# Sound Fractionation — research + build plan (FFTT04D desktop)

Status: **implemented & committed** (2026-06-17, commit `afd96ee`).
All 7 methods live in `desktop/src/main/kotlin/…/fractionation/`. Methods 1–5 and 7 are pure Kotlin;
method 6 (HuBERT) is an ONNX-gated stub. UI: "Fractionate" panel with 7 buttons + "Recommended Workflows" popup.
Raw research from Gemini 2.5 Flash is preserved verbatim in `research/`:
- `research/AUD_paradigm_part1.md` — MFA verdict + AUD/self-supervised method comparison
- `research/AUD_paradigm_part2.md` — pipeline, public datasets, Riva/VoxPopuli verdict, minimal first step
- `research/fractionation_methods_raw.md` — the 7 segmentation methods (full detail; source for §1 below)

## Why this work
The current analysis featurizes each clip as ONE 14-dim vector → k-means codebook
(`AcousticUnitDiscovery.kt`, whole-clip). Results are suboptimal. The plan: **fractionate** each
recording into sub-units ("cough phonemes"), featurize EACH sub-unit, cluster into a codebook, and
represent a recording as a TOKEN SEQUENCE. **MFA is the wrong tool** (needs transcripts + pronunciation
dict + speech acoustic models). The right paradigm is unsupervised acoustic-unit discovery; the first
step is good temporal **fractionation**, which is what this feature provides — multiple methods, each a
button, so they can be compared.

The desktop already has a baseline: `cough/CoughSegmenter.kt` (+ `CoughPhases.kt`). The new methods sit
alongside it behind a common interface.

---

## §1 — Methods (each becomes a button). Full algorithms in `research/fractionation_methods_raw.md`.

| # | Button | Principle | JVM | Best for |
|---|--------|-----------|-----|----------|
| 1 | **Energy Onset** | RMS-envelope rise over adaptive threshold + hysteresis | pure Kotlin | explosive cough bursts, typing, horn |
| 2 | **Spectral Flux Onset** | half-wave-rectified frame-to-frame magnitude increase (opt. HF-only variant) | pure Kotlin | plosives/fricatives, timbral shifts |
| 3 | **Syllable Nucleus** | sonority/loudness-dip peaks (Mermelstein-style) via RMS+centroid+ZCR | pure Kotlin | voiced speech, voiced cough phase |
| 4 | **Cough Phases** | rule/state-machine: burst → intermediate → voiced (RMS+SC+ZCR+pitch) | pure Kotlin (needs YIN pitch) | detailed cough dynamics |
| 5 | **Feature Changepoint** | CUSUM on a frame feature stream (MFCC+energy+ZCR) | pure Kotlin | general; refine coarse boundaries |
| 6 | **HuBERT K-Means Units** | HuBERT frame embeddings → k-means → boundary = cluster change | **needs ONNX Runtime** | high-precision phoneme codebook |
| 7 | **Feature Cluster Boundaries** | pure-Kotlin AUD approx: MFCC+Δ → (PCA) → k-means → boundary = cluster change | pure Kotlin | data-driven units without ONNX |

Defaults (8–16 kHz mono): 25 ms frame / 10 ms hop throughout. Per-method params/thresholds are in the
raw doc — expose the important ones in the UI later; hardcode sane defaults first.

**Build order recommendation:** 1, 2, 5, 7 first (pure Kotlin, high value, reuse existing FFT/MFCC),
then 3 and 4 (need ZCR + YIN pitch), then 6 last (ONNX dependency — gate behind a model-present check,
mirror the GPU-optional pattern in `GpuFft.kt`).

## §2 — Recommended Workflows (populate the "Recommended Workflows" popup)
(Workflow 1 is from Gemini; 2–5 synthesized from the method set — refine when implementing.)

1. **Cough-Only Fast Triage** — `Energy Onset` (sensitivity ~2.0, min-interval 200 ms) → `Cough Phases`
   within each hit. Fast pass to find loud transients, then confirm true coughs by their burst→voiced
   structure. Use for quick yes/there's-a-cough triage.
2. **Build a Phoneme Codebook (pure-Kotlin)** — `Feature Cluster Boundaries` (or `Feature Changepoint`
   for boundaries) across the whole external DB → per-segment 14-dim vector → feed the EXISTING
   `AcousticUnitDiscovery` k-means (now at sub-segment granularity) → export codebook. The low-risk
   first end-to-end path; reuses current code.
3. **Build a Phoneme Codebook (high-precision)** — `HuBERT K-Means Units` for boundaries + embeddings →
   k-means codebook. Best quality; needs ONNX. Use once #2 is validated and ONNX is wired.
4. **Separate Speech vs Cough vs Ambient** — `Spectral Flux Onset` to cut events → per-event features →
   classify each via `RespiratoryTaxonomy` group (RESPIRATORY/SPEECH/NOISE). Produces the typing/horn
   = NOISE tokens and the speech tokens distinct from cough tokens.
5. **High-Precision Research Segmentation** — `Spectral Flux Onset` (coarse) → `Feature Changepoint`
   (refine boundaries) → `Cough Phases` / `Syllable Nucleus` (label sub-structure). For careful manual
   dataset construction.

## §3 — Desktop implementation plan
**New package** `desktop/.../fractionation/`:
- `Fractionator.kt` — common interface:
  ```kotlin
  data class Segment(val startMs: Int, val endMs: Int, val label: String? = null, val clusterId: Int? = null)
  interface Fractionator { val name: String; fun fractionate(x: FloatArray, sr: Int): List<Segment> }
  ```
- One file per method implementing it: `EnergyOnset.kt`, `SpectralFluxOnset.kt`, `SyllableNucleus.kt`,
  `CoughPhasesFractionator.kt` (wrap existing `CoughPhases.kt`), `FeatureChangepoint.kt`,
  `FeatureClusterBoundaries.kt`, `HubertKMeansUnits.kt` (ONNX-gated).
- Reuse existing DSP: `cough/FFTUtils.kt`, `cough/MfccExtractor.kt`, `cough/FftFeatureExtractor.kt`,
  `WholeClipFeatures.kt`. Add a small `Zcr`/`SpectralCentroid` helper + a `Yin` pitch detector for 3/4.

**UI (`Main.kt`):** there's a `createButton(text, action): JButton` helper (~line 1064) and buttons are
laid out in panels. Add a **"Fractionate ▾"** group with one button per method; each runs on the
loaded/selected audio on a background thread (mirror `onGenerateImages`/`isolateButton` patterns:
set button text to "Cancel …", run in a worker, write to `analysisResultsArea`, restore on done).
Render segments by drawing boundaries over the existing spectrogram (`SpectrogramRenderer.kt`) and/or
listing `(start,end,label)` in the results area. Add **"Recommended Workflows"** button → a
`JOptionPane`/`JDialog` showing §2 text.

**Output:** write segments to a `segments.jsonl`-style export (the project already exports
`segments.jsonl`; extend it with a `method` field) so sub-segments feed `AcousticUnitDiscovery`.

**Validation:** test on a known multi-cough clip + a speech clip + a typing/ambient clip; eyeball
boundaries on the spectrogram; confirm method comparison differs as the table predicts.

## §4 — External datasets to seed the phoneme library (from research part 2)
COUGHVID, Coswara (already mass-unpacked here), ICBHI 2017 respiratory, FluSense, FSD50K, ESC-50,
AudioSet. Grow incrementally / actively. The Riva VoxPopuli tutorial is **not directly useful** (German
ASR data prep); only its manifest workflow + the unlabeled-pretraining concept transfer.

## §5 — Related app-side TODOs (carried from 2026-06-17 session; see FFTT04M/SESSION_NOTES_2026-06-17.md)
- Per-file delete-as-acknowledged for USB transfer (desktop `UsbImporter.kt`: pull file-by-file +
  incremental received manifest; phone watches + deletes per entry).
- Empty 2-byte `.json` metadata sidecars bug (`GalleryTransfer.writeMetaSidecar`/`metaJsonFor`).
