#!/usr/bin/env python3
"""Resize the validated masked convolution graph to four independent lanes."""
import argparse
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
model = onnx.load(args.source)
for value in (*model.graph.input, *model.graph.output):
    shape = value.type.tensor_type.shape
    if shape.dim[0].dim_value != 1:
        raise ValueError(f"Unexpected batch dimension: {value.name}")
    shape.dim[0].dim_value = 4
del model.graph.value_info[:]
model = onnx.shape_inference.infer_shapes(model, strict_mode=True)
onnx.checker.check_model(model)

options = ort.SessionOptions()
options.intra_op_num_threads = 4
single = ort.InferenceSession(str(args.source), options, providers=["CPUExecutionProvider"])
batch = ort.InferenceSession(model.SerializeToString(), options, providers=["CPUExecutionProvider"])
rng = np.random.default_rng(20261114)
for lengths in ((33, 199, 598, 1024), (101, 480, 770, 0), (250, 799, 0, 0), (1024, 0, 0, 0)):
    tensors = {item.name: np.zeros([4, *item.shape[1:]], dtype=np.float32) for item in single.get_inputs()}
    for lane, length in enumerate(lengths):
        tensors["features"][lane, 0, :, :length] = rng.standard_normal((80, length), dtype=np.float32)
        for width in (1024, 512, 256, 128):
            stride = 1024 // width
            tensors[f"mask{width}"][lane, ..., :(length + stride - 1) // stride] = 1
    actual, = batch.run(None, tensors)
    assert actual.shape == (4, 1024, 10, 128)
    for lane, length in enumerate(lengths):
        if length:
            expected, = single.run(None, {key: value[lane:lane + 1] for key, value in tensors.items()})
            columns = (length + 7) // 8
            np.testing.assert_allclose(actual[lane:lane + 1, ..., :columns], expected[..., :columns], rtol=2e-5, atol=1e-4)
    print(f"Matched batch 1 for lengths {lengths}", flush=True)
onnx.save(model, args.output)
