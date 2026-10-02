# PaddleOCR conversion dependency review

Reviewed 2026-10-02. Docker and the manual GitHub workflow use the same
`requirements-paddle2onnx.txt` and `requirements-onnx2tf.txt` files. The two
converter versions remain explicit in Docker and configurable in the workflow.
Both environments run `pip check` after installation.

## Applied fixes

The Paddle-to-ONNX environment now uses ONNX 1.17.0, protobuf 5.29.6 and setuptools 83.0.0.
These address the reviewed protobuf recursion and setuptools file-collision
advisories. ONNX 1.17.0 also fixes GHSA-h36j-8vv3-cj52 and is the newest version
allowed by paddle2onnx 2.1.0; newer ONNX advisories remain blocked below.

Polygraphy 0.53.6 and ONNX GraphSurgeon 0.5.8 are installed explicitly. Without
GraphSurgeon, Polygraphy attempted a pip install during constant folding and
replaced NumPy inside the running process, causing import failures and skipping
constant folding. The pinned helper completes folding while retaining NumPy
1.26.4. Docker also copies `onnx_split_qkv.py`, which `convert.sh` requires.

Community release dates checked against PyPI metadata:

| Package | Version | Published |
|---|---|---|
| setuptools | 83.0.0 | 2026-07-04 |
| Polygraphy | 0.53.6 | 2026-09-22 |
| ONNX GraphSurgeon | 0.5.8 | 2025-04-10 |
| ONNX | 1.17.0 | 2024-10-01 |

## Remaining blocked security updates

The ONNX advisories need an upgrade through 1.22.0, but the published converters
reject that version:

- [paddle2onnx 2.1.0 metadata](https://pypi.org/pypi/paddle2onnx/2.1.0/json)
  requires `onnx>=1.16.1,<=1.17.0`.
- [onnx2tf 2.4.0 metadata](https://pypi.org/pypi/onnx2tf/2.4.0/json) pins
  `onnx==1.20.1`, `protobuf==4.25.5`, `ml-dtypes==0.5.1` and `pytest==9.0.2`.
- [onnx2tf 2.6.9 metadata](https://pypi.org/pypi/onnx2tf/2.6.9/json), the current
  newer converter release, still pins ONNX 1.20.1. A converter bump alone does
  not remove the ONNX findings.

Linux pip resolver checks confirmed both conflicts with ONNX 1.22.0. Protobuf
in the ONNX-to-TFLite environment also remains affected under the upstream pin.
The transitive scan additionally found pytest GHSA-6w46-j5rx-g56g, fixed in
9.0.3; onnx2tf's exact 9.0.2 requirement blocks that update too.
Resolving these requires a maintained converter backport or migration, followed
by complete det/rec/cls conversion and output/inference validation. Do not use
`--no-deps` to silently bypass these contracts. These are build-tool findings;
ONNX and Python converter packages do not ship in the Android runtime.

## Validation and follow-up

On Linux/Python 3.12, the updated Paddle environment installed successfully,
passed `pip check` before and after conversion, exported the official
detection, recognition and orientation models at opset 17, completed
constant folding, and passed the ONNX checker. NumPy remained 1.26.4.
The shared ONNX-to-TFLite requirements passed pip's resolver check.

The Docker daemon was unavailable; no Docker image or complete TFLite model
bundle was built. Direct requirements are pinned, but transitive requirements,
base images and upstream model downloads are not fully locked. CI still has
its own conversion loop and omits the QKV rewrite performed by `convert.sh`;
sharing the full conversion driver and comparing all outputs is separate work.
