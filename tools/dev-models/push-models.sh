#!/usr/bin/env bash
# Dev-only sideload of Mindlayer on-device AI models to a connected
# debuggable Android device via adb.
#
# Pushes model files from a local cache into the Mindlayer service's
# externalFilesDir (/sdcard/Android/data/<pkg>/files) on the device.
# The runtime registries scan that directory on debuggable builds
# (BuildConfig.DEBUG) and load whatever they find. The script detects
# whether the debug or release service variant is installed; if
# neither is, it falls back to /data/local/tmp/ with a loud warning
# (apps cannot list /data/local/tmp on Android 12+ even when files
# inside are world-readable). This script does NOT download anything
# — see docs/DEV_MODELS.md for sources.
#
# Bash 3.2 compatible (default macOS bash). No GNU-only flags.

set -euo pipefail

LEGACY_REMOTE_DIR='/data/local/tmp'
SERVICE_PKG_RELEASE='com.adsamcik.mindlayer.service'
SERVICE_PKG_DEBUG='com.adsamcik.mindlayer.service.debug'
# Resolved at runtime via resolve_remote_dir; seeded with the legacy
# path so REMOTE_DIR is always set.
REMOTE_DIR="$LEGACY_REMOTE_DIR"
USING_LEGACY_REMOTE_DIR=1
ZERO_SHA='0000000000000000000000000000000000000000000000000000000000000000'

GEMMA_FILE='gemma-4-E2B-it.litertlm'
GEMMA_MANIFEST='gemma_model/src/main/assets/model_integrity.json'

EMBEDDING_FILES=(
  'embedding-gemma-300m-v1.tflite'
  'embedding-gemma-300m-v1.spm.model'
)
EMBEDDING_MANIFEST='gemma_embed_model/src/main/assets/embedding_model_integrity.json'

PADDLE_FILES=(
  'paddleocr-ppocrv5-mobile-det.tflite'
  'paddleocr-ppocrv5-mobile-rec.tflite'
  'paddleocr-ppocrv5-mobile-cls.tflite'
  'paddleocr-ppocrv5-mobile-dict.txt'
)
PADDLE_MANIFEST='paddleocr_model/src/main/assets/paddleocr_model_integrity.json'

GEMMA=0
EMBEDDINGS=0
PADDLEOCR=0
ALL=0
CACHE="${MINDLAYER_MODEL_CACHE:-}"
DEVICE=''
DRY_RUN=0
PREFER_LEGACY_TMP=0
FORCE=0

usage() {
  cat <<'EOF'
Usage: push-models.sh [--gemma] [--embeddings] [--paddleocr] [--all]
                      [--cache <dir>] [--device <serial>] [--dry-run]
                      [--prefer-legacy-tmp] [--force]

  --gemma              Push the chat model (Gemma 4 E2B .litertlm).
  --embeddings         Push EmbeddingGemma weights + tokenizer.
  --paddleocr          Push the four PaddleOCR PP-OCRv5 mobile files.
  --all                Equivalent to --gemma --embeddings --paddleocr.
  --cache <dir>        Local cache directory (default: $MINDLAYER_MODEL_CACHE).
  --device <id>        adb device serial when multiple devices are connected.
  --dry-run            Print actions without invoking 'adb push'. Assumes
                       the debug service variant is installed so the dry-run
                       preview matches a normal dev device.
  --prefer-legacy-tmp  Force the legacy /data/local/tmp/ push target
                       regardless of installed-service detection. Useful
                       for the API <= 30 fallback and for testing.
  --force              Skip the "remote already has a file of the same
                       size" optimization and push every file
                       unconditionally. Without --force the script
                       'adb shell stat -c %s'-checks the remote and
                       skips the multi-GB push when sizes match.
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --gemma)              GEMMA=1 ;;
    --embeddings)         EMBEDDINGS=1 ;;
    --paddleocr)          PADDLEOCR=1 ;;
    --all)                ALL=1 ;;
    --cache)              shift; CACHE="${1:-}" ;;
    --device)             shift; DEVICE="${1:-}" ;;
    --dry-run)            DRY_RUN=1 ;;
    --prefer-legacy-tmp)  PREFER_LEGACY_TMP=1 ;;
    --force)              FORCE=1 ;;
    -h|--help)            usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; usage; exit 2 ;;
  esac
  shift
done

if [ "$ALL" -eq 1 ]; then GEMMA=1; EMBEDDINGS=1; PADDLEOCR=1; fi
if [ "$GEMMA" -eq 0 ] && [ "$EMBEDDINGS" -eq 0 ] && [ "$PADDLEOCR" -eq 0 ]; then
  echo "error: specify at least one of --gemma, --embeddings, --paddleocr, --all" >&2
  usage
  exit 2
fi
if [ -z "$CACHE" ]; then
  echo "error: no cache directory. Pass --cache <path> or set MINDLAYER_MODEL_CACHE." >&2
  exit 2
fi
if [ ! -d "$CACHE" ]; then
  echo "error: cache directory does not exist: $CACHE" >&2
  exit 2
fi

# Resolve repo root: this script lives at tools/dev-models/.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE="$(cd "$CACHE" && pwd)"

adb_args() {
  if [ -n "$DEVICE" ]; then printf -- '-s\n%s\n' "$DEVICE"; fi
}

run_adb() {
  # shellcheck disable=SC2046
  adb $(adb_args) "$@"
}

assert_adb() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "error: 'adb' not on PATH. Install Android platform-tools." >&2
    exit 1
  fi
}

assert_device() {
  local out devices count
  out="$(run_adb devices)"
  devices="$(echo "$out" | awk 'NR>1 && $2=="device" {print $1}')"
  count="$(echo "$devices" | awk 'NF>0' | wc -l | tr -d ' ')"
  if [ "$count" -eq 0 ]; then
    echo "error: no adb devices in state 'device'. Connect a device." >&2
    exit 1
  fi
  if [ "$count" -gt 1 ] && [ -z "$DEVICE" ]; then
    echo "error: multiple devices connected; pass --device <serial>." >&2
    echo "$devices" >&2
    exit 1
  fi
}

assert_debuggable() {
  # Sideload is only safe when the receiving service trusts the push
  # target. Either the device is debuggable (every installed app
  # becomes Debug.isDebuggable() == true) OR the debug variant of the
  # Mindlayer service is installed (its BuildConfig.DEBUG is true
  # regardless of device build type). Mirrors Assert-DebuggableDevice
  # in push-models.ps1.
  local debuggable build_type
  debuggable="$(run_adb shell getprop ro.debuggable | tr -d '\r\n ')"
  build_type="$(run_adb shell getprop ro.build.type | tr -d '\r\n ')"
  if [ "$debuggable" = "1" ] || [ "$build_type" = "userdebug" ] || [ "$build_type" = "eng" ]; then
    return 0
  fi
  # Device-level guard failed; fall back to package-level evidence.
  if run_adb shell pm list packages "$SERVICE_PKG_DEBUG" 2>/dev/null | tr -d '\r' | grep -qx "package:$SERVICE_PKG_DEBUG"; then
    echo "note: device is ro.debuggable='$debuggable' ro.build.type='$build_type'" >&2
    echo "      (non-debuggable). Continuing because the debug variant" >&2
    echo "      '$SERVICE_PKG_DEBUG' is installed; its runtime BuildConfig.DEBUG" >&2
    echo "      gate is the authoritative check." >&2
    return 0
  fi
  echo "error: Mindlayer sideload requires either a debuggable device" >&2
  echo "       (ro.debuggable=1 or ro.build.type in {userdebug, eng}) OR" >&2
  echo "       the debug variant '$SERVICE_PKG_DEBUG' to be installed." >&2
  echo "       Got ro.debuggable='$debuggable' ro.build.type='$build_type'" >&2
  echo "       and the debug service package is NOT installed." >&2
  echo "       Install the debug build of :app first (./gradlew :app:installDebug)," >&2
  echo "       or run on a debuggable device." >&2
  exit 1
}

# Resolve the on-device push target. Sets REMOTE_DIR, USING_LEGACY_REMOTE_DIR,
# RESOLVED_PKG. Mirrors Resolve-RemoteDir in push-models.ps1.
resolve_remote_dir() {
  RESOLVED_PKG=''
  if [ "$PREFER_LEGACY_TMP" -eq 1 ]; then
    REMOTE_DIR="$LEGACY_REMOTE_DIR"
    USING_LEGACY_REMOTE_DIR=1
    return 0
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    REMOTE_DIR="/sdcard/Android/data/$SERVICE_PKG_DEBUG/files"
    USING_LEGACY_REMOTE_DIR=0
    RESOLVED_PKG="$SERVICE_PKG_DEBUG"
    return 0
  fi
  local pkg listing
  for pkg in "$SERVICE_PKG_DEBUG" "$SERVICE_PKG_RELEASE"; do
    listing="$(run_adb shell pm list packages "$pkg" 2>/dev/null || true)"
    if echo "$listing" | tr -d '\r' | grep -qx "package:$pkg"; then
      REMOTE_DIR="/sdcard/Android/data/$pkg/files"
      USING_LEGACY_REMOTE_DIR=0
      RESOLVED_PKG="$pkg"
      return 0
    fi
  done
  echo "warning: Mindlayer service not installed on device" >&2
  echo "         ($SERVICE_PKG_DEBUG / $SERVICE_PKG_RELEASE)." >&2
  echo "         Falling back to $LEGACY_REMOTE_DIR — this MAY FAIL on Android 12+" >&2
  echo "         (API 31+) because apps can no longer list /data/local/tmp/ even when" >&2
  echo "         files inside are world-readable. Install a debug build of :app first," >&2
  echo "         then re-run." >&2
  REMOTE_DIR="$LEGACY_REMOTE_DIR"
  USING_LEGACY_REMOTE_DIR=1
}

# Create REMOTE_DIR on the device when it's the externalFilesDir variant.
# externalFilesDir is normally created by the app on first launch; mkdir -p
# is cheap and safe. Skipped in dry-run and for the legacy path.
init_remote_dir() {
  if [ "$DRY_RUN" -eq 1 ] || [ "$USING_LEGACY_REMOTE_DIR" -eq 1 ]; then
    return 0
  fi
  if ! run_adb shell mkdir -p "$REMOTE_DIR" >/dev/null; then
    echo "error: failed to create remote dir $REMOTE_DIR" >&2
    exit 1
  fi
}

sha256_of() {
  # macOS + Linux: shasum -a 256
  shasum -a 256 "$1" | awk '{print tolower($1)}'
}

# Look up a SHA for a filename in a manifest JSON. Supports both schemas:
#   { "modelFile": "...", "sha256": "..." }            (single-file)
#   { "models": [ { "filename": "...", "sha256": "..." }, ... ] }
manifest_sha_for() {
  local manifest="$1" filename="$2"
  if [ ! -f "$manifest" ]; then echo ''; return 0; fi

  # Try single-file form.
  local single_file single_sha
  single_file="$(awk -F'"' '/"modelFile"/ {print $4; exit}' "$manifest")"
  single_sha="$(awk -F'"' '/"sha256"/ {print $4; exit}' "$manifest")"
  if [ -n "$single_file" ] && [ "$single_file" = "$filename" ]; then
    echo "$single_sha" | tr '[:upper:]' '[:lower:]'
    return 0
  fi

  # models[] form: walk the file pairwise (filename then sha256).
  awk -v target="$filename" '
    /"filename"/ {
      fn=$0; sub(/.*"filename"[^"]*"/, "", fn); sub(/".*/, "", fn);
      next_fn=fn;
    }
    /"sha256"/ && next_fn!="" {
      sha=$0; sub(/.*"sha256"[^"]*"/, "", sha); sub(/".*/, "", sha);
      if (next_fn==target) { print tolower(sha); exit }
      next_fn="";
    }
  ' "$manifest"
}

FAILURES=()
add_failure() { FAILURES+=("$1"); echo "  FAIL: $1" >&2; }

verify_sha() {
  local local_path="$1" filename="$2" manifest="$3"
  local expected
  expected="$(manifest_sha_for "$manifest" "$filename")"
  if [ -z "$expected" ]; then
    echo "  sha: no manifest entry for $filename — skipping verification."
    return 0
  fi
  if [ "$expected" = "$ZERO_SHA" ]; then
    echo "  sha: manifest SHA not populated (dev placeholder), skipping verification."
    return 0
  fi
  local actual
  actual="$(sha256_of "$local_path")"
  if [ "$actual" != "$expected" ]; then
    echo "  sha MISMATCH for $filename"
    echo "    expected: $expected"
    echo "    actual:   $actual"
    return 1
  fi
  echo "  sha: OK ($actual)"
  return 0
}

push_one() {
  local local_path="$1" filename="$2"
  local remote="$REMOTE_DIR/$filename"
  local local_size
  local_size="$(wc -c < "$local_path" | tr -d ' ')"

  # Guard: refuse a stale / mis-converted PaddleOCR model that carries an
  # unresolved ONNX_LAYERNORMALIZATION custom op. Mirrors the build-time guard
  # in scripts/build-paddleocr-models/convert.sh: such a rec model fails to
  # invoke on every accelerator, so OCR silently returns 0 lines.
  case "$filename" in
    paddleocr-*.tflite)
      if grep -aq 'ONNX_LAYERNORMALIZATION' "$local_path"; then
        echo "error: refusing to push '$filename': it contains an unresolved" >&2
        echo "       ONNX_LAYERNORMALIZATION custom op (stale or mis-converted model" >&2
        echo "       that fails to invoke on every accelerator). Rebuild via" >&2
        echo "       scripts/build-paddleocr-models (or download the latest CI artifact)" >&2
        echo "       and refresh your model cache before pushing." >&2
        return 1
      fi
      ;;
  esac

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  [dry-run] adb $(adb_args | tr '\n' ' ')push $local_path $remote"
    echo "  [dry-run] adb $(adb_args | tr '\n' ' ')shell ls -l $remote"
    return 0
  fi

  # Skip already-pushed files whose size matches the local cache.
  # `adb push` is roughly 80 MB/s on a good USB-3 link — pushing a
  # 2.4 GB Gemma model is 30+ seconds you can avoid every iteration.
  # `stat -c %s` is the Android toybox stat invocation; missing-file
  # exits non-zero, which we treat as "needs pushing".
  if [ "$FORCE" -eq 0 ]; then
    local remote_size
    remote_size="$(run_adb shell stat -c %s "$remote" 2>/dev/null | tr -d '\r\n ')"
    if [ -n "$remote_size" ] && [ "$remote_size" = "$local_size" ]; then
      echo "  skip: already on device with matching size ($remote_size bytes). Use --force to override."
      return 0
    fi
    if [ -n "$remote_size" ]; then
      echo "  size differs (remote=$remote_size, local=$local_size); re-pushing."
    fi
  fi

  echo "  pushing $filename ($local_size bytes) -> $remote"
  if ! run_adb push "$local_path" "$remote" >/dev/null; then
    return 1
  fi
  local ls_out
  ls_out="$(run_adb shell ls -l "$remote" || true)"
  if echo "$ls_out" | grep -q "$local_size"; then
    echo "  verified size $local_size bytes on device."
  else
    echo "  warning: could not confirm size from: $ls_out"
  fi
  return 0
}

process_group() {
  local label="$1" manifest_rel="$2"; shift 2
  local manifest="$REPO_ROOT/$manifest_rel"
  echo ""
  echo "=== $label ==="
  local f
  for f in "$@"; do
    local local_path="$CACHE/$f"
    if [ ! -f "$local_path" ]; then
      add_failure "$label: missing '$f' in cache '$CACHE'. See docs/DEV_MODELS.md#sources."
      continue
    fi
    echo "- $f"
    if ! verify_sha "$local_path" "$f" "$manifest"; then
      add_failure "$label: SHA mismatch for $f"
      continue
    fi
    if ! push_one "$local_path" "$f"; then
      add_failure "$label: push failed for $f"
    fi
  done
}

echo "Mindlayer dev model sideload"
echo "  repo:   $REPO_ROOT"
echo "  cache:  $CACHE"
echo "  device: ${DEVICE:-(auto)}"
echo "  dryRun: $DRY_RUN"

if [ "$DRY_RUN" -eq 0 ]; then
  assert_adb
  assert_device
  assert_debuggable
else
  echo "  (skipping adb/device checks in dry-run mode)"
fi

resolve_remote_dir
if [ "$USING_LEGACY_REMOTE_DIR" -eq 1 ]; then
  echo "  remote: $REMOTE_DIR  (LEGACY — /data/local/tmp is unlistable on API 31+)"
else
  echo "  remote: $REMOTE_DIR  (service pkg: $RESOLVED_PKG)"
fi
init_remote_dir

if [ "$GEMMA" -eq 1 ]; then
  process_group 'Gemma chat' "$GEMMA_MANIFEST" "$GEMMA_FILE"
fi
if [ "$EMBEDDINGS" -eq 1 ]; then
  process_group 'EmbeddingGemma' "$EMBEDDING_MANIFEST" "${EMBEDDING_FILES[@]}"
fi
if [ "$PADDLEOCR" -eq 1 ]; then
  process_group 'PaddleOCR PP-OCRv5 mobile' "$PADDLE_MANIFEST" "${PADDLE_FILES[@]}"
fi

echo ''
if [ "${#FAILURES[@]}" -eq 0 ]; then
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "Done. All requested files processed cleanly (dry-run)."
  else
    echo "Done. All requested files processed cleanly."
  fi
  exit 0
else
  echo "Done with ${#FAILURES[@]} failure(s):"
  for msg in "${FAILURES[@]}"; do echo "  - $msg"; done
  exit 1
fi

