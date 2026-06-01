#!/usr/bin/env python3
"""
Post-conversion .tflite GPU-compatibility surgery for LiteRT 2.1.5.

The PP-OCRv5 mobile detection model emitted by onnx2tf 2.4.0 contains
two ops that the on-device LiteRT 2.1.5 GPU delegate has no kernels
for, even though they pass onnx2tf's own ``-cgdc`` check against
TFLite Runtime 2.19.1's op table:

  1. ``RELU_0_TO_1`` — added by onnx2tf's clamp canonicalisation pass
     (``MAXIMUM(0) + MINIMUM(1)`` collapsed into one node). The
     LiteRT 2.1.5 GPU delegate has no kernel for this op.
  2. ``TRANSPOSE_CONV`` opcode version 4 — added per-channel
     quantisation params (unused by float32 models). LiteRT 2.1.5's
     GPU kernel is pinned at version 3.

This tool walks the flatbuffer in-place and undoes both:

  * Every ``RELU_0_TO_1(x)`` op is rewritten as two ops:
        intermediate = MAXIMUM(x, 0.0)
        out          = MINIMUM(intermediate, 1.0)
    Mathematically identical to ``clamp(x, 0, 1)``.
  * The ``TRANSPOSE_CONV`` opcode version is forced to 3. v4 only
    adds optional per-channel quant fields; float32 models leave
    those zero, so the on-disk bytes stay valid for v3.

See ``docs/PADDLEOCR_GPU_INVESTIGATION.md`` for the full investigation
trail. The rewrites are exact (clamp = max+min) so numerical
equivalence with the original ``RELU_0_TO_1`` graph is guaranteed.

Usage:
  python tflite_gpu_fixup.py input.tflite output.tflite

Exit codes:
  0  - surgery applied (or model already GPU-compatible)
  1  - schema parse failure / unrecoverable state

Backed by ``tensorflow.lite.tools.flatbuffer_utils`` (Google's
official mutable-flatbuffer helper, used internally by the TFLite
team) so we don't ship a hand-rolled flatbuffer codec.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

from tensorflow.lite.tools import flatbuffer_utils
from tensorflow.lite.tools.flatbuffer_utils import schema_fb


def _ensure_opcode(model, builtin: int, min_version: int = 1) -> int:
    """Return the index in operator_codes of the requested builtin op,
    adding a new entry if needed."""
    for idx, op_code in enumerate(model.operatorCodes):
        # builtinCode is the modern field; deprecatedBuiltinCode is the
        # legacy 8-bit slot. Either may hold the value depending on the
        # producer (onnx2tf populates both, identically, for codes < 128).
        code = (
            op_code.builtinCode
            if op_code.builtinCode != 0
            else op_code.deprecatedBuiltinCode
        )
        if code == builtin:
            if op_code.version < min_version:
                op_code.version = min_version
            return idx
    new_code = schema_fb.OperatorCodeT()
    new_code.builtinCode = builtin
    # builtinCode >= 128 cannot be expressed in the deprecated 8-bit
    # field; the schema sets it to PLACEHOLDER_FOR_GREATER_OP_CODES (127)
    # in that case. MAXIMUM and MINIMUM are both well below 128.
    new_code.deprecatedBuiltinCode = builtin if builtin < 128 else 127
    new_code.version = min_version
    model.operatorCodes.append(new_code)
    return len(model.operatorCodes) - 1


def _add_scalar_constant_buffer(model, value: float) -> int:
    """Append a buffer containing a single FLOAT32 scalar; return its index."""
    buf = schema_fb.BufferT()
    buf.data = list(struct.pack("<f", value))
    model.buffers.append(buf)
    return len(model.buffers) - 1


def _add_scalar_constant_tensor(
    subgraph,
    buffer_idx: int,
    name: str,
) -> int:
    """Append a rank-0 (scalar) FLOAT32 tensor backed by ``buffer_idx``."""
    tensor = schema_fb.TensorT()
    tensor.shape = []  # rank-0 scalar
    tensor.type = schema_fb.TensorType.FLOAT32
    tensor.buffer = buffer_idx
    tensor.name = name
    tensor.isVariable = False
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def _clone_tensor_meta(
    subgraph,
    template_idx: int,
    name: str,
    empty_buffer_idx: int,
) -> int:
    """Append a non-constant tensor that mirrors ``template_idx``'s
    shape/dtype, backed by the empty buffer (= activation tensor)."""
    template = subgraph.tensors[template_idx]
    new_tensor = schema_fb.TensorT()
    new_tensor.shape = list(template.shape) if template.shape is not None else []
    new_tensor.type = template.type
    new_tensor.buffer = empty_buffer_idx
    new_tensor.name = name
    new_tensor.isVariable = False
    # Preserve quantization (PP-OCRv5 is float32 so usually None, but
    # copy through for safety).
    new_tensor.quantization = template.quantization
    subgraph.tensors.append(new_tensor)
    return len(subgraph.tensors) - 1


def fixup(model) -> tuple[int, int, int]:
    """Apply all GPU-compat opcode-version rewrites in place. Returns
    ``(relu_0_to_1_rewrites, transpose_conv_downgrades,
       strided_slice_downgrades)``."""

    # ── 1. Downgrade opcode versions for ops where LiteRT 2.1.5's GPU
    #      kernel registry is pinned at an older schema version than
    #      onnx2tf 2.4.0 / TFLite Converter 2.19.1 emits. Float32
    #      models leave the higher-version fields unused, so the
    #      on-disk bytes stay valid for the older version. ───────────
    #
    #   * TRANSPOSE_CONV v4 added per-channel quant params. GPU
    #     kernel pinned at v3.
    #   * STRIDED_SLICE v4 added optional ellipsis-axis-mask. GPU
    #     kernel pinned at v2.
    OPCODE_DOWNGRADES = {
        schema_fb.BuiltinOperator.TRANSPOSE_CONV: 3,
        schema_fb.BuiltinOperator.STRIDED_SLICE: 2,
    }
    transpose_conv_downgrades = 0
    strided_slice_downgrades = 0
    for op_code in model.operatorCodes:
        code = (
            op_code.builtinCode
            if op_code.builtinCode != 0
            else op_code.deprecatedBuiltinCode
        )
        if code in OPCODE_DOWNGRADES:
            target = OPCODE_DOWNGRADES[code]
            if op_code.version > target:
                op_code.version = target
                if code == schema_fb.BuiltinOperator.TRANSPOSE_CONV:
                    transpose_conv_downgrades += 1
                elif code == schema_fb.BuiltinOperator.STRIDED_SLICE:
                    strided_slice_downgrades += 1

    # ── 2. Rewrite RELU_0_TO_1 → MAXIMUM(0) + MINIMUM(1) ────────────
    relu_opcode_idx = None
    for idx, op_code in enumerate(model.operatorCodes):
        code = (
            op_code.builtinCode
            if op_code.builtinCode != 0
            else op_code.deprecatedBuiltinCode
        )
        if code == schema_fb.BuiltinOperator.RELU_0_TO_1:
            relu_opcode_idx = idx
            break

    relu_rewrites = 0
    if relu_opcode_idx is None:
        return relu_rewrites, transpose_conv_downgrades, strided_slice_downgrades

    # Ensure MAXIMUM and MINIMUM are registered.
    max_opcode_idx = _ensure_opcode(model, schema_fb.BuiltinOperator.MAXIMUM)
    min_opcode_idx = _ensure_opcode(model, schema_fb.BuiltinOperator.MINIMUM)

    # TFLite convention: buffer 0 is canonically the empty buffer.
    if not model.buffers:
        model.buffers.append(schema_fb.BufferT())
    empty_buffer_idx = 0

    for sg_idx, subgraph in enumerate(model.subgraphs):
        if not any(op.opcodeIndex == relu_opcode_idx for op in subgraph.operators):
            continue

        zero_buf_idx = _add_scalar_constant_buffer(model, 0.0)
        one_buf_idx = _add_scalar_constant_buffer(model, 1.0)
        zero_tensor_idx = _add_scalar_constant_tensor(
            subgraph, zero_buf_idx, f"sg{sg_idx}/gpu_fixup/relu_0_to_1/const_0"
        )
        one_tensor_idx = _add_scalar_constant_tensor(
            subgraph, one_buf_idx, f"sg{sg_idx}/gpu_fixup/relu_0_to_1/const_1"
        )

        new_operators = []
        local_rewrites = 0
        for op in subgraph.operators:
            if op.opcodeIndex != relu_opcode_idx:
                new_operators.append(op)
                continue
            if len(op.inputs) != 1 or len(op.outputs) != 1:
                raise RuntimeError(
                    f"RELU_0_TO_1 op with non-unary I/O: "
                    f"inputs={list(op.inputs)}, outputs={list(op.outputs)}"
                )
            data_in = int(op.inputs[0])
            data_out = int(op.outputs[0])

            intermediate_idx = _clone_tensor_meta(
                subgraph,
                template_idx=data_in,
                name=(
                    f"sg{sg_idx}/gpu_fixup/relu_0_to_1/{local_rewrites}/"
                    f"intermediate"
                ),
                empty_buffer_idx=empty_buffer_idx,
            )

            max_op = schema_fb.OperatorT()
            max_op.opcodeIndex = max_opcode_idx
            max_op.inputs = [data_in, zero_tensor_idx]
            max_op.outputs = [intermediate_idx]
            new_operators.append(max_op)

            min_op = schema_fb.OperatorT()
            min_op.opcodeIndex = min_opcode_idx
            min_op.inputs = [intermediate_idx, one_tensor_idx]
            min_op.outputs = [data_out]
            new_operators.append(min_op)

            local_rewrites += 1

        subgraph.operators = new_operators
        relu_rewrites += local_rewrites

    return relu_rewrites, transpose_conv_downgrades, strided_slice_downgrades


def main(argv):
    if len(argv) != 3:
        print(f"usage: {argv[0]} input.tflite output.tflite", file=sys.stderr)
        return 1

    input_path = Path(argv[1])
    output_path = Path(argv[2])

    model = flatbuffer_utils.read_model(str(input_path))
    relu_rewrites, transpose_conv_downgrades, strided_slice_downgrades = fixup(model)

    total_changes = (
        relu_rewrites + transpose_conv_downgrades + strided_slice_downgrades
    )
    if total_changes == 0:
        print(
            f"{input_path.name}: model already GPU-compatible, no surgery needed"
        )
        if str(output_path.resolve()) != str(input_path.resolve()):
            output_path.write_bytes(input_path.read_bytes())
        return 0

    flatbuffer_utils.write_model(model, str(output_path))
    in_size = input_path.stat().st_size
    out_size = output_path.stat().st_size
    print(
        f"{input_path.name}: relu_0_to_1_rewrites={relu_rewrites}, "
        f"transpose_conv_downgrades={transpose_conv_downgrades}, "
        f"strided_slice_downgrades={strided_slice_downgrades}, "
        f"size {in_size} -> {out_size} bytes"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
