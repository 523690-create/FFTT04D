# HuBERT model for fractionation method 6

Method 6 ("HuBERT K-Means Units") in the Fractionate panel is **ONNX-gated**: it stays inactive
until a `hubert_base.onnx` model is present here, mirroring the GPU-optional pattern in `GpuFft.kt`.

## Generate the model

```bash
pip install torch transformers onnx onnxscript
python export_hubert_onnx.py
```

(`onnxscript` is pulled in by torch 2.6+ even though the script forces the classic exporter.)

This writes `hubert_base.onnx` (~360 MB) into this folder. Restart the desktop app and the
**HuBERT K-Means** button activates automatically. Verify headless with:

```bash
./gradlew :desktop:fractionateCli --args="D:\AndroidProjects\true_cough"
```

The `=== HuBERT K-Means Units ===` section should report `ACTIVE` instead of `INACTIVE`.

## GPU acceleration (optional, NVIDIA CUDA)

Method 6 runs on **CPU by default**. To use the GPU (e.g. an RTX card), build the desktop module
with the opt-in flag so it pulls the CUDA-12 ONNX Runtime instead of the CPU one:

```bash
./gradlew :desktop:fatJar -PuseOnnxGpu
```

`HubertKMeansUnits` then tries the CUDA execution provider and **falls back to CPU** if anything is
missing (mirrors `GpuFft`). For CUDA to actually engage you also need these DLLs on the native search
path (`desktop/native/cuda/`, beside the CUDA bits already there, or `$FFTT04D_CUDA_DIR`):

- `cudart64_12.dll`, `cufft64_11.dll` — already present (shared with the cuFFT path)
- `cublas64_12.dll`, `cublasLt64_12.dll` — from the CUDA 12 redist
- `cudnn64_9.dll` + its `cudnn_*64_9.dll` companions — from the **cuDNN 9** redist

All are public on NVIDIA's redist server (no login):
`https://developer.download.nvidia.com/compute/cuda/redist/` and
`https://developer.download.nvidia.com/compute/cudnn/redist/`. The validation CLI prints the live
provider — `execution provider: CUDA` vs `CPU` — so you can confirm the GPU is in use.

> Note: ONNX Runtime's CUDA-12 build is linked against `_12` libs; the machine's CUDA **Toolkit 13**
> (`cublas64_13` etc.) won't substitute — drop the `_12`/cuDNN-9 redist DLLs in explicitly.

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
