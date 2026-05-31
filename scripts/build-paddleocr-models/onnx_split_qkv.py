#!/usr/bin/env python3
"""
Pre-conversion ONNX surgery for PaddleOCR PP-OCRv5 mobile rec.

The SVTR attention block in PP-OCRv5 mobile rec uses the textbook
fused-QKV pattern:

    qkv = self.qkv(x)                                      # [B, T, 3*H*D]
            .reshape((0, -1, 3, num_heads, head_dim))      # [B, T, 3, H, D]  ← 5D
            .transpose((2, 0, 3, 1, 4))                    # [3, B, H, T, D]  ← 5D
    q, k, v = qkv[0] * scale, qkv[1], qkv[2]

(`PaddlePaddle/PaddleOCR:ppocr/modeling/backbones/rec_svtrnet.py:196`)

LiteRT 2.1.5's GPU delegate's tensor pipeline tops out at 4D — the
``BHWC`` representation is the only first-class tensor type. The
intermediate ``[B, T, 3, H, D]`` therefore causes the GPU delegate to
emit ``RESHAPE: bad input dims size: 5`` and refuse to compile the
recognition model.

This script rewrites every fused QKV ``MatMul → Add → Reshape → Transpose``
chain into three independent 4D linear projections:

    BEFORE (5D intermediate, rejected by GPU delegate):
        MatMul[B,T,120→360] → Add(bias) → Reshape[B,T,3,8,15] → Transpose[3,B,8,T,15] → Slice ×3

    AFTER (pure 4D, GPU-compatible):
        MatMul_Q[B,T,120→120] → Add(bias_q) → Reshape[B,T,8,15] → Transpose[B,8,T,15] → q_out
        MatMul_K[B,T,120→120] → Add(bias_k) → Reshape[B,T,8,15] → Transpose[B,8,T,15] → k_out
        MatMul_V[B,T,120→120] → Add(bias_v) → Reshape[B,T,8,15] → Transpose[B,8,T,15] → v_out

The weight surgery is algebraically exact: ``W_q = W_qkv[:, 0:dim]``,
``W_k = W_qkv[:, dim:2*dim]``, ``W_v = W_qkv[:, 2*dim:3*dim]`` and the
same 3-way slice on the bias. No retraining, no precision loss
(initializer slice is bit-exact at float32; matmul reduction order
shifts by at most 1 ULP — far below any downstream CTC threshold).

The downstream consumers of Q/K/V are unchanged; they previously
received outputs of the ``transpose((2,0,3,1,4))`` indexed by 0/1/2,
and now receive Q/K/V directly with the same shape ``[B, H, T, D]``.

# Detection

The QKV chain is identified by **topology, not node name** — paddle2onnx's
auto-generated names (`p2o.MatMul.N`, `p2o.Reshape.N`) are unstable
across exports. We look for:

  1. A MatMul (or Gemm) node whose second input is an initializer
     (the fused weight) and whose output's last dim is divisible by 3.
  2. The MatMul output is consumed by an Add node (the bias).
  3. The Add output is consumed by a Reshape node whose target shape
     constant is rank 5 with shape[2] == 3 (the "3" indicates Q,K,V).

This matches all 4 SVTR attention blocks in PP-OCRv5 mobile rec
(``depth=2 × 2 blocks = 4 fires``) and nothing else.

# What this script is NOT

  * Not a generic ONNX attention optimizer (only handles the specific
    fused-QKV-into-5D-reshape pattern).
  * Not a Vatti / NMS / softmax / matmul rewriter — only structural
    re-routing of weights + nodes.
  * Not for cls or det models (they have no SVTR attention).

# Validation

Run before/after via ONNX Runtime on the same input and compare
sentence_embedding outputs. Expected cosine similarity ≥ 1 - 1e-6.

Usage:
    python onnx_split_qkv.py input.onnx output.onnx

Exit codes:
    0  - surgery applied (or model already split / no QKV chains)
    1  - schema parse / unrecoverable state
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper, shape_inference


def _initializer_map(graph) -> dict:
    return {init.name: init for init in graph.initializer}


def _value_info_map(graph) -> dict:
    """Map from tensor name to its inferred shape (list of ints/None)."""
    out = {}
    for source in (graph.input, graph.output, graph.value_info):
        for vi in source:
            dims = []
            for d in vi.type.tensor_type.shape.dim:
                if d.HasField("dim_value"):
                    dims.append(d.dim_value)
                elif d.HasField("dim_param"):
                    dims.append(d.dim_param)
                else:
                    dims.append(None)
            out[vi.name] = dims
    return out


def _consumers_of(graph, tensor_name: str) -> list:
    return [n for n in graph.node if tensor_name in n.input]


def _producer_of(graph, tensor_name: str):
    for n in graph.node:
        if tensor_name in n.output:
            return n
    return None


def _constant_value(graph, tensor_name: str, init_map: dict) -> Optional[np.ndarray]:
    """Return the constant numpy value of a tensor, whether it's an
    initializer or the output of a Constant node. Returns None if the
    value isn't statically known."""
    if tensor_name in init_map:
        return numpy_helper.to_array(init_map[tensor_name])
    producer = _producer_of(graph, tensor_name)
    if producer is not None and producer.op_type == "Constant":
        for attr in producer.attribute:
            if attr.name == "value":
                return numpy_helper.to_array(attr.t)
    return None


def _make_initializer(name: str, array: np.ndarray):
    return numpy_helper.from_array(array.astype(array.dtype), name)


def _looks_like_qkv_reshape_target(value: np.ndarray) -> Optional[tuple[int, int]]:
    """Return ``(num_heads, head_dim)`` if ``value`` is a rank-5 reshape
    target of the form ``[?, ?, 3, H, D]`` (the SVTR QKV pattern).
    Otherwise None."""
    if value is None or value.ndim != 1 or value.shape[0] != 5:
        return None
    # shape entries are int64 in ONNX
    s = value.tolist()
    # s[0] = batch (0 = "keep" in paddle reshape, or a positive int)
    # s[1] = seq_len (-1 = "infer" or positive int)
    # s[2] MUST be 3 — the Q/K/V axis
    # s[3] = num_heads (positive int)
    # s[4] = head_dim (positive int)
    if s[2] != 3:
        return None
    if not (isinstance(s[3], int) and s[3] > 0):
        return None
    if not (isinstance(s[4], int) and s[4] > 0):
        return None
    return (int(s[3]), int(s[4]))


def _walk_back_skipping_identity(graph, tensor_name: str, max_depth: int = 8):
    """Walk back through producer nodes, skipping any Identity/Cast pass-through
    nodes, and return (producer_node, intermediate_tensor_names). The
    intermediate tensor names are everything we walked through, so callers
    that need to delete the chain know which tensors to clean up."""
    intermediates = []
    name = tensor_name
    for _ in range(max_depth):
        producer = _producer_of(graph, name)
        if producer is None:
            return None, intermediates
        if producer.op_type not in ("Identity", "Cast"):
            return producer, intermediates
        intermediates.append(producer)
        name = producer.input[0]
    return None, intermediates


def find_qkv_chains(graph, init_map: dict, vi_map: dict) -> list:
    """Find every fused-QKV chain that ends in a 5D Reshape whose shape
    target is ``[..., 3, num_heads, head_dim]``. Walks backwards from the
    Reshape (which is the most distinctive op in the pattern) to find the
    bias Add and the upstream MatMul/Gemm, skipping Identity/Cast pass-
    throughs that paddle2onnx 2.1.0 inserts.

    This is more robust than walking forward from MatMul because:
      * the 5D reshape target is the ground-truth marker of fused-QKV
      * paddle2onnx inserts Identity nodes between bias Add and Reshape
        (observed 2026-05-31 on PP-OCRv5_mobile_rec.onnx)
      * forward walks can match unrelated MatMul→Add chains that
        happen to feed a reshape with a different downstream pattern.
    """
    chains = []
    for reshape_node in graph.node:
        if reshape_node.op_type != "Reshape":
            continue
        if len(reshape_node.input) < 2:
            continue
        shape_value = _constant_value(graph, reshape_node.input[1], init_map)
        match = _looks_like_qkv_reshape_target(shape_value)
        if match is None:
            continue
        num_heads, head_dim = match
        expected_out_dim = 3 * num_heads * head_dim

        # Walk back to find the bias Add, skipping Identity/Cast nodes.
        add_node, identity_chain_before_add = _walk_back_skipping_identity(
            graph, reshape_node.input[0]
        )
        if add_node is None or add_node.op_type != "Add":
            continue

        # One Add input is the MatMul output, the other is the bias
        # initializer. Identify which is which.
        matmul_input_name = None
        bias_name = None
        for input_name in add_node.input:
            if input_name in init_map:
                bias = numpy_helper.to_array(init_map[input_name])
                if bias.ndim == 1 and bias.shape[0] == expected_out_dim:
                    bias_name = input_name
            else:
                matmul_input_name = input_name
        if matmul_input_name is None or bias_name is None:
            continue
        bias_array = numpy_helper.to_array(init_map[bias_name])

        # Walk back to the MatMul/Gemm, again skipping Identity/Cast.
        matmul_node, identity_chain_before_matmul = _walk_back_skipping_identity(
            graph, matmul_input_name
        )
        if matmul_node is None or matmul_node.op_type not in ("MatMul", "Gemm"):
            continue

        # The MatMul's second input must be a weight initializer.
        weight_name = None
        x_name = None
        for input_name in matmul_node.input:
            if input_name in init_map:
                weight_name = input_name
            else:
                x_name = input_name
        if weight_name is None or x_name is None:
            continue
        W = numpy_helper.to_array(init_map[weight_name])
        if W.ndim != 2:
            continue

        # Validate weight shape matches expected_out_dim.
        is_gemm = matmul_node.op_type == "Gemm"
        if is_gemm:
            transB = 0
            for attr in matmul_node.attribute:
                if attr.name == "transB":
                    transB = int(attr.i)
                    break
            out_dim = W.shape[0] if transB == 1 else W.shape[1]
        else:
            out_dim = W.shape[1]
        if out_dim != expected_out_dim:
            continue

        chains.append({
            "matmul_node": matmul_node,
            "add_node": add_node,
            "reshape_node": reshape_node,
            "identity_chain_before_add": identity_chain_before_add,
            "identity_chain_before_matmul": identity_chain_before_matmul,
            "x_name": x_name,
            "weight_name": weight_name,
            "bias_name": bias_name,
            "weight": W,
            "bias": bias_array,
            "num_heads": num_heads,
            "head_dim": head_dim,
            "is_gemm": is_gemm,
        })
    return chains


def trace_qkv_consumers(graph, chain) -> Optional[list]:
    """Trace the ``Reshape → Transpose → 3 × {Slice → Squeeze}`` pattern
    downstream from the fused QKV reshape and return a list of 3 tensor
    names — the 4D ``[B, H, T, D]`` Q, K, V outputs that the attention
    math consumes.

    paddle2onnx 2.1.0 lowers the SVTR ``qkv[k]`` indexing as:
        ``Transpose [3,B,H,T,D]`` →
        ``Slice (axis=0, start=k, end=k+1) [1,B,H,T,D]`` →
        ``Squeeze (axis=0) [B,H,T,D]``

    We need to feed our new per-branch Q/K/V tensors INTO the Squeeze
    outputs (not the Slice outputs) because the Slice outputs are still
    5D and would mismatch our 4D replacements. We also need to capture
    the Slice + Squeeze nodes for removal — otherwise dangling Squeezes
    keep dead references to the removed Slice outputs.
    """
    reshape_out = chain["reshape_node"].output[0]
    consumers = _consumers_of(graph, reshape_out)
    if len(consumers) != 1 or consumers[0].op_type != "Transpose":
        return None
    transpose_node = consumers[0]
    perm = None
    for attr in transpose_node.attribute:
        if attr.name == "perm":
            perm = list(attr.ints)
            break
    if perm != [2, 0, 3, 1, 4]:
        return None
    transpose_out = transpose_node.output[0]

    slice_consumers = _consumers_of(graph, transpose_out)
    qkv_outs: list[Optional[str]] = [None, None, None]
    slice_nodes: list[Optional[object]] = [None, None, None]
    squeeze_nodes: list[Optional[object]] = [None, None, None]

    for slice_node in slice_consumers:
        idx = _detect_slice_index(graph, slice_node, transpose_out)
        if idx is None or not (0 <= idx < 3):
            continue
        # Slice output is 5D ``[1, B, H, T, D]`` — walk one more step to
        # the Squeeze that drops the leading 1 dim. That Squeeze output
        # is the 4D ``[B, H, T, D]`` Q/K/V we will replace.
        sq_consumers = _consumers_of(graph, slice_node.output[0])
        squeeze_node = None
        for sq in sq_consumers:
            if sq.op_type == "Squeeze":
                squeeze_node = sq
                break
        if squeeze_node is None:
            # Some onnx variants encode the drop as a Reshape; allow that too.
            for sq in sq_consumers:
                if sq.op_type == "Reshape":
                    squeeze_node = sq
                    break
        if squeeze_node is None:
            # No squeeze found — fall back to the raw Slice output, but
            # this will fail downstream because consumers expect 4D.
            qkv_outs[idx] = slice_node.output[0]
            slice_nodes[idx] = slice_node
            continue
        # Walk past any Identity/Cast pass-throughs after the Squeeze.
        # paddle2onnx 2.1.0 inserts those for every layer it traces; if
        # we leave them in, they keep dangling references to the removed
        # Squeeze output and break onnx.checker.check_model + onnx2tf.
        final_out = squeeze_node.output[0]
        post_squeeze_identities = []
        while True:
            next_consumers = _consumers_of(graph, final_out)
            if len(next_consumers) != 1:
                break
            next_node = next_consumers[0]
            if next_node.op_type not in ("Identity", "Cast"):
                break
            post_squeeze_identities.append(next_node)
            final_out = next_node.output[0]
        qkv_outs[idx] = final_out
        slice_nodes[idx] = slice_node
        squeeze_nodes[idx] = squeeze_node
        if "post_squeeze_identity_chains" not in chain:
            chain["post_squeeze_identity_chains"] = []
        chain["post_squeeze_identity_chains"].extend(post_squeeze_identities)

    if any(v is None for v in qkv_outs):
        # Could be a Split node instead — single producer with 3 outputs
        for s in slice_consumers:
            if s.op_type == "Split" and len(s.output) == 3:
                qkv_outs = list(s.output)
                slice_nodes = [s, s, s]
                squeeze_nodes = [None, None, None]
                break
    if any(v is None for v in qkv_outs):
        return None
    chain["transpose_node"] = transpose_node
    chain["slice_consumers"] = [n for n in slice_nodes if n is not None]
    chain["squeeze_consumers"] = [n for n in squeeze_nodes if n is not None]
    return qkv_outs


def _detect_slice_index(graph, node, source_name: str) -> Optional[int]:
    """If ``node`` is a Slice/Gather selecting a single index ``k`` along
    axis 0 of ``source_name``, return ``k``. Else None."""
    if node.op_type == "Gather":
        # Gather(data, indices, axis=0): indices is a scalar
        if len(node.input) < 2:
            return None
        axis = 0
        for attr in node.attribute:
            if attr.name == "axis":
                axis = int(attr.i)
                break
        if axis != 0:
            return None
        idx_val = _constant_value(graph, node.input[1], _initializer_map(graph))
        if idx_val is None:
            return None
        if idx_val.ndim == 0:
            return int(idx_val.item())
        if idx_val.ndim == 1 and idx_val.size == 1:
            return int(idx_val[0])
        return None
    if node.op_type == "Slice":
        # Slice(data, starts, ends, axes, steps) — opset >= 10
        # We need starts[0]==k, ends[0]==k+1, axes[0]==0, steps[0]==1
        init_map = _initializer_map(graph)
        if len(node.input) < 3:
            return None
        starts = _constant_value(graph, node.input[1], init_map)
        ends = _constant_value(graph, node.input[2], init_map)
        axes = _constant_value(graph, node.input[3], init_map) if len(node.input) > 3 else None
        steps = _constant_value(graph, node.input[4], init_map) if len(node.input) > 4 else None
        if starts is None or ends is None:
            return None
        if axes is not None and int(axes[0]) != 0:
            return None
        if steps is not None and int(steps[0]) != 1:
            return None
        k = int(starts[0])
        if int(ends[0]) != k + 1:
            return None
        return k
    return None


def split_chain(graph, chain, qkv_outs: list, fire_index: int) -> int:
    """Replace one fused QKV chain with three independent Q/K/V chains.
    ``qkv_outs`` are the downstream tensor names that consumers of Q/K/V
    expect to read from. We rewrite the graph so those names are produced
    by three new Q/K/V chains directly.

    Returns the number of rewrites performed (1 if successful)."""
    W = chain["weight"]
    bias = chain["bias"]
    num_heads = chain["num_heads"]
    head_dim = chain["head_dim"]
    dim = num_heads * head_dim
    x = chain["x_name"]
    prefix = f"qkv_split_{fire_index}"

    # Weight split — exact algebra. For MatMul ([in_dim, out_dim])
    # weight, the last dim is sliced into three; for Gemm with transB=1
    # ([out_dim, in_dim]), the first dim.
    if chain["is_gemm"]:
        # [3*dim, in_dim] — slice rows
        W_q = W[0:dim, :]
        W_k = W[dim:2 * dim, :]
        W_v = W[2 * dim:3 * dim, :]
    else:
        # [in_dim, 3*dim] — slice columns
        W_q = W[:, 0:dim]
        W_k = W[:, dim:2 * dim]
        W_v = W[:, 2 * dim:3 * dim]
    bias_q = bias[0:dim] if bias is not None else None
    bias_k = bias[dim:2 * dim] if bias is not None else None
    bias_v = bias[2 * dim:3 * dim] if bias is not None else None

    # Add new initializers for split weights / biases.
    new_initializers = [
        _make_initializer(f"{prefix}/W_q", W_q),
        _make_initializer(f"{prefix}/W_k", W_k),
        _make_initializer(f"{prefix}/W_v", W_v),
    ]
    if bias is not None:
        new_initializers += [
            _make_initializer(f"{prefix}/b_q", bias_q),
            _make_initializer(f"{prefix}/b_k", bias_k),
            _make_initializer(f"{prefix}/b_v", bias_v),
        ]
    # Add the new 4D reshape target [0, -1, num_heads, head_dim].
    # (Paddle convention: 0 = keep, -1 = infer.)
    reshape_target = np.array([0, -1, num_heads, head_dim], dtype=np.int64)
    reshape_target_name = f"{prefix}/reshape_4d_target"
    new_initializers.append(_make_initializer(reshape_target_name, reshape_target))

    graph.initializer.extend(new_initializers)

    # Build the three Q/K/V branches.
    new_nodes = []
    for branch, (W_name, b_name, dest_tensor_name) in enumerate([
        (f"{prefix}/W_q", f"{prefix}/b_q" if bias is not None else None, qkv_outs[0]),
        (f"{prefix}/W_k", f"{prefix}/b_k" if bias is not None else None, qkv_outs[1]),
        (f"{prefix}/W_v", f"{prefix}/b_v" if bias is not None else None, qkv_outs[2]),
    ]):
        proj_out = f"{prefix}/branch{branch}/proj_out"
        # MatMul (or Gemm) — match the original op type for shape inference.
        if chain["is_gemm"]:
            # Gemm requires alpha/beta. Use defaults (1.0/1.0). transB=1
            # since W is [out_dim, in_dim].
            if bias is not None:
                gemm = helper.make_node(
                    "Gemm",
                    inputs=[x, W_name, b_name],
                    outputs=[proj_out],
                    name=f"{prefix}/branch{branch}/Gemm",
                    transB=1,
                )
                add_out = proj_out
            else:
                gemm = helper.make_node(
                    "Gemm",
                    inputs=[x, W_name],
                    outputs=[proj_out],
                    name=f"{prefix}/branch{branch}/Gemm",
                    transB=1,
                )
                add_out = proj_out
            new_nodes.append(gemm)
        else:
            mm = helper.make_node(
                "MatMul",
                inputs=[x, W_name],
                outputs=[proj_out],
                name=f"{prefix}/branch{branch}/MatMul",
            )
            new_nodes.append(mm)
            if bias is not None:
                add_out = f"{prefix}/branch{branch}/add_out"
                add = helper.make_node(
                    "Add",
                    inputs=[proj_out, b_name],
                    outputs=[add_out],
                    name=f"{prefix}/branch{branch}/Add",
                )
                new_nodes.append(add)
            else:
                add_out = proj_out

        # Reshape to [B, T, H, D] (4D).
        reshape_out = f"{prefix}/branch{branch}/reshape_out"
        reshape = helper.make_node(
            "Reshape",
            inputs=[add_out, reshape_target_name],
            outputs=[reshape_out],
            name=f"{prefix}/branch{branch}/Reshape",
        )
        new_nodes.append(reshape)

        # Transpose to [B, H, T, D] — perm [0, 2, 1, 3].
        # The fused chain produced [3, B, H, T, D] then sliced index k
        # to get [B, H, T, D], so this matches.
        transpose = helper.make_node(
            "Transpose",
            inputs=[reshape_out],
            outputs=[dest_tensor_name],
            name=f"{prefix}/branch{branch}/Transpose",
            perm=[0, 2, 1, 3],
        )
        new_nodes.append(transpose)

    # Remove the old fused chain nodes — including the Identity/Cast
    # passthroughs that paddle2onnx inserts between MatMul → Add and
    # Add → Reshape. Forgetting them leaves dangling tensor references
    # that fail onnx.checker.check_model with "input X is not output of
    # any previous nodes".
    old_nodes_to_remove = {
        id(chain["matmul_node"]),
        id(chain["reshape_node"]),
        id(chain["transpose_node"]),
    }
    if chain.get("add_node") is not None:
        old_nodes_to_remove.add(id(chain["add_node"]))
    for n in chain.get("identity_chain_before_add", []):
        old_nodes_to_remove.add(id(n))
    for n in chain.get("identity_chain_before_matmul", []):
        old_nodes_to_remove.add(id(n))
    for sc in chain.get("slice_consumers", []):
        old_nodes_to_remove.add(id(sc))
    for sq in chain.get("squeeze_consumers", []):
        old_nodes_to_remove.add(id(sq))
    for ident in chain.get("post_squeeze_identity_chains", []):
        old_nodes_to_remove.add(id(ident))

    keep_nodes = [n for n in graph.node if id(n) not in old_nodes_to_remove]
    # Insert the new nodes near the old MatMul's position in the topo
    # order. ONNX runtime doesn't care about order (it topo-sorts at
    # load), but onnx.checker.check_model and onnx2tf both enforce
    # strict topological order — produce before consume.
    while graph.node:
        graph.node.pop()
    graph.node.extend(keep_nodes + new_nodes)
    return 1


def _topo_sort_graph(graph) -> None:
    """In-place reorder ``graph.node`` so every node appears AFTER its
    producers. Required after surgery: when we append new nodes at the
    end but they're consumed by nodes earlier in the original list, the
    graph fails topological-order checks even though it's semantically
    correct."""
    init_names = {init.name for init in graph.initializer}
    graph_input_names = {vi.name for vi in graph.input}
    available = set(init_names) | set(graph_input_names)

    nodes_pool = list(graph.node)
    ordered: list = []
    progress = True
    while nodes_pool and progress:
        progress = False
        remaining = []
        for node in nodes_pool:
            if all(inp == "" or inp in available for inp in node.input):
                ordered.append(node)
                for out in node.output:
                    available.add(out)
                progress = True
            else:
                remaining.append(node)
        nodes_pool = remaining
    if nodes_pool:
        # Append unresolvable nodes (likely a graph bug) so we at least
        # produce something onnx.save can write; downstream tools will
        # surface the cycle.
        ordered.extend(nodes_pool)

    while graph.node:
        graph.node.pop()
    graph.node.extend(ordered)


def split_qkv(input_path: str, output_path: str) -> int:
    model = onnx.load(input_path)
    try:
        model = shape_inference.infer_shapes(model)
    except Exception as exc:
        print(f"warn: shape inference failed ({exc}); continuing", file=sys.stderr)

    graph = model.graph
    init_map = _initializer_map(graph)
    vi_map = _value_info_map(graph)
    chains = find_qkv_chains(graph, init_map, vi_map)
    print(f"found {len(chains)} candidate QKV chain(s)")

    rewrites = 0
    # Iterate by re-finding chains after each mutation. Pre-computing the
    # full list once leaves stale node-object references after the first
    # mutation invalidates positions in graph.node — `id(node)` lookups
    # then miss the second chain's nodes.
    while True:
        init_map = _initializer_map(graph)
        vi_map = _value_info_map(graph)
        chains = find_qkv_chains(graph, init_map, vi_map)
        # Filter out already-rewritten chains (their tensor names contain
        # the surgery prefix). The find_ function won't return them
        # anyway after rewriting because the upstream Reshape no longer
        # exists, but be defensive.
        if not chains:
            break
        chain = chains[0]
        qkv_outs = trace_qkv_consumers(graph, chain)
        if qkv_outs is None:
            print(
                f"  chain[{rewrites}] skipped (downstream Transpose/Slice "
                f"pattern not matched); cannot proceed without losing data"
            )
            return -1
        print(
            f"  chain[{rewrites}] split: num_heads={chain['num_heads']}, "
            f"head_dim={chain['head_dim']}, "
            f"dim={chain['num_heads']*chain['head_dim']}"
        )
        split_chain(graph, chain, qkv_outs, fire_index=rewrites)
        rewrites += 1
        # Re-run shape inference between iterations so the next find_
        # call sees fresh shape data for the new branches.
        try:
            model = shape_inference.infer_shapes(model, check_type=False)
            graph = model.graph
        except Exception:
            # Non-fatal — find_qkv_chains is shape-independent
            pass

    if rewrites == 0:
        print("no QKV chains rewritten; copying input through unchanged")
        Path(output_path).write_bytes(Path(input_path).read_bytes())
        return 0

    # Final validation pass: shape inference must succeed end-to-end.
    try:
        model = shape_inference.infer_shapes(model, check_type=False)
    except Exception as exc:
        raise RuntimeError(
            f"post-surgery shape inference failed: {exc}"
        ) from exc
    _topo_sort_graph(model.graph)

    onnx.save(model, output_path)
    in_size = Path(input_path).stat().st_size
    out_size = Path(output_path).stat().st_size
    print(f"wrote {output_path}: {rewrites} chain(s) rewritten, "
          f"size {in_size} -> {out_size} bytes")
    return 0


def main(argv):
    if len(argv) != 3:
        print(f"usage: {argv[0]} input.onnx output.onnx", file=sys.stderr)
        return 1
    return split_qkv(argv[1], argv[2])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
