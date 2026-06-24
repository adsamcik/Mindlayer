#!/usr/bin/env bash
# Driver for the PaddleOCR PP-OCRv5 mobile -> ONNX -> TFLite conversion.
# Mirrors the steps in .github/workflows/build-paddleocr-models.yml so
# the local Docker run and the CI run produce byte-identical artifacts.
#
# This is the **source of truth** for the conversion pipeline. The CI
# workflow inlines the same pip pins + bash logic to stay close to a
# clean Ubuntu runner; if you change anything material here update the
# workflow at the same time.
#
# Outputs land in /work/out (mount a host directory to get them out):
#   paddleocr-ppocrv5-mobile-det.tflite
#   paddleocr-ppocrv5-mobile-rec.tflite
#   paddleocr-ppocrv5-mobile-cls.tflite
#   paddleocr-ppocrv5-mobile-dict.txt
#   expected_shas.txt

set -euo pipefail

PADDLEOCR_REF="${PADDLEOCR_REF:-v3.5.0}"

WORKDIR=/work
SRC_DIR="${WORKDIR}/paddle-src"
OUT_DIR="${WORKDIR}/out"
mkdir -p "${SRC_DIR}" "${OUT_DIR}"

cd "${SRC_DIR}"

echo "==> Fetching PaddleOCR source ${PADDLEOCR_REF}"
curl -fsSL -o paddleocr-src.tar.gz \
    "https://github.com/PaddlePaddle/PaddleOCR/archive/refs/tags/${PADDLEOCR_REF}.tar.gz"
tar -xzf paddleocr-src.tar.gz --strip-components=1 -C .

base_url="https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
declare -A model_urls=(
    [det]="${base_url}/PP-OCRv5_mobile_det_infer.tar"
    [rec]="${base_url}/PP-OCRv5_mobile_rec_infer.tar"
    [cls]="${base_url}/PP-LCNet_x0_25_textline_ori_infer.tar"
)
declare -A model_dirs=(
    [det]="PP-OCRv5_mobile_det_infer"
    [rec]="PP-OCRv5_mobile_rec_infer"
    [cls]="PP-LCNet_x0_25_textline_ori_infer"
)

for kind in det rec cls; do
    echo "==> Fetching paddle inference model: ${kind}"
    tar_name="${model_dirs[$kind]}.tar"
    curl -fsSL -o "$tar_name" "${model_urls[$kind]}"
    tar -xf "$tar_name"
done

echo "==> Paddle -> ONNX"
for kind in det rec cls; do
    dir="${model_dirs[$kind]}"
    /opt/venv-paddle2onnx/bin/paddle2onnx \
        --model_dir "$dir" \
        --model_filename inference.json \
        --params_filename inference.pdiparams \
        --save_file "${OUT_DIR}/paddleocr-ppocrv5-mobile-${kind}.onnx" \
        --opset_version 17 \
        --enable_onnx_checker True
done

declare -A onnx_input_dims=(
    [det]="1,3,640,640"
    [rec]="1,3,48,320"
    [cls]="1,3,80,160"
)

echo "==> ONNX -> TFLite (with -ofgd for GPU delegate compatibility)"
cd "${OUT_DIR}"
for kind in det rec cls; do
    onnx="paddleocr-ppocrv5-mobile-${kind}.onnx"
    fixed_onnx="paddleocr-ppocrv5-mobile-${kind}-fixed.onnx"
    simp_onnx="paddleocr-ppocrv5-mobile-${kind}-simp.onnx"

    input_name=$(/opt/venv-paddle2onnx/bin/python - "$onnx" "$fixed_onnx" "${onnx_input_dims[$kind]}" <<'PY'
import sys
import onnx

model = onnx.load(sys.argv[1])
output_path = sys.argv[2]
static_shape = [int(part) for part in sys.argv[3].split(",")]
graph_input = model.graph.input[0]
dims = graph_input.type.tensor_type.shape.dim
for dim, value in zip(dims, static_shape):
    dim.ClearField("dim_param")
    dim.dim_value = value
onnx.save(model, output_path)
print(graph_input.name)
PY
)
    /opt/venv-onnx2tf/bin/onnxsim \
        "$fixed_onnx" "$simp_onnx" \
        --overwrite-input-shape "${input_name}:${onnx_input_dims[$kind]}"

    # ONNX QKV-split surgery — rewrites the fused SVTR attention QKV
    # projection from a single Linear → 5D Reshape → Slice/Squeeze chain
    # into three independent 4D Q/K/V projections, eliminating every
    # 5D RESHAPE/TRANSPOSE intermediate that the LiteRT 2.1.5 GPU
    # delegate cannot compile. Only the rec model has SVTR attention;
    # det and cls are no-ops (the script self-detects no chains and
    # passes through unchanged). See docs/ocr/PADDLEOCR_GPU_INVESTIGATION.md.
    qkv_surgery_py="$(dirname "$0")/onnx_split_qkv.py"
    if [ ! -f "$qkv_surgery_py" ]; then
        qkv_surgery_py="/usr/local/bin/onnx_split_qkv.py"
    fi
    split_onnx="paddleocr-ppocrv5-mobile-${kind}-split.onnx"
    /opt/venv-onnx2tf/bin/python "$qkv_surgery_py" "$simp_onnx" "$split_onnx"
    mv "$split_onnx" "$simp_onnx"

    # -ofgd: replace GPU-incompatible ops with supported equivalents
    #        where onnx2tf knows how to (e.g. TRANSPOSE_CONV downgrades).
    #        Currently a no-op for the two known blockers — see
    #        docs/ocr/PADDLEOCR_GPU_INVESTIGATION.md.
    # -cgdc: GPU delegate compatibility check; emits a report at
    #        conversion time so a future onnx2tf bump that regresses
    #        op-level GPU support is loudly visible in the workflow log.
    # -tb tf_converter: decomposes LayerNorm to native ops (rec needs this).
    /opt/venv-onnx2tf/bin/onnx2tf \
        -i "$simp_onnx" \
        -o "ppocrv5-${kind}-saved" \
        -tb tf_converter \
        -ofgd \
        -cgdc \
        -ois "${input_name}:${onnx_input_dims[$kind]}" \
        -osd

    generated="ppocrv5-${kind}-saved/paddleocr-ppocrv5-mobile-${kind}-simp_float32.tflite"
    if [ ! -f "$generated" ]; then
        echo "::error::Expected onnx2tf output missing: $generated"
        find "ppocrv5-${kind}-saved" -maxdepth 2 -type f -print
        exit 1
    fi
    mv "$generated" "paddleocr-ppocrv5-mobile-${kind}.tflite"
    rm -rf "ppocrv5-${kind}-saved" "$fixed_onnx" "$simp_onnx" "$onnx"

    # Sanity: no leaked ONNX_LAYERNORMALIZATION custom op.
    if grep -aq 'ONNX_LAYERNORMALIZATION' "paddleocr-ppocrv5-mobile-${kind}.tflite"; then
        echo "::error::${kind} contains ONNX_LAYERNORMALIZATION custom op"
        exit 1
    fi

    # Post-conversion GPU-compat surgery — rewrites RELU_0_TO_1 ops
    # into MAXIMUM(0)+MINIMUM(1) chains and downgrades TRANSPOSE_CONV
    # opcode v4 -> v3 so the LiteRT 2.1.5 on-device GPU delegate can
    # compile the model. See docs/ocr/PADDLEOCR_GPU_INVESTIGATION.md.
    #
    # tflite_gpu_fixup.py lives next to this script — both are copied
    # into /usr/local/bin by the Dockerfile, and both are typically
    # mounted from /scripts in dev workflows. Use a relative dispatch
    # that works in either layout.
    fixup_py="$(dirname "$0")/tflite_gpu_fixup.py"
    if [ ! -f "$fixup_py" ]; then
        fixup_py="/usr/local/bin/tflite_gpu_fixup.py"
    fi
    tmp_in="paddleocr-ppocrv5-mobile-${kind}.tflite"
    tmp_out="paddleocr-ppocrv5-mobile-${kind}.gpu.tflite"
    /opt/venv-onnx2tf/bin/python "$fixup_py" "$tmp_in" "$tmp_out"
    mv "$tmp_out" "$tmp_in"
done

echo "==> Stage character dictionary"
for candidate in \
    "${SRC_DIR}/ppocr/utils/dict/ppocr_v5_dict.txt" \
    "${SRC_DIR}/ppocr/utils/ppocr_keys_v5.txt" \
    "${SRC_DIR}/ppocr/utils/dict/ppocrv5_dict.txt"; do
    if [ -f "$candidate" ]; then
        cp "$candidate" "${OUT_DIR}/paddleocr-ppocrv5-mobile-dict.txt"
        echo "Used dictionary: $candidate"
        break
    fi
done
if [ ! -f "${OUT_DIR}/paddleocr-ppocrv5-mobile-dict.txt" ]; then
    echo "::error::PP-OCRv5 character dictionary not found"
    exit 1
fi
# Append space token (PP-OCRv5 mobile is trained with use_space_char=True).
if [ -n "$(tail -c1 ${OUT_DIR}/paddleocr-ppocrv5-mobile-dict.txt)" ]; then
    printf '\n' >> "${OUT_DIR}/paddleocr-ppocrv5-mobile-dict.txt"
fi
printf ' \n' >> "${OUT_DIR}/paddleocr-ppocrv5-mobile-dict.txt"

echo "==> Compute SHA-256 manifest"
cd "${OUT_DIR}"
{
    echo "# PaddleOCR PP-OCRv5 mobile artifact SHA-256 values"
    echo "# Generated by scripts/build-paddleocr-models/Dockerfile + convert.sh"
    echo ""
    for f in paddleocr-ppocrv5-mobile-det.tflite \
             paddleocr-ppocrv5-mobile-rec.tflite \
             paddleocr-ppocrv5-mobile-cls.tflite \
             paddleocr-ppocrv5-mobile-dict.txt; do
        sha=$(sha256sum "$f" | awk '{print $1}')
        size=$(stat -c%s "$f")
        echo "$f  $sha  ${size}"
    done
} | tee expected_shas.txt

echo "==> Done. Artifacts in /work/out:"
ls -la "${OUT_DIR}"
