# FFTT04M Ecosystem — Institutional Memory & Algorithm Homology

**Version**: 0.1.0  
**Purpose**: Single source of truth for DSP algorithms, design principles, and interop across Android, Windows, and research platforms.

## Core Mission

Real-time and offline audio analysis: spectral decomposition, cough detection, medical diagnostics.

## Three Projects (Planned Split)

```
FFTT04M-Android/
  ├─ app/                          # Kotlin Android
  ├─ shared/                        # Shared code (WavReader, FFTUtils)
  └─ README.md                      # Android-specific docs

FFTT04M-Desktop/
  ├─ desktop/                       # Kotlin Swing UI
  ├─ research/cough/               # Python ML pipeline
  └─ README.md

FFTT04M-Research/
  ├─ notebooks/                     # Jupyter analysis
  ├─ datasets/                      # Data prep scripts
  └─ models/                        # Tier-2/3 training
```

## Shared DSP Algorithms (Algorithm Homology)

### Tier 1: Classical Spectral (On-Device, All Platforms)

**Location**: `shared/cough/` (Kotlin) + `research/cough/features.py` (Python)

- **FFT Features**: Q-ratio E(60–600)/E(600–6k), Fmax (parabolic sub-bin interpolation)
- **Ridge Extraction**: 300–1000 Hz band, STFT, parabola fit f=at²+bt+c (curvature, slope, intercept)
- **Segmentation**: RMS envelope, dynamic threshold (median × factor), merge gaps, duration gate
- **Phase Split**: T1/T2/T3 + expulsive window (150–200 ms)
- **MFCC**: Mel filterbank → log → DCT-II, 13 coefficients, mean±std summary
- **Speech Rejection**: Spectral flatness + autocorrelation pitch strength

**Invariants**:
- All frequencies in Hz, times in seconds, amplitudes normalized to [-1,1]
- Samplerate: 44.1 kHz canonical; resample on input if needed
- Feature z-scoring: per-dimension (curvature / std) for comparability

### Tier 2: MFCC + TDNN (GPU-Accelerated, Desktop/Cloud)

**Location**: `research/cough/models.py` (PyTorch)

- 13-dim MFCC + 4-layer temporal DNN (256 hidden)
- Class-weighted CE loss (4-class: bronchitis, pneumonia, croup, habit-cough)
- Validation AUROC 0.80–0.92

### Tier 3: Transformer (EAT — Explainable Attention)

**Location**: Future; scaffolded in `research/cough/models.py`

- Mel-patch input, self-attention over time
- AUROC 0.85–0.97

## Data Schema (Unified Across Platforms)

**File Format**: `segments.jsonl` (one event per line, training-ready)

```json
{
  "recording_id": "uuid",
  "segment_id": "uuid_seg_0000",
  "start_sample": 1024,
  "end_sample": 65536,
  "sample_rate_hz": 44100,
  "is_cough": true,
  "phases": {
    "t1_s": 0.1,
    "t2_s": 0.05,
    "t3_expulsive_s": 0.2,
    "expulsive_start_sample": 2048,
    "expulsive_end_sample": 11000
  },
  "qc": {
    "speech_likelihood": 0.1,
    "spectral_flatness": 0.85,
    "pitch_strength": 0.05
  },
  "features": {
    "fft": {
      "duration_s": 0.35,
      "q_ratio": 0.42,
      "fmax_hz": 450.5
    },
    "ridge": {
      "valid": true,
      "curvature_a": 125.3,
      "slope_b": -50.2,
      "intercept_c": 600.0,
      "center_freq_hz": 550.0,
      "frame_count": 35,
      "energy": 0.78,
      "bandwidth_hz": 180.0,
      "r_squared": 0.92
    },
    "mfcc": {
      "num_coeffs": 13,
      "frame_count": 35,
      "mean": [-200.5, ...],
      "std": [45.2, ...]
    }
  },
  "labels": {
    "diagnosis_4class": "bronchitis",
    "source": "manual"
  }
}
```

## Platform-Specific Adaptations

### Android (`FFTT04M-Android`)
- **Use case**: Real-time capture, on-device Tier-1 analysis, gallery mgmt
- **Languages**: Kotlin + Java
- **Display**: Spectrogram (FFTHeatMapView), ridge overlay (CoughVisualizerView)
- **Storage**: Public `/sdcard/Documents/FFTT04M` (survives uninstall)
- **API**: 32+ (Pixel 3a+)

### Desktop (`FFTT04M-Desktop`)
- **Use case**: Batch dataset analysis, Tier-2 training prep, visualization
- **Languages**: Kotlin (UI) + Python (ML)
- **UI**: Swing (load datasets, run analysis, export JSON)
- **Compute**: CPU-based (GPU optional via PyTorch)

### Research (`FFTT04M-Research`)
- **Datasets**: COUGHVID, ICBHI, Coswara, ESC-50 (public)
- **ML**: PyTorch scaffolds (TDNN4, EAT4), training loops
- **Output**: Trained `.onnx` models → re-import to Android

## Consistency Rules

1. **DSP Algorithm Changes**: Update both Kotlin and Python versions simultaneously (feature parity)
2. **Schema Changes**: Update `segments.jsonl` schema, regenerate all training data
3. **Samplerate**: Always 44.1 kHz as canonical; document any exceptions
4. **Feature Vectors**: Z-score standardization mandatory before clustering/distance metrics
5. **Test Coverage**: Every new DSP component has unit tests on the JVM (Kotlin) + pytest (Python)

## Known Limitations & TODOs

- [ ] Coswara tar.gz extraction in desktop (stubs ready)
- [ ] Tier-2 model training pipeline (scaffolded)
- [ ] Android TFLite inference integration
- [ ] Real-time latency benchmarks on Tier-2
- [ ] Multi-language cough detection (currently mono)

## References

- **BMC Pulmonary Medicine**: Tier-1 classical features (Q-ratio, Fmax)
- **ICBHI 2017 Challenge**: 4-class taxonomy, AUROC baselines
- **COUGHVID**: Public cough database (~20k samples)
- **EAT4 Paper**: Explainable attention for respiratory signals

---

**Last Updated**: 2026-06-10  
**Maintainers**: Audio analysis research team
