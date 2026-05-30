#!/usr/bin/env bash
# Driver for the CI `instrumented-tests` matrix.
#
# Why this script exists
# ----------------------
# The `reactivecircus/android-emulator-runner@v2.37.0` action's
# `parseScript` helper splits its `script:` input on every newline and
# runs *each line* in its own `sh -c` invocation. That means multi-line
# shell constructs (`if/then/else/fi`, `for`, backslash-continued
# commands, …) silently break: the first line runs alone, the shell
# errors out with `Syntax error: end of file unexpected (expecting "fi")`,
# and every subsequent line either runs in the wrong order or not at all.
#
# Source:
#   https://github.com/ReactiveCircus/android-emulator-runner/blob/v2.37.0/src/script-parser.ts
#
# Workaround: keep the workflow `script:` input to a single line that
# just invokes this file, and put the real logic here where bash can
# parse it as one program.
#
# Inputs (read from environment)
# ------------------------------
#   HAVE_AI_PACK            — "true" when the workflow's `Provision AI
#                              Pack assets` step verified all required
#                              PaddleOCR asset files and SHA-256s.
#   PADDLEOCR_DET_SHA256    — repository var, required when HAVE_AI_PACK=true
#   PADDLEOCR_REC_SHA256    — repository var, required when HAVE_AI_PACK=true
#   PADDLEOCR_CLS_SHA256    — repository var, required when HAVE_AI_PACK=true
#   PADDLEOCR_DICT_SHA256   — repository var, required when HAVE_AI_PACK=true

set -euo pipefail

have_pack="${HAVE_AI_PACK:-false}"

if [ "$have_pack" = "true" ]; then
    ./gradlew \
        :paddleocr_model:assembleDebug \
        --no-daemon \
        -PpaddleOcrDetSha256="${PADDLEOCR_DET_SHA256}" \
        -PpaddleOcrRecSha256="${PADDLEOCR_REC_SHA256}" \
        -PpaddleOcrClsSha256="${PADDLEOCR_CLS_SHA256}" \
        -PpaddleOcrDictSha256="${PADDLEOCR_DICT_SHA256}"
    ./gradlew \
        :app:connectedDebugAndroidTest \
        :sdk:connectedDebugAndroidTest \
        --no-daemon \
        -PpaddleOcrDetSha256="${PADDLEOCR_DET_SHA256}" \
        -PpaddleOcrRecSha256="${PADDLEOCR_REC_SHA256}" \
        -PpaddleOcrClsSha256="${PADDLEOCR_CLS_SHA256}" \
        -PpaddleOcrDictSha256="${PADDLEOCR_DICT_SHA256}"
else
    ./gradlew :app:connectedDebugAndroidTest :sdk:connectedDebugAndroidTest --no-daemon
fi
