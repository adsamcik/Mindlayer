#!/usr/bin/env bash
# Run Mindlayer instrumented (androidTest) tests on a dev device/emulator
# WITHOUT wiping the sideloaded AI models.
#
# Why this script exists
# ----------------------
# `./gradlew :app:connectedDebugAndroidTest` (and Android Studio's "Run" gutter)
# drive AGP's install -> test -> *uninstall* cycle. That final uninstall deletes
# the service app's externalFilesDir, taking the ~3 GB of pushed Gemma /
# EmbeddingGemma / PaddleOCR models with it — the next run then fails with
# `MLERR:1003:Model file missing` until you re-push (minutes over a qemu pipe).
# CI is the only safe place for `connectedAndroidTest` because CI AVDs are
# throw-away.
#
# This script runs the same tests the model-preserving way:
#   1. Build the code-only app APK (-Pmindlayer.bundle*=false) + androidTest APK.
#   2. `adb install -r` both APKs — reinstall in place, PRESERVING
#      externalFilesDir. It never uninstalls.
#   3. `adb shell am instrument -w` the test package directly, optionally
#      filtered to a class or package.
# It NEVER calls `adb uninstall` / `pm clear`, so the models survive every run.
#
# Usage:
#   scripts/dev-instrument.sh [--module app|sdk] [--class FQN] [--package PKG]
#                             [--device SERIAL] [--skip-build] [--dry-run]
#
# Examples:
#   # One fast, model-independent class — proves the loop preserves models.
#   scripts/dev-instrument.sh --class com.adsamcik.mindlayer.service.security.DbKeyProviderTest
#   # Whole app suite against a specific emulator.
#   scripts/dev-instrument.sh --device emulator-5554
#   # SDK instrumented tests (self-instrumenting; model-safe anyway).
#   scripts/dev-instrument.sh --module sdk

set -euo pipefail

module="app"
test_class=""
test_package=""
device=""
skip_build=false
dry_run=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --module)     module="$2"; shift 2 ;;
        --class)      test_class="$2"; shift 2 ;;
        --package)    test_package="$2"; shift 2 ;;
        --device)     device="$2"; shift 2 ;;
        --skip-build) skip_build=true; shift ;;
        --dry-run)    dry_run=true; shift ;;
        -h|--help)    grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [[ "$module" != "app" && "$module" != "sdk" ]]; then
    echo "--module must be 'app' or 'sdk'" >&2; exit 2
fi
if [[ -n "$test_class" && -n "$test_package" ]]; then
    echo "Pass at most one of --class / --package" >&2; exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
cd "$repo_root"

# ---------------------------------------------------------------------------
# Module-specific layout
# ---------------------------------------------------------------------------
if [[ "$module" == "app" ]]; then
    instrumentation_pkg="com.adsamcik.mindlayer.service.debug.test"
    apks=(
        "app/build/outputs/apk/debug/app-debug.apk"
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    )
    build_tasks=(
        ":app:assembleDebug"
        ":app:assembleDebugAndroidTest"
        "-Pmindlayer.bundleGemma=false"
        "-Pmindlayer.bundleEmbeddings=false"
        "-Pmindlayer.bundlePaddleocr=false"
    )
else
    instrumentation_pkg="com.adsamcik.mindlayer.sdk.test"
    apks=("sdk/build/outputs/apk/androidTest/debug/sdk-debug-androidTest.apk")
    build_tasks=(":sdk:assembleDebugAndroidTest")
fi
runner="${instrumentation_pkg}/androidx.test.runner.AndroidJUnitRunner"

adb_pre=(adb)
[[ -n "$device" ]] && adb_pre=(adb -s "$device")

run_adb() {
    if [[ "$dry_run" == true ]]; then
        echo "[dry-run] ${adb_pre[*]} $*"
        return 0
    fi
    "${adb_pre[@]}" "$@"
}

# ---------------------------------------------------------------------------
# Phase 1 — build
# ---------------------------------------------------------------------------
if [[ "$skip_build" == true ]]; then
    echo "[skip-build] reusing existing APK(s)"
else
    echo "== Building $module instrumented-test APK(s) (code-only; models stay on device) =="
    if [[ "$dry_run" == true ]]; then
        echo "[dry-run] ./gradlew ${build_tasks[*]} --console=plain"
    else
        ./gradlew "${build_tasks[@]}" --console=plain
    fi
fi

if [[ "$dry_run" != true ]]; then
    for apk in "${apks[@]}"; do
        [[ -s "$apk" ]] || { echo "APK not found at $apk — run without --skip-build to build it." >&2; exit 1; }
    done
fi

# ---------------------------------------------------------------------------
# Phase 2 — install (adb install -r preserves externalFilesDir; NEVER uninstall)
# ---------------------------------------------------------------------------
echo "== Installing APK(s) with adb install -r (models preserved) =="
for apk in "${apks[@]}"; do
    run_adb install -r -t "$apk"
done

# ---------------------------------------------------------------------------
# Phase 3 — instrument (no uninstall, ever)
# ---------------------------------------------------------------------------
instrument_args=(shell am instrument -w)
if [[ -n "$test_class" ]]; then
    instrument_args+=(-e class "$test_class")
elif [[ -n "$test_package" ]]; then
    instrument_args+=(-e package "$test_package")
fi
instrument_args+=("$runner")

echo "== Running instrumented tests: $runner =="
if [[ "$dry_run" == true ]]; then
    run_adb "${instrument_args[@]}"
    echo ""
    echo "Done (dry-run)."
    exit 0
fi

out="$(run_adb "${instrument_args[@]}" 2>&1)"
echo "$out"

echo ""
echo "Done — externalFilesDir (and the ~3 GB of models) was never touched."
echo "This script never runs 'adb uninstall' / 'pm clear'. Do NOT use"
echo "'connectedDebugAndroidTest' on a model-loaded device — it uninstalls and wipes them."

# `am instrument` exits 0 even when tests fail — the verdict is in the text.
if grep -qE 'FAILURES!!!|INSTRUMENTATION_CODE: -1|Process crashed|^Error:|INSTRUMENTATION_RESULT: shortMsg' <<<"$out"; then
    echo "Instrumented tests reported failures (see output above)." >&2
    exit 1
fi
if ! grep -qE 'OK \([0-9]+ test' <<<"$out"; then
    echo "Could not confirm a passing run (no 'OK (N tests)' marker). Treating as failure." >&2
    exit 1
fi
echo "Instrumented tests passed."
