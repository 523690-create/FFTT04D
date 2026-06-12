# FFTT04D — Cough Analysis Desktop

The **Windows desktop companion and analytic engine** for the FFTT04M mobile app. It runs the
*same* Tier-1 cough DSP as the phone, but fanned out across every CPU core, so large public
datasets can be batch-analysed offline and turned into training data the mobile app can't compute
on-device.

> This is the desktop repo. The README, HANDOFF and agents docs in the *mobile* repo (FFTT04M /
> FFTT04L) describe the Android app — don't follow those here. The authoritative desktop docs are
> this file, [WINDOWS_PORT.md](WINDOWS_PORT.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

- **Branch:** `port_windows`
- **UI:** Java Swing (`desktop/` module, `MainKt` → `AnalyzerWindow`). *Not* Compose, despite the
  original plan in WINDOWS_PORT.md.
- **Toolchain:** Kotlin/JVM, Java 8 toolchain, Gradle.
- **External tools:** `ffmpeg` (audio decode/convert) and `adb` (USB device import) are discovered
  on `PATH` / standard install locations at runtime; neither is bundled.

## Running

```
gradlew.bat :desktop:fatJar          # build desktop\build\libs\CoughAnalyzer.jar
CoughAnalyzer.bat                     # launch it (java -jar …)
```

`launch-cough-analyzer.vbs` is a no-console launcher for a desktop shortcut (edit its `root=` if
the repo isn't at the path it hardcodes). `gradlew.bat :desktop:run` also works for development.

## What the window does

A single window with dataset buttons on the left, a recordings list, an analysis text pane on the
right, and a progress/status bar at the bottom. All long operations run on background threads and
report into the progress bar; nothing blocks the UI.

### Loading recordings
- **Load Cough Dataset 1 / Load ESC-50 / Load Coswara** — open a remembered directory chooser
  (no more hardcoded `H:\…` paths) and parse that dataset's audio + metadata into the list.
  Coswara split `.tar.gz` archives are extracted on demand.
- **Request from USB Device** — pull recordings + metadata sidecars off a USB-connected Android
  device (FFTT04M/FFTT04L) via `adb`, into a directory you choose. Supports the cooperative
  offer/ack handshake (see *USB import* below).

### Analysing
- **Analyze All** — runs the full Tier-1 cough engine (`ParallelCoughAnalyzer`) across every core:
  FFT q-ratio/Fmax, ridge parabola, T1/T2/T3 phases, 13-band MFCC, and a speech-vs-cough verdict
  per detected event. (This is the homologous `cough/` DSP package, identical to the phone's.)
- **Export segments.jsonl** — write the canonical per-segment JSON-lines training file. Remembers
  the last export directory; never overwrites (auto-increments the filename).
- **Meta-Analysis (Tensor)** — build one z-scored feature tensor over every cough event
  (`MetaAnalyzer`), report nearest-neighbours / pairwise-distance stats, and offer a tensor CSV.

### Build ALLDATA (dataset consolidator)
**Build ALLDATA** merges the several datasets that sit side-by-side under one folder into a single
analysis-ready corpus. Pick the **sources root** (default `C:\AndroidStudio`, the folder that
*contains* the datasets) and the **output folder** (default `C:\AndroidStudio\ALLDATA`); the
builder then:

- transcodes **every** clip to 44.1 kHz mono 16-bit PCM **WAV** in the output folder (via ffmpeg);
- writes one **`metadata.csv`** with a row per output WAV, in uniform columns:
  `wav, source, original_id, sound_type, is_cough, health_status, age, gender, country,
  cough_detected, metadata_json` — the last column preserves the full original metadata losslessly;
- derives a consistent **cough / non-cough** label (`is_cough`) and a canonical **health_status**
  across every source;
- **encodes each clip's key metadata into its WAV filename** —
  `<source>__<id>__<soundType>__<cough|noncough>__<health>__a<age>__<gender>__<country>.wav`
  (blanks skipped, sanitized, length-capped) — while the CSV's `wav` column always matches the
  on-disk name, so files and rows stay linked;
- optionally renders, beside each WAV (same base name), two **512×512** analysis images:
  - **`.png`** — FFT spectrogram (size 2048 / step 1024), Magma, renormalized to full intensity;
  - **`.jpg`** — Morlet **CWT** scalogram (resampled to 20 kHz, level 10, w0 = 6, no threshold),
    Magma, renormalized.

  Image rendering (especially the CWT) is the slow part, so the build asks yes/no first.

It is **parallel** (one ffmpeg per core), **resumable** (existing output WAVs and images are reused,
never regenerated) and **cancellable** (the button toggles to *Cancel ALLDATA build* while running).

| Source folder (under the root) | Audio | `is_cough` | `health_status` | Metadata source |
|---|---|---|---|---|
| `Coswara-Data-dataset-paper-publication` | per-participant WAVs in split tars | `cough-*` → true; breathing/vowel/counting → **false** (negatives) | from `covid_status` | `combined_data.csv` (by `id`) **+** in-tar `metadata.json` |
| `CoughDataset-main` | `covid/*.wav` | true | **covid** (presumed) | folder convention |
| `coughvid_20211012` | `.webm/.ogg/.wav` | true | from `status` | per-file `.json` **+** `metadata_compiled.csv` (by uuid) |
| `dataset_1sec` | `covid/healthy/lower/obstructive/upper/*.wav` | true | from folder name | folder = `sound_type` |
| `ESC-50-master` | `audio/*.wav` | `coughing` → true; all others → **false** (negatives) | `na` | `meta/esc50.csv` |

The negatives (Coswara breathing/vowel/counting, ESC-50 non-cough categories) are exactly what
trains the app's cough/non-cough discrimination.

## Module layout (`desktop/src/main/kotlin/com/example/FFTT04M/desktop/`)

- `Main.kt` — `AnalyzerWindow` Swing UI + all button handlers and directory pickers.
- `DatasetLoader.kt` — parsers for Cough Dataset 1, ESC-50, Coswara (tar extraction), USB imports.
- `AudioDecoder.kt` — decode to float PCM and `convertToWav()`, resolving ffmpeg on PATH/winget.
- `AllDataBuilder.kt` — the ALLDATA consolidator (this file owns its own CSV/JSON helpers).
- `ParallelCoughAnalyzer.kt` — multi-core batch driver for the Tier-1 engine.
- `MetaAnalyzer.kt` — z-scored cough feature tensor + similarity stats.
- `UsbImporter.kt` — adb device listing, pull, and the offer/ack handshake.
- `cough/` — the shared Tier-1 DSP, homologous to the mobile app's `cough/` package.

## USB import (cooperative handshake)

The phone's **Gallery → SHARE → "Offer recordings to desktop (USB)"** writes a
`fftt_usb_offer.json` manifest into `/sdcard/Documents/FFTT04M` (fallback
`/sdcard/Android/data/com.example.FFTT04M/files`). The desktop's *Request from USB Device* reads
that offer over `adb`, pulls the WAV + `.json`/`.txt` sidecars, imports them, and pushes a
`fftt_usb_ack.json` back so the phone's dialog confirms the transfer. Pulling whatever is already
staged works even without an active offer.

## Datasets it understands (as laid out on this machine)

- **Coswara** — `Coswara-Data-dataset-paper-publication/`: `YYYYMMDD/` date folders with split
  `*.tar.gz.aa…` archives (each holding `<participant>/<sound-type>.wav` + `metadata.json`),
  plus `combined_data.csv` and `csv_labels_legend.json`.
- **CoughDataset-main** — a small `covid/` set of cough clips.
- **coughvid_20211012** — large flat folder of `.webm`/`.ogg`/`.wav` with per-file `.json` and a
  compiled `metadata_compiled.csv`.
- **dataset_1sec** — one-second clips foldered by condition (covid/healthy/lower/obstructive/upper).
- **ESC-50-master** — 2000 environmental clips; only the `coughing` class is a cough.
