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
