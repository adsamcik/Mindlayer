#!/usr/bin/env bash
# One-shot dev install loop for the Mindlayer service APK + on-device AI models.
#
# Combines the three steps a dev hits every time they iterate on the
# service while still depending on the (multi-GB) Gemma/EmbeddingGemma/
# PaddleOCR models:
#
#   1. Build the debug APK WITHOUT bundling the AI Asset Packs
#      (-Pmindlayer.bundle{Gemma,Embeddings,Paddleocr}=false). Keeps
#      the build under ~30 s and the APK under ~80 MB.
#   2. `adb install -r` the freshly-built APK. `-r` preserves the
#      app's externalFilesDir, so models pushed earlier survive a
#      code-only reinstall.
#   3. Run tools/dev-models/push-models.sh for any model group the
#      runtime reports as missing on the device. Already-present
#      files with a size match are skipped (see push-models.sh
#      `--force` to override).
#
# Use this instead of plain `adb install -r app-debug.apk` (which
# silently leaves the device without models) or `adb uninstall`
# (which WIPES the pushed models because uninstall deletes
# externalFilesDir).
#
# Requires: adb on PATH, JDK 21 on PATH for ./gradlew (see
# .github/context/DEVELOPMENT.md for the JDK 21 gotcha), and a populated
# model cache: the standardized <repo-root>/.models directory (gitignored),
# or an explicit --cache/$MINDLAYER_MODEL_CACHE override (see
# docs/models/DEV_MODELS.md for what to put in it).

set -euo pipefail

CACHE="${MINDLAYER_MODEL_CACHE:-}"
DEVICE=''
SKIP_BUILD=0
SKIP_INSTALL=0
FORCE=0
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: dev-install.sh [--cache <dir>] [--device <serial>]
                      [--skip-build] [--skip-install] [--force] [--dry-run]

  --cache <dir>     Local model cache (default: $MINDLAYER_MODEL_CACHE,
                    else <repo-root>/.models if it exists).
                    Forwarded to push-models.sh.
  --device <id>     adb device serial when multiple devices attach.
  --skip-build      Reuse the existing app/build/outputs/apk/debug/app-debug.apk
                    instead of running Gradle. Useful when the APK was
                    built by another process (CI, Android Studio).
  --skip-install    Don't run `adb install`. Useful for pushing models
                    onto a device whose APK is already current.
  --force           Forward to push-models.sh — re-push every file even
                    when a same-name, same-size file is already on device.
  --dry-run         Skip the Gradle build + adb install, and forward
                    --dry-run to push-models.sh.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --cache)        shift; CACHE="${1:-}" ;;
    --device)       shift; DEVICE="${1:-}" ;;
    --skip-build)   SKIP_BUILD=1 ;;
    --skip-install) SKIP_INSTALL=1 ;;
    --force)        FORCE=1 ;;
    --dry-run)      DRY_RUN=1 ;;
    -h|--help)      usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; usage; exit 2 ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PUSH_SCRIPT="$REPO_ROOT/tools/dev-models/push-models.sh"
APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

# ---------------------------------------------------------------------------
# Phase 1 — build (code-only APK, AI Packs excluded)
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" -eq 1 ] || [ "$DRY_RUN" -eq 1 ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "[dry-run] would build :app:assembleDebug without AI packs"
  else
    echo "[skip-build] reusing existing APK at $APK_PATH"
  fi
else
  echo "== Building code-only debug APK (no AI Asset Packs bundled) =="
  ( cd "$REPO_ROOT"
    ./gradlew :app:assembleDebug \
      "-Pmindlayer.bundleGemma=false" \
      "-Pmindlayer.bundleEmbeddings=false" \
      "-Pmindlayer.bundlePaddleocr=false" \
      --console=plain
  )
fi

if [ "$SKIP_INSTALL" -eq 0 ] && [ "$DRY_RUN" -eq 0 ] && [ ! -f "$APK_PATH" ]; then
  echo "error: APK not found at $APK_PATH — pass --skip-build only when one already exists." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Phase 2 — install (preserves externalFilesDir; never `adb uninstall`)
# ---------------------------------------------------------------------------
adb_args() { if [ -n "$DEVICE" ]; then printf -- '-s\n%s\n' "$DEVICE"; fi; }
run_adb() { adb $(adb_args) "$@"; }

if [ "$SKIP_INSTALL" -eq 1 ] || [ "$DRY_RUN" -eq 1 ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "[dry-run] would: adb install -r -t $APK_PATH"
    echo "[dry-run] would: adb shell am start -W -n <pkg>/...MainActivity"
  else
    echo "[skip-install] not running adb install"
  fi
else
  echo "== Installing APK (adb install -r preserves externalFilesDir) =="
  if ! run_adb install -r -t "$APK_PATH"; then
    echo "error: adb install failed" >&2
    exit 1
  fi

  # Launch the dashboard once so the OS creates the service's externalFilesDir
  # under the right UID. Without this, push-models writes to a FUSE phantom
  # directory that the app's own context.getExternalFilesDir(null) never sees
  # — pushes report success and the registries report "Discovered 0 bundles"
  # because they're looking at a different inode. Order of pkg probes mirrors
  # ConnectionManager's debug-suffix fallback.
  echo "== Launching dashboard once so the OS creates externalFilesDir =="
  launched=0
  for pkg in com.adsamcik.mindlayer.debug com.adsamcik.mindlayer; do
    if run_adb shell pm list packages "$pkg" 2>/dev/null | tr -d '\r' | grep -qx "package:$pkg"; then
      if run_adb shell am start -W -n "$pkg/com.adsamcik.mindlayer.service.ui.MainActivity" >/dev/null; then
        echo "  launched $pkg/.MainActivity (externalFilesDir is now real)"
        launched=1
      else
        echo "  warning: failed to launch $pkg" >&2
      fi
      break
    fi
  done
  if [ "$launched" -eq 0 ]; then
    echo "warning: Mindlayer service not detected on the device; push-models will" >&2
    echo "         try anyway but is likely to land in a FUSE phantom directory" >&2
    echo "         the app cannot read." >&2
  fi
  # Brief settle so the externalFilesDir mkdir + permissions are fully
  # observable to the next `adb push`. 1.5s is more than enough on real
  # devices; emulators are sometimes slower.
  sleep 1.5
fi

# ---------------------------------------------------------------------------
# Phase 3 — push models (no-op when already on device + sizes match)
# ---------------------------------------------------------------------------
echo "== Pushing missing model files (skips already-present, size-matched files) =="

if [ ! -x "$PUSH_SCRIPT" ] && [ ! -f "$PUSH_SCRIPT" ]; then
  echo "error: expected $PUSH_SCRIPT to exist — did the repo layout change?" >&2
  exit 1
fi

push_args=( --all )
if [ -n "$CACHE" ];  then push_args+=( --cache "$CACHE" ); fi
if [ -n "$DEVICE" ]; then push_args+=( --device "$DEVICE" ); fi
if [ "$FORCE" -eq 1 ];   then push_args+=( --force ); fi
if [ "$DRY_RUN" -eq 1 ]; then push_args+=( --dry-run ); fi

if ! bash "$PUSH_SCRIPT" "${push_args[@]}"; then
  echo "error: push-models.sh failed. See its output above for which model group failed." >&2
  exit 1
fi

cat <<'EOF'

Dev install loop complete.
Reminder: NEVER use 'adb uninstall com.adsamcik.mindlayer.debug' —
          uninstall wipes externalFilesDir and you will lose ~3 GB of pushed models.
          Use './scripts/dev-install.sh' or 'adb install -r' instead.
EOF
