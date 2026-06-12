# FFTT04M Three-Project Architecture

## Overview
FFTT04M has been split into three independent projects with shared Tier-1 DSP algorithms:

### FFTT04M (High-API, API 32+)
- **Branch**: blue_sky
- **Scope**: Modern Android with full cough analysis (Tier-1/2/3)
- **Icon**: Original cyan/magenta roundel
- Features: FFT, MFCC, ridge parabola, phases, speech rejection, on-device cough detection

### FFTT04L (Legacy, API 23+)  
- **Branch**: main
- **Scope**: Maximum compatibility (Nexus 7+)
- **Icon**: Square-in-square (magenta outer, cyan inner)
- Features: Core FFT, RMS/Peak, Bluetooth, gallery

### FFTT04D (Desktop Analyzer, Windows)
- **Branch**: port_windows
- **Scope**: Batch analysis, dataset consolidation, and (future) model training
- **UI**: Java Swing (`MainKt` → `AnalyzerWindow`); see [README.md](README.md)
- **Launcher**: CoughAnalyzer.bat → desktop shortcut
- Features: dataset loading (Cough Dataset 1 / ESC-50 / Coswara), USB device import, full Tier-1
  cough engine across all cores (`ParallelCoughAnalyzer`), `segments.jsonl` + feature-tensor
  export, and the ALLDATA consolidator (`AllDataBuilder`: all datasets → WAV + merged `metadata.csv`)

### Shared Algorithm Homology
All three implement identical Tier-1 DSP:
- FFT (Cooley-Tukey)
- Segmentation (energy envelope)
- Features (Q-ratio, Fmax)
- Ridge fitting (300–1000 Hz parabola)
- Speech rejection (spectral flatness + pitch)
- Phases (T1/T2/T3 + expulsive)
- MFCC (Mel → log → DCT)
- Similarity (z-scored Euclidean)

### Data Format
Canonical `segments.jsonl` (JSON per line):
```json
{
  "segment_idx": 0,
  "time_start_s": 0.0,
  "time_end_s": 1.0,
  "fft": {"q_ratio": 1.5, "fmax_hz": 800},
  "ridge": {"f0_hz": 450},
  "phases": {"t1_s": 0.1, "t2_s": 0.2, "t3_expulsive_s": 0.3},
  "mfcc": {"mean_coeffs": [...]},
  "speech": {"verdict": "cough", "score": 0.95}
}
```

### Consistency Rules
1. Bug fixes main → port to blue_sky
2. New features stay in blue_sky (don't backport)
3. Identical DSP across all 3 projects
4. 44.1 kHz canonical sample rate
5. Z-score normalization mandatory

### Status / limitations (desktop)
- Coswara tar extraction: **working** — split `.tar.gz` parts are concatenated and extracted
  (streamed in `AllDataBuilder`, on-disk in `DatasetLoader`).
- WebM/OGG: **decoded via ffmpeg** (resolved at runtime; not bundled). Earlier "blocked" note is
  obsolete.
- Desktop DSP: **full Tier-1 engine** runs in parallel across all cores (not RMS/Peak).
- Tier-2 model training/redeploy (ONNX/TFLite back to Android) remains future work.
