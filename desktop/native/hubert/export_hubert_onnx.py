#!/usr/bin/env python3
"""
Export facebook/hubert-base-ls960 to hubert_base.onnx for fractionation method 6
(HubertKMeansUnits.kt). Produces a model that takes a 16 kHz mono waveform shaped
[batch, samples] and emits per-frame hidden states [batch, frames, 768].

Usage:
    pip install torch transformers onnx
    python export_hubert_onnx.py            # writes hubert_base.onnx next to this script

The Kotlin side auto-detects the input/output names from the session, so the exact names
below are not load-bearing — but [batch, samples] in / [batch, frames, hidden] out is.
"""
import os
import torch
from transformers import HubertModel

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hubert_base.onnx")
MODEL_ID = "facebook/hubert-base-ls960"


def main() -> None:
    print(f"Loading {MODEL_ID} …")
    model = HubertModel.from_pretrained(MODEL_ID)
    model.eval()

    # 1 second of dummy 16 kHz audio. Dynamic axes make the real sample/frame counts free.
    dummy = torch.randn(1, 16000, dtype=torch.float32)

    print(f"Exporting → {OUT}")
    torch.onnx.export(
        model,
        (dummy,),
        OUT,
        input_names=["input_values"],
        output_names=["last_hidden_state"],
        dynamic_axes={
            "input_values": {0: "batch", 1: "samples"},
            "last_hidden_state": {0: "batch", 1: "frames"},
        },
        opset_version=14,
        do_constant_folding=True,
    )
    size_mb = os.path.getsize(OUT) / (1024 * 1024)
    print(f"Done — {OUT} ({size_mb:.0f} MB). The fractionation 'HuBERT K-Means' button is now live.")


if __name__ == "__main__":
    main()
