# Build PaddleOCR PP-OCRv5 mobile → ONNX → TFLite (GPU-compatible)
#
# This Dockerfile captures the exact same pinned conversion toolchain
# the GitHub Actions `Build PaddleOCR PP-OCRv5 mobile .tflite models`
# workflow uses, so local dry-runs and CI runs are byte-for-byte
# equivalent. The image is reproducible: every dependency is pinned and
# soak-aged (≥7 days at workflow-author time per project rules).
#
# Why two virtualenvs in one image: paddle2onnx 2.1.0 and onnx2tf 2.4.0
# intentionally use incompatible onnx versions. Co-locating the venvs
# keeps the conversion chain reproducible without juggling host Python.
#
# Usage from repo root (PowerShell):
#   docker build -t mindlayer-paddleocr-convert ./scripts/build-paddleocr-models
#   docker run --rm -v ${PWD}/build/paddle-convert:/work/out `
#     -e PADDLEOCR_REF=v3.5.0 mindlayer-paddleocr-convert
#
# Output goes to ./build/paddle-convert/{det,rec,cls}.tflite + dict.txt.
#
# This Dockerfile is the **source of truth** for the conversion. The CI
# workflow at .github/workflows/build-paddleocr-models.yml can either
# build this image or run the same pip pins inline — keep them in sync.

FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates tar \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# Two pinned venvs (matches workflow lines 100–138). paddle2onnx 2.1.0
# requires onnx 1.16.2; onnx2tf 2.4.0 needs onnx 1.20.1 + tensorflow
# 2.19.1 + tf_keras 2.19.0 + onnxsim 0.6.3.
RUN python -m venv /opt/venv-paddle2onnx \
    && /opt/venv-paddle2onnx/bin/python -m pip install --upgrade pip \
    && /opt/venv-paddle2onnx/bin/python -m pip install \
        "setuptools==80.9.0" \
        "paddlepaddle==3.0.0" \
        "paddle2onnx==2.1.0" \
        "onnx==1.16.2" \
        "onnxruntime==1.18.1" \
        "protobuf==4.25.4" \
        "numpy==1.26.4"

RUN python -m venv /opt/venv-onnx2tf \
    && /opt/venv-onnx2tf/bin/python -m pip install --upgrade pip \
    && /opt/venv-onnx2tf/bin/python -m pip install \
        "onnx2tf==2.4.0" \
        "tensorflow==2.19.1" \
        "tf_keras==2.19.0" \
        "onnxsim==0.6.3" \
        "onnx==1.20.1" \
        "protobuf==4.25.5" \
        "numpy==1.26.4"

COPY convert.sh /usr/local/bin/convert.sh
RUN chmod +x /usr/local/bin/convert.sh

ENV PADDLEOCR_REF=v3.5.0
VOLUME ["/work/out"]

ENTRYPOINT ["/usr/local/bin/convert.sh"]
