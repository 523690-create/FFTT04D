# HuBERT model for fractionation method 6

Method 6 ("HuBERT K-Means Units") in the Fractionate panel is **ONNX-gated**: it stays inactive
until a `hubert_base.onnx` model is present here, mirroring the GPU-optional pattern in `GpuFft.kt`.

## Generate the model

```bash
pip install torch transformers onnx
python export_hubert_onnx.py
```

This writes `hubert_base.onnx` (~360 MB) into this folder. Restart the desktop app and the
**HuBERT K-Means** button activates automatically. Verify headless with:

```bash
./gradlew :desktop:fractionateCli --args="D:\AndroidProjects\true_cough"
```

The `=== HuBERT K-Means Units ===` section should report `ACTIVE` instead of `INACTIVE`.

## How discovery works

`HubertKMeansUnits.locateModel()` looks, in order:
1. `$FFTT04D_HUBERT_MODEL` (full path to a `.onnx`), if set;
2. `native/hubert/hubert_base.onnx` and `desktop/native/hubert/hubert_base.onnx`, walking up from
   both the working dir and the jar location (same search strategy as the CUDA DLLs).

So the model also works if dropped beside the fat jar or pointed at via the env var.

## Model contract

- **Input:** mono 16 kHz waveform, float32, shape `[1, samples]`. The app resamples to 16 kHz.
- **Output:** per-frame hidden states, shape `[1, frames, 768]` (HuBERT base, ~50 fps / 20 ms stride).

Input/output *names* are auto-detected from the session, so a differently-named export works as long
as the shapes match. The app then k-means-clusters the frames and cuts a boundary wherever the
cluster id changes.

> The whole `desktop/native/` tree is gitignored (it holds large binaries like the CUDA DLLs and
> this `.onnx`). This script and README are force-added as the only tracked files in here, so the
> model itself never lands in git — generate it locally with the command above.
