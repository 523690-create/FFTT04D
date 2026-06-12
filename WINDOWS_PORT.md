# Windows Desktop Port — Cough Analysis Dataset Engine

A Kotlin/JVM **Swing** desktop application for offline batch analysis of cough and sound
recordings, and for consolidating public datasets into training data the mobile app can't compute
on-device.

> **History note.** This file originally described a *planned* Compose Desktop app (AnalysisDb,
> Visualizer, TrainingExporter, an MVP→Phase-4 roadmap). That plan was **not** what got built — the
> actual app is Swing and is described below. The old plan is preserved at the bottom for context.

## Why desktop

On-device recording + segmentation + feature extraction is feasible on a phone, but analysing
large public datasets (Coswara ~tens of thousands of clips, ESC-50, COUGHVID) is resource-
prohibitive there. The desktop offloads that: it runs the **same** Tier-1 DSP (the homologous
`cough/` package) across **every CPU core**, producing the full feature set and exportable training
artifacts.

## What was actually built (Swing)

UI entry point `Main.kt` → `AnalyzerWindow` (a single `JFrame`). See
[README.md](README.md) for the per-button walkthrough. Core pieces:

```
desktop/src/main/kotlin/com/example/FFTT04M/desktop/
├── Main.kt                 # AnalyzerWindow Swing UI + handlers + directory pickers
├── DatasetLoader.kt        # Cough Dataset 1 / ESC-50 / Coswara / USB-import parsers
├── AudioDecoder.kt         # decode to PCM float + convertToWav(); resolves ffmpeg
├── AllDataBuilder.kt       # ALLDATA consolidator (all datasets → WAV + one metadata.csv)
├── ParallelCoughAnalyzer.kt# multi-core batch driver for the Tier-1 cough engine
├── MetaAnalyzer.kt         # z-scored cough feature tensor + similarity stats
├── UsbImporter.kt          # adb device list / pull / offer-ack handshake
└── cough/                  # shared Tier-1 DSP, homologous to the mobile cough/ package
    ├── CoughAnalyzer.kt  CoughSegmenter.kt  FftFeatureExtractor.kt  RidgeExtractor.kt
    ├── SpeechRejector.kt  CoughPhases.kt  MfccExtractor.kt  CoughSimilarity.kt
    ├── CoughDsp.kt  CoughModels.kt  CoughSchemaJson.kt  FFTUtils.kt
```

## Pipeline (as implemented)

1. **Load** — `DatasetLoader` parses a chosen dataset's metadata and discovers its audio files,
   or `UsbImporter` pulls them off a USB device. Directories are user-picked and remembered
   (Java `Preferences`, node `FFTT04D/dirs`); exports remember theirs under `FFTT04D/export`.
2. **Decode** — `AudioDecoder` converts WAV via `javax.sound`, and WebM/OGG/etc. via `ffmpeg`,
   to mono 44.1 kHz float PCM.
3. **Analyze** — `ParallelCoughAnalyzer` fans the Tier-1 engine across all cores: segmentation,
   FFT q-ratio/Fmax, ridge parabola, T1/T2/T3 phases, 13-band MFCC, speech-vs-cough verdict.
4. **Export** — `segments.jsonl` (canonical per-segment training rows) and a z-scored cough
   feature tensor CSV (`MetaAnalyzer`).
5. **Consolidate** — `AllDataBuilder` merges all five datasets into `ALLDATA/`: every clip → WAV,
   all metadata → one `metadata.csv` with uniform `is_cough` / `health_status` columns plus a
   lossless `metadata_json` column. Parallel, resumable, cancellable.

## Audio format support

`ffmpeg` is required for non-WAV decode/convert (WebM/OGG/MP3…). It is resolved at runtime from
`PATH`, the winget shim, or the `Gyan.FFmpeg` package directory — not bundled. `adb` is resolved
similarly from `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`LOCALAPPDATA` for USB import.

---

## Original (superseded) plan

The initial design targeted **Compose Desktop** with `AudioDecoder`, `AnalysisDb` (SQLite cache),
`BatchProcessor`, a `ui/` package (`MainWindow`, `DatasetBrowser`, `AnalysisResults`, `Visualizer`,
`ExportDialog`) and `models/TrainingExporter`, staged across an MVP and Phases 2–4 (ESC-50 →
Coswara → similarity clusters → train a Tier-2 model → export ONNX/TFLite → redeploy). The shipped
app instead uses Swing and a flat module layout, runs the full Tier-1 engine in parallel rather
than RMS/Peak, and writes `segments.jsonl` / tensor CSV / ALLDATA outputs instead of a SQLite DB.
The Tier-2 training/redeploy phase remains future work.
