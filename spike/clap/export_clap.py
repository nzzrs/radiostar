#!/usr/bin/env python3
"""Export the CLAP HTSAT-tiny audio tower to ONNX.

Input is a raw mono waveform, 10 s at 48 kHz: ``waveform [1, 480000]`` ->
``embedding [1, 512]``, L2 normalised. The mel front-end lives inside the graph,
so device and reference share one arithmetic path and cannot drift.

    python spike/clap/export_clap.py --out spike/clap/out

Without ``--checkpoint`` the laion_clap default is downloaded
(``630k-audioset-best.pt``). For the music/AudioSet/ESC checkpoint, pass it
explicitly and check its own licence file: the HF weights are Apache-2.0, the
Zenodo checkpoint is a separate artefact.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as functional
from torch import nn

SAMPLE_RATE = 48_000
WINDOW_SAMPLES = 480_000  # 10 s at 48 kHz, the laion_clap clip length


class AudioEmbedding(nn.Module):
    """Waveform -> pooled audio embedding -> projection -> unit vector."""

    def __init__(self, core: nn.Module) -> None:
        super().__init__()
        self.core = core

    def forward(self, waveform: torch.Tensor) -> torch.Tensor:
        payload = {
            "waveform": waveform,
            "longer": torch.tensor([False], dtype=torch.bool),
        }
        embedding = self.core.encode_audio(payload, device=waveform.device)["embedding"]
        embedding = self.core.audio_projection(embedding)
        return functional.normalize(embedding, dim=-1)


def build(checkpoint: Path | None):
    import laion_clap

    module = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-tiny")
    module.load_ckpt(ckpt=str(checkpoint) if checkpoint else None)
    module.eval()
    return module


def export(wrapper: nn.Module, out_path: Path) -> None:
    sample = torch.zeros(1, WINDOW_SAMPLES, dtype=torch.float32)
    with torch.no_grad():
        reference = wrapper(sample)
    torch.onnx.export(
        wrapper,
        sample,
        str(out_path),
        opset_version=17,
        do_constant_folding=True,
        input_names=["waveform"],
        output_names=["embedding"],
        dynamic_axes=None,
        dynamo=False,
    )
    print(f"wrote {out_path} ({out_path.stat().st_size / 1e6:.1f} MB)")
    print(f"reference head: {reference[0, :4].tolist()}")


def parity(wrapper: nn.Module, onnx_path: Path) -> float:
    import onnxruntime as ort

    rng = np.random.default_rng(0)
    waveform = (0.1 * rng.standard_normal((1, WINDOW_SAMPLES))).astype(np.float32)
    with torch.no_grad():
        expected = wrapper(torch.from_numpy(waveform)).numpy()

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    actual = session.run(None, {"waveform": waveform})[0]

    cosine = float(
        np.sum(expected * actual) / (np.linalg.norm(expected) * np.linalg.norm(actual))
    )
    max_error = float(np.max(np.abs(expected - actual)))
    print(f"parity: cosine {cosine:.6f}, max abs error {max_error:.2e}")
    return cosine


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=Path("spike/clap/out"))
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    module = build(args.checkpoint)
    wrapper = AudioEmbedding(module.model).eval()

    target = args.out / "clap_tiny.onnx"
    export(wrapper, target)
    return 0 if parity(wrapper, target) >= 0.999 else 1


if __name__ == "__main__":
    raise SystemExit(main())
