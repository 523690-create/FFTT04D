# HANDOFF — FFTT04D desktop (for Claude Code)

Read this first. It captures desktop-specific context that isn't obvious from the code.
Date: 2026-06-12.

> **This is the desktop repo.** Earlier versions of this file were a copy of the *mobile* app's
> handoff (Nexus 7, APK signing, EQ labels, etc.) — none of that applies here. The authoritative
> desktop docs are [README.md](README.md), [WINDOWS_PORT.md](WINDOWS_PORT.md),
> [ARCHITECTURE.md](ARCHITECTURE.md), and this file.

## What this app is
A Kotlin/JVM **Swing** desktop app (`desktop/` module) that runs the same Tier-1 cough DSP as the
phone, but across all CPU cores, for offline batch analysis and dataset prep. UI = `Main.kt` →
`AnalyzerWindow`. Launch with `CoughAnalyzer.bat` (runs `desktop\build\libs\CoughAnalyzer.jar`).
Build the jar with `gradlew.bat :desktop:fatJar`.

## Build / run conventions
- `gradlew.bat :desktop:compileKotlin` to typecheck; `:desktop:fatJar` to produce the runnable jar;
  `:desktop:run` for dev. **Rebuild the fat jar after code changes** or `CoughAnalyzer.bat` runs
  stale bytecode.
- Java 8 toolchain, dependency-light (gson + commons-compress). No Compose, no Android deps in
  `desktop/`.
- **Kotlin nests block comments**: a `/*` inside a `/** … */` KDoc (e.g. writing a path like
  `audio/*.wav`) opens a nested comment and breaks the file with "Unclosed comment". Avoid `/*`
  (and literal `*/`) inside comments.

## Runtime tool discovery (not bundled)
- **ffmpeg** (`AudioDecoder`): resolved from PATH → winget shim → `Gyan.FFmpeg` package dir.
  Required for WebM/OGG decode and all ALLDATA conversion. v8.1 is installed on this machine.
- **adb** (`UsbImporter`): resolved from `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`LOCALAPPDATA`. Required
  for USB device import.
- **CUDA runtime DLLs** (`GpuFft`, optional GPU CWT): cudart/cufft/nvJitLink loaded from
  `native/cuda/` (gitignored — ~360 MB), env `FFTT04D_CUDA_DIR`, or `CUDA_PATH\bin`. Fetch the
  redistributables without the toolkit via pip wheels:
  `python -m pip install --target native/cuda nvidia-cufft-cu12 nvidia-cuda-runtime-cu12` then move
  the `*/bin/*.dll` up into `native/cuda/`. Missing → `GpuFft.available()` is false and the CWT
  falls back to CPU. Only an NVIDIA driver is otherwise required (no toolkit install).

## Recent work (this session)
- **Directory pickers** replaced the hardcoded `H:\…` dataset paths and the fixed
  `~\FFTT04M_usb_import` destination. Choices persist via Java `Preferences` (node `FFTT04D/dirs`;
  exports use `FFTT04D/export`). First-run defaults fall back to the old fixed paths if present.
- **ALLDATA consolidator** (`AllDataBuilder.kt` + **Build ALLDATA** button): merges the five
  datasets under a sources root into `C:\AndroidStudio\ALLDATA` — every clip → 44.1 kHz mono
  16-bit WAV, all metadata → one `metadata.csv`. See README for the per-source mapping table.
  Parallel (one ffmpeg/core), resumable (existing WAV reused), cancellable (button toggles).
  Verified end-to-end on CoughDataset + ESC-50 and one Coswara date (684 clips, 152 cough /
  532 non-cough), plus resume-reuse and CSV escaping of the JSON column.
- **Filename metadata + analysis images**: output WAV names encode the clip's summary metadata
  (`<source>__<id>__<soundType>__<cough|noncough>__<health>__a<age>__<gender>__<country>.wav`);
  `Row.wav` is set from the final name so CSV rows stay linked. `SpectrogramRenderer.kt` renders,
  beside each WAV, a 512×512 Magma FFT spectrogram (`.png`, size 2048/step 1024, renormalized) and
  a 512×512 Morlet-CWT scalogram (`.jpg`, resampled 20 kHz, level 10, w0 6, no threshold,
  renormalized). The CWT math is ported verbatim from `WaveletActivity.runCwt`; "order" is DWT-only
  and has no effect on Morlet CWT. Build asks yes/no for images (the CWT is the slow part);
  `AllDataBuilder.generateImages` toggles it. Images use the desktop `cough.FFTUtils` (no `:shared`
  dependency). Verified: 512×512 PNG/JPEG output, correct chirp ridge (FFT) and log-frequency CWT
  ridge, trio grouping (wav/png/jpg share base name), and resume skipping existing images.

## Image passes + GPU + Analyze All sources (this session)
- **Image generation is now a separate, resumable second pass** (`ImageBatch.kt`), not part of the
  ALLDATA build. Three buttons over a chosen folder (defaults to the ALLDATA output): **FFT images**
  (PNG), **CWT images (CPU)**, **CWT images (GPU)** — each renders only clips missing that image,
  fans across all cores, is cancellable, and reports device + clips/s. The Build ALLDATA image
  prompt now defaults to **No** (build WAV + metadata only, image later).
- **Optional GPU CWT** (`GpuFft.kt`, JCuda/cuFFT): `SpectrogramRenderer.useGpu` routes the CWT's
  100-scale inverse-FFT bank through cuFFT (batched, persistent plan + device buffer, CPU-side
  multiply/magnitude). Verified correct on a GTX 1660 SUPER, but **transfer-bound**: on a 20-core
  box the simple path is ~1.8× *slower* than CPU (26 MB up + 26 MB down per clip). A real win needs
  on-device multiply+magnitude (NVRTC) — not yet done. The CPU/GPU wavelet buttons exist to measure
  this live. CWT kernels are now cached per padded length (a CPU win independent of GPU).
- **Analyze All** now offers a source picker: loaded list, **ALLDATA folder**, **USB import
  folder**, or both combined (`DatasetLoader.loadFolder`, recursive). The list display caps at 2000
  rows (ALLDATA is ~61k) but the full set is analyzed.

## Cough detector rebuild, cloud meta-analysis, ISOLATE, fixes (this session)
- **New cough/not-cough detector** (replaces the broken median×4 segmenter + rubber-stamp verdict):
  - `WholeClipFeatures.kt` — 14 detection-independent acoustic features over the loudest ~0.7 s window
    (crest, zcr, onset_sharp, env_peak_ratio, active_frac, centroid, flatness, rolloff85, bandwidth,
    hf_ratio, q_ratio, pitch_strength, **syllabic_mod**, **spectral_crest**).
  - `CoughForest.kt` — serializable random forest (gzip resource `cough_forest.txt.gz`, `loadBundled`).
  - `CoughClassifier.kt` — the deployable verdict (features → forest); `thresholdOverride` moves along
    the ROC at runtime.
  - Trained on ALLDATA (cough vs **speech/crying/non-human**; sneezing/breathing/snoring are "don't
    care", excluded). Held-out **AUC 0.92, ~91 % sens / 83 % spec** (sens-leaning threshold 0.48). The
    OLD engine was ~84 % sens / **44 % spec** and missed **95 % of 1-sec clips** (median threshold fails
    when the cough fills the clip). Same model ported into the FFTT04M + FFTT04L mobile cough packages
    (FFT is byte-identical via the shared module). NOTE: the desktop `cough/CoughAnalyzer` segmenter is
    UNCHANGED (still used for the rich per-event features the meta-analysis reads); the forest is a
    separate clip-level verdict. See memory `cough-detection-redesign`.
- **ISOLATE COUGHS** button (`CoughIsolator.kt`): trims each cough WAV in chosen ALLDATA + extras folders
  down to the detected cough span (cuts before/after), overwrites in place under the original name,
  deletes its `.png`/`.jpg`. Non-cough skipped; no-detection clips untouched. `AudioDecoder` gained a
  16-bit-mono WAV writer (`writeWavMono16`).
- **Cloud meta-analysis** (`CloudAnalysis.kt` + **Cloud Match (extras)** button, `CloudMetaAnalyzer.kt`):
  builds a labeled per-recording vector pool from ALLDATA (WholeClipFeatures + 8-value paroxysm block;
  one sound cloud + multi-label qualifiers per recording), then k-NN-matches the user's extras/USB
  recordings → nearest sound cloud + qualifier votes, tagged with cough probability. Writes a report +
  `cloud_match.csv` + `cloud_pca.png` (2D PCA map). Qualifier taxonomy + synonym collapses live in memory
  `meta-analysis-cloud-design`. v1 = acoustic + paroxysm only (image descriptors deferred).
- **GPU image passes are concurrent**: each image button owns its own cancel token (no global flag), so
  CPU + GPU CWT (and FFT) run at once; `SpectrogramRenderer.renderCwtJpg(…, useGpu)` is per-call.
- **Stacked progress bars**: each running task gets its own bar (`TaskProgress`) instead of sharing one.
- **AllDataBuilder auto drill-down**: `findChild` descends through unzip wrappers automatically
  (single-subfolder nesting of any depth/naming — `X/X`, or `public_dataset_v3/coughvid_20211012`) to the
  real dataset content, so Build ALLDATA pointed at a freshly-unzipped root (e.g. `H:\`) finds all five
  datasets. `AllDataBuilder.diagnose(root)` reports resolved paths. (COUGHVID's `public_dataset_v3` may
  lack `metadata_compiled.csv` — copy it from the C:\ `coughvid_20211012` for the expert labels.)
- **Desktop version letter**: `generateVersionLetter` Gradle task stamps a per-build letter (a..z,A..Z)
  into `version.properties`; `BuildInfo` reads it; shown top-right of the title in magenta.

## metadata.csv schema (ALLDATA)
`wav, source, original_id, sound_type, is_cough, health_status, age, gender, country,
cough_detected, metadata_json`
- `is_cough` — cough vs non-cough; Coswara breathing/vowel/counting and ESC-50 non-`coughing`
  categories are the **false** negatives for discrimination training.
- `health_status` — canonical bucket (covid / healthy / symptomatic / recovered / respiratory_* /
  na / unknown); the raw value is always kept in `metadata_json`.
- `metadata_json` — full original metadata, losslessly (one JSON object, CSV-quoted).

## Datasets on this machine (under C:\AndroidStudio)
- `Coswara-Data-dataset-paper-publication` — `YYYYMMDD/` folders, split `*.tar.gz.aa…` archives
  (`<participant>/<sound-type>.wav` + `metadata.json`), `combined_data.csv` (keyed by `id`),
  `csv_labels_legend.json`. This is the *moved/renamed* Coswara (old code expected
  `Coswara-Data-master`); `findChild` matches by prefix so both names work.
- `CoughDataset-main/covid` — small, all presumed COVID.
- `coughvid_20211012` — large flat folder; `.webm`/`.ogg`/`.wav` + per-file `.json`;
  `metadata_compiled.csv` keyed by uuid (`status` = healthy/symptomatic/COVID-19).
- `dataset_1sec` — `covid/healthy/lower/obstructive/upper` 1-second clips; folder = label.
- `ESC-50-master` — `audio/*.wav` + `meta/esc50.csv`; only `coughing` is a cough.

## USB import handshake
Phone **Gallery → SHARE → "Offer recordings to desktop (USB)"** writes `fftt_usb_offer.json` to
`/sdcard/Documents/FFTT04M` (fallback `/sdcard/Android/data/com.example.FFTT04M/files`). Desktop
reads the offer over adb, pulls WAV + `.json`/`.txt` sidecars, imports, and pushes
`fftt_usb_ack.json` back. Works without an active offer too (pulls whatever is staged).

## Possible next steps
- COUGHVID is huge (~34k clips); a full ALLDATA build will take a while and a lot of disk — fine,
  but expect it. The build is resumable, so it can be run incrementally.
- ALLDATA is metadata + WAV only; wiring `Analyze All` / `segments.jsonl` to read an ALLDATA folder
  directly (as a dataset source) would close the loop to training.
- Tier-2 model training on the tensor/segments output, then redeploy to Android (still future).

## SESSION 2026-06-13 — desktop performance notes & queued work

### H:\ drive is the bottleneck (being replaced)
The external **H:\** drive is very slow; several observed issues trace to it, not the app:
- **"Cancel ALLDATA build" is sluggish**, especially mid-Coswara: cancel appears to wait for the
  current `.tar` to finish unpacking before stopping, then runs a validity/consistency pass. QUEUED:
  check the cancel flag *per archive entry* (not per archive) and skip the post-cancel pass.
- **Image generation does NOT auto-start after the data build.** Confirmed *intended* that images don't
  compete during the WAV/metadata build (good — keeps the data pass fast). But once `metadata.csv` is
  written, the FFT/CWT image passes currently must be kicked manually. QUEUED: auto-initiate image
  generation after metadata.csv completes; **GPU wavelets by default** to free CPU. Canceling image
  production is also slow.
- **FFT / GPU-wavelet passes are slow to *initiate*** while the H: disk sits idle — startup latency
  (likely seek/spin-up), not compute.

### Hardware migration (planned)
User is installing **2× SATA HDDs** to replace H:. Plan: format one, move ALL data directories onto it
(datasets incl. `public_dataset_v3` = COUGHVID, ALLDATA output, USB-import, `H:\train`), then re-run
desktop functions and re-evaluate the perf concerns above (several may simply disappear). If the drives
are fast enough, the working **code** dirs (FFTT04D/M/L) may migrate there too.

### Training negatives for the speech/music FP retrain
- `H:\train` holds **.mp3** files (several hours) for the hard-negative retrain. QUEUED "train-negative"
  button: ingest autonomously, chop into appropriately-sized snippets, train the forest on them as
  negatives **in random order**. Caveat: a **very limited (<20)** number of real coughs may have snuck
  into the audio — tolerate that small label noise. Run AFTER the drive swap (H: too slow). Then
  re-derive threshold, redeploy the model to all 3 apps, drop the 0.65 stopgap override.

### Misc
- `.tar` dedup (QUEUED): open archives only far enough to **list entries** and match, not fully unpack
  (Coswara is `.tar`).
- `public_dataset_v3` is NOT a missing dataset — it's COUGHVID's folder name (collectCoughvid aliases
  `public_dataset_v3`/`public_dataset`). A low row count there earlier was just the zip still extracting
  during the scan; re-running Build ALLDATA is idempotent (reuses converted WAVs).
- Results-pane font enlarged 10 → 14pt this session.
