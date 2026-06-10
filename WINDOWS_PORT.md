# Windows Desktop Port — Cough Analysis Dataset Engine

A Compose Desktop application for offline batch analysis of cough and sound recordings, generating the tensor space and training data that enables mobile auto-cough detection.

## Why Desktop

**Mobile constraints:** on-device recording + segmentation + feature extraction is feasible; but training on large public datasets (Coswara ~10k samples, ESC-50 ~2k samples) is resource-prohibitive. **Desktop offloads the analysis.**

**Pipeline:**
1. Desktop loads Cough Dataset 1, ESC-50, Coswara
2. Runs the same DSP engine as Android (pure Kotlin, no OS deps)
3. Produces `segments.jsonl` training data + feature embeddings
4. Visualizes similarity clusters, learns decision boundaries
5. Exports a trained Tier-2 model (or feature vectors for post-hoc training)
6. Model redeploys to Android for live auto-cough detection

## Data Sources

| Source | Format | Size | Metadata |
|--------|--------|------|----------|
| **Cough Dataset 1** | WebM/OGG + JSON | ~200 files | datetime, cough_detected score |
| **ESC-50** | WAV (0–49) | 2000 files | class label (0–49, e.g., cough, glass breaking) |
| **Coswara** | split tar.gz | ~10k participant recordings | age, gender, location, covid_status, health flags |

## Desktop App Structure

```
desktop/
├── build.gradle.kts           # Compose Desktop build config
├── src/main/kotlin/com/example/FFTT04M/
│   ├── cough/                 # Copy from app/ (pure DSP, no Android deps)
│   │   ├── CoughDsp.kt
│   │   ├── CoughSegmenter.kt
│   │   ├── FftFeatureExtractor.kt
│   │   ├── RidgeExtractor.kt
│   │   ├── SpeechRejector.kt
│   │   ├── CoughPhases.kt
│   │   ├── MfccExtractor.kt
│   │   ├── CoughSimilarity.kt
│   │   ├── CoughAnalyzer.kt
│   │   ├── CoughModels.kt
│   │   ├── CoughSchemaJson.kt
│   │   └── ... (all pure-Kotlin DSP)
│   ├── desktop/
│   │   ├── Main.kt            # Compose Desktop entry point
│   │   ├── DatasetLoader.kt   # Cough Dataset 1, ESC-50, Coswara parsers
│   │   ├── AudioDecoder.kt    # WAV, WebM, OGG decoding (via javax.sound or external lib)
│   │   ├── AnalysisDb.kt      # SQLite or file-based persistence of analysis results
│   │   ├── BatchProcessor.kt  # Queue management, threading for batch analysis
│   │   ├── ui/
│   │   │   ├── MainWindow.kt
│   │   │   ├── DatasetBrowser.kt
│   │   │   ├── AnalysisResults.kt
│   │   │   ├── Visualizer.kt (spectrogram, ridge plot, clusters)
│   │   │   └── ExportDialog.kt
│   │   └── models/
│   │       └── TrainingExporter.kt # Output segments.jsonl + feature tensors
│   └── FFTUtils.kt            # (shared from app/)
└── src/test/kotlin/...        # Unit tests for loaders, decoders
```

## Feature Pipeline

1. **Load** — DatasetLoader parses Cough Dataset 1, ESC-50, Coswara metadata + discovers audio files
2. **Decode** — AudioDecoder converts WebM/OGG/WAV → PCM (float[], 44.1 kHz resampled)
3. **Analyze** — CoughAnalyzer segments and extracts features (FFT, ridge, MFCC, phases, speech)
4. **Embed** — CoughSimilarity computes z-scored Euclidean/cosine embeddings
5. **Persist** — AnalysisDb stores results; TrainingExporter writes segments.jsonl + tensor files
6. **Visualize** — Compose Desktop UI shows spectrograms, ridge overlays, similarity clusters
7. **Export** — Output training data for external model training or direct inference artifacts

## MVP (Phase 1)

- ✅ Compose Desktop scaffold + main window
- Load Cough Dataset 1 metadata + list files
- AudioDecoder for WebM → PCM
- Run CoughAnalyzer on one recording, display results
- Save segments.jsonl to disk

## Phase 2

- Load ESC-50 with class labels
- Batch analysis UI (queue, progress, cancel)
- Visualizer (spectrogram, ridge plot)
- Persistence (SQLite cache)

## Phase 3

- Load Coswara (extract tar.gz, parse CSV)
- Similarity cluster visualization
- Feature tensor export for model training
- Training pipeline (logistic regression / TDNN scaffolds)

## Phase 4 (Future)

- Train Tier-2 model on desktop
- Export .onnx / TFLite artifact
- Redeploy to Android for live inference
