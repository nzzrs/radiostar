#!/usr/bin/env python3
"""Quantize the exported CLAP audio tower and check parity.

NOT EXECUTED in the development container (no torch/onnxruntime). Recipe:

    python spike/clap/quantize.py \
        --input spike/clap/out/clap_tiny.onnx \
        --output spike/clap/out/clap_tiny_int8.onnx \
        --parity-dir spike/clap/out/parity

`reduce_range` is False on purpose. The flag exists to avoid `VPMADDUBSW` int16
saturation on x86 AVX2; ARM64 has no such instruction, and the 7-bit weights
cost accuracy for nothing. Build a separate x86 artefact if the server needs it.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from onnxruntime.quantization import QuantType, quantize_dynamic


def quantize(source: Path, target: Path) -> None:
    quantize_dynamic(
        str(source),
        str(target),
        weight_type=QuantType.QInt8,
        per_channel=True,
        reduce_range=False,
    )
    print(f"wrote {target} ({target.stat().st_size / 1e6:.1f} MB)")


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    denominator = float(np.linalg.norm(a) * np.linalg.norm(b))
    return float(np.sum(a * b) / denominator) if denominator else 0.0


def parity(fp32: Path, int8: Path, directory: Path) -> int:
    import onnxruntime as ort

    files = sorted(directory.glob("*.npy"))
    if not files:
        print("no .npy mel fixtures; skipping parity")
        return 0

    fp32_session = ort.InferenceSession(str(fp32), providers=["CPUExecutionProvider"])
    int8_session = ort.InferenceSession(str(int8), providers=["CPUExecutionProvider"])

    scores = []
    for path in files:
        mel = np.load(path)
        expected = fp32_session.run(None, {"mel": mel})[0]
        actual = int8_session.run(None, {"mel": mel})[0]
        scores.append(cosine(expected[0], actual[0]))

    values = np.array(scores)
    print(f"fixtures      : {len(values)}")
    print(f"cosine mean   : {values.mean():.6f}")
    print(f"cosine min    : {values.min():.6f}")

    ok = values.mean() >= 0.98 and values.min() >= 0.95
    print(f"verdict       : {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--parity-dir", type=Path, default=None)
    args = parser.parse_args()

    quantize(args.input, args.output)
    if args.parity_dir is not None:
        return parity(args.input, args.output, args.parity_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
