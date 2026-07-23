# HANDOFF — FFTT04D desktop (for Claude Code)

Read this first. It captures desktop-specific context that isn't obvious from the code.
Date: 2026-06-13 (latest session appended at top: 2026-07-17).

## SESSION 2026-07-23 — autonomous resume-paused-work: no-op (9th consecutive) — all repos clean/pushed, no device connected
Same as every run since 07-12: D=`1307263`, M=`60f7286`, L=`942d9c7`, all clean/pushed, zero java.exe,
`adb devices` empty, `phoneme_segment_edits.json` unchanged since 2026-07-10 (still 238 bytes). No stranded
work, nothing new to act on. No commits.

## SESSION 2026-07-22 — autonomous resume-paused-work: no-op (8th consecutive) — all repos clean/pushed, no device connected
Same as every run since 07-12: D=`56d0eb4`, M=`60f7286`, L=`942d9c7`, all clean/pushed, zero java.exe,
`adb devices` empty, `phoneme_segment_edits.json` unchanged since 2026-07-10 (still 238 bytes). No stranded
work, nothing new to act on. No commits.

## SESSION 2026-07-21 — autonomous resume-paused-work: no-op (7th consecutive) — all repos clean/pushed, no device connected
Same as every run since 07-12: D=`999eed4`, M=`60f7286`, L=`942d9c7`, all clean/pushed, zero java.exe,
`adb devices` empty, `phoneme_segment_edits.json` unchanged since 2026-07-10 (still 238 bytes). No stranded
work, nothing new to act on. No commits.

## SESSION 2026-07-17 — autonomous resume-paused-work: no-op (6th consecutive) — all repos clean/pushed, no device connected
Same result as 07-12 through 07-16: D/M/L all clean + fully pushed (D=`aafaa2d`, M=`60f7286`, L=`942d9c7`),
zero java.exe, `adb devices` empty (still no new Ground Truth Review data). Re-checked every open follow-up
across memories/COUGH_ISOLATION.md — all remain gated on either (a) the user collecting more labelled
device data, or (b) explicit user preference (mobile Breath/Snore bucket merge), same as documented
2026-07-12/16. `COUGH_ISOLATION.md`'s two remaining unchecked boxes ("wire CoughGate into a GUI button",
"user-domain override") are the OLD ALLDATA-trained fuser path, superseded by the in-domain `CoughVote`
already shipped to mobile ([[on-device-cough-vote]]) — not picked up as busywork on a deprioritized path.
No commits this run. Per the 07-16 note, future no-op runs should stay this terse unless something changes.

## SESSION 2026-07-16 — autonomous resume-paused-work: found+landed the 2026-07-15 session's stranded commit (5th consecutive no-new-code audit)
The 2026-07-15 session's HANDOFF.md edit (below) was never committed — it was sitting as an uncommitted
working-tree change, i.e. exactly the "crash/resource-exhaustion mid-session" case this task exists to
resume. Verified it was still accurate before landing it: `git status -sb` on all three repos matched the
commits it names exactly (FFTT04D/port_windows `1fb4f9b`, FFTT04M/blue_sky `60f7286`, FFTT04L/main
`942d9c7`, all clean/pushed), `tasklist` showed zero java.exe, `adb devices` was empty (no phone connected,
so still no new Ground Truth Review data since 2026-07-12/14). Committed it as-is (docs-only, no code
changed) rather than rewrite, then re-ran the same follow-up sweep myself independently before appending
this note — same result: `on-device-cough-vote` (a)/(b) still explicitly gated on field-validation/data
that hasn't happened, (d) still an explicit user-preference question, `phoneme-atlas-player-todo` has
nothing open besides "wait for more segment edits to accumulate", FFTT04L untouched since 2026-06-17 with
no HANDOFF gap. **This is now 5 consecutive sessions (07-12 through 07-16) reaching the identical
conclusion: the only remaining lever on the cough-detection research line is the user collecting more
labelled device data via the already-built, already device-tested Ground Truth Review tooling — no
autonomous session can manufacture that. Future scheduled runs should keep checking for a connected device
/ new data rather than re-deriving this conclusion from scratch each time**; if 07-17 onward again finds
zero new data and zero stranded work, a terser one-line log entry is enough — no need to re-justify at
length.

## SESSION 2026-07-15 — autonomous resume-paused-work audit: nothing stranded, no new work started (4th consecutive no-op)
Ran the standing "resume paused work" scheduled task. All three repos unchanged since the 2026-07-14
session: `git status -sb` on FFTT04D/port_windows (`1fb4f9b`), FFTT04M/blue_sky (`60f7286`), FFTT04L/main
(`942d9c7`) all clean, fully pushed, zero commits ahead/behind origin. `tasklist` showed zero java.exe
(no concurrent-build risk). `adb devices` empty — no phone connected, so nothing new was collected via
Ground Truth Review since the last run either.
Re-checked every open follow-up across the tracked memories/docs — all are blocked on the same things the
2026-07-12/13/14 sessions already identified, and none of that has changed:
- `phoneme-atlas-player-todo`: segment-edit retraining validation is DONE (null result, 2026-07-14);
  vertex-window precision was already assessed as not a real bug (2026-07-12) — nothing left to do here.
- `on-device-cough-vote` follow-up (a) (DSP-only vote in the hot real-time capture-time gate): still
  explicitly gated on AutoReject being "field-validated" — no evidence of extended real-world use since
  the one-time Pixel 10 device test on 2026-07-12, so left untouched rather than touch the hot path on a
  guess.
- follow-up (b) (in-domain codebook cough-fraction as a 4th vote): still an "expensive" research task with
  no new labelled data to make it worthwhile right now.
- follow-up (d) (merge Breath/Snore display buckets on mobile): still explicitly conditional on the user's
  own preference — not something an autonomous session should decide unilaterally.
- FFTT04L (main) hasn't been touched since 2026-06-17; its HANDOFF.md is not stale relative to it (no gap).
**Conclusion (same as the last two runs): the only real remaining lever on the main cough-detection
research line is the user actually using the (already device-tested) Ground Truth Review tooling on their
phone to grow the labelled device dataset past its current size — see `cough-detection-architecture` /
`mobile-capture-transfer` memories. No autonomous desktop or mobile session can manufacture that data.
Nothing changed this run; no commits.

## SESSION 2026-07-14 — autonomous resume-paused-work: segment-edit retrain validation DONE (null result); found+fixed a stale "blocked" conclusion
All three repos (D/M/L) clean/pushed at session start, no device connected, no concurrent build. The prior
two sessions (2026-07-12/13) had concluded the "segment-edit retraining validation" follow-up (see
`phoneme_atlas_player_todo.md`) was blocked because `phoneme_segment_edits.json` "doesn't exist on disk" —
**that was wrong**: the real path (`Workspace.repoRoot`-resolved, one level OUT of this repo at
`D:\AndroidProjects\data\codebooks\phoneme_segment_edits.json`) has had real content since 2026-07-10
11:28 (one clip, 12 window overrides). Also found the `-Dsegedit.weight` CLI flag was never wired through
`desktop/build.gradle.kts`'s `phonemeCodebookCli` task (always silently used the hardcoded default 8) —
fixed with a one-line `systemProperty` passthrough, commit `813fb93`, pushed.
Backed up `data/codebooks/*.json` to `data/_backup_pre_segedit_validate_20260714/`, then ran the
documented production rebuild recipe (`-Dhubert.feat -Dcodebook.k=256 -Dpurify.mixed -Dcodebook.only
-PuseOnnxGpu`) twice, varying only `-Dsegedit.weight`: **weight=1 (no boost) → classifier CV 81% (1361
clips); weight=8 (shipped default) → classifier CV 80%.** NULL result — within k-means run-to-run noise,
not a real regression; expected given only 1 of 1361 test clips carries an edit right now. Feature
confirmed working (applies without crashing/dominating), just not yet measurable at n=1 — revisit once
more Tier-B edits accumulate. Full detail + numbers in memory `phoneme-atlas-player-todo`. Final on-disk
codebook state = the weight=8 (default) run's output, matching normal production rebuild behavior.

## SESSION 2026-07-13 — autonomous resume-paused-work: nothing stranded on FFTT04D; picked up a mobile item
All three repos (D/M/L) clean and pushed at session start — no crash/resource-exhaustion fallout to
resume. No device connected, no concurrent build process. The desktop research line remains DEPRIORITIZED
per the 2026-07-12 conclusion (unchanged: `phoneme_segment_edits.json` still doesn't exist on disk, no new
labelled device data). Rather than manufacture desktop busywork, picked up a small, well-scoped, non-device
-testing-gated follow-up from FFTT04M instead: exposed `AutoReject.VOTE_REJECT_THRESHOLD` as a gallery
Tools-spinner setting (blue_sky `045bac8`/`60f7286`). Full detail in FFTT04M's own HANDOFF.md and memory
`on-device-cough-vote`. FFTT04D itself: no code changes this run.

## SESSION 2026-07-12 — autonomous resume-paused-work audit: nothing stranded, no new work started
Ran the standing "resume paused work" scheduled task. Checked all three repos for the failure modes it
exists to catch: `git status -sb` on FFTT04D/M/L all showed **working tree clean, up to date with
origin** (no local unpushed commits on port_windows/blue_sky/main) — nothing was stranded by a crash or
resource exhaustion this time, so no push was needed. `tasklist` showed **zero running java.exe** — no
concurrent-build lock risk (see `scheduled-task-concurrency-lesson` memory), so it was also safe to build
if there had been something to build.
Re-checked the two open items in `phoneme_atlas_player_todo.md`:
- **Segment-edit retraining validation** — still blocked. `data/codebooks/phoneme_segment_edits.json`
  doesn't exist on disk at all (not even `{}`), confirming zero real Tier-B edits exist yet to retrain
  against. This needs the user to add edits via the running desktop app first; nothing to do here without
  fabricating data.
- **Vertex-window precision** — on inspection this isn't actually a fixable bug: decode windows are
  180ms/90ms-hop (50% overlap), so `autoDetectSquiggles`'s nearest-window-center heuristic
  (`PhonemePlayer.kt` ~line 262) is already the principled choice given that granularity — there's no
  better-defined "correct" window for a vertex time that falls in the overlap of two windows. Left as
  documented (occasional off-by-one-window, acceptable), not touched.
Per `cough-detection-architecture` memory, the main research line (breath-FP reduction) is explicitly
DEPRIORITIZED until the labelled device set grows past 1,899 clips — the only real next lever is the
user actually using the (already device-tested, per `mobile-capture-transfer`) Ground Truth Review
tooling on their phone, which no autonomous desktop session can do. Concluded there was no concrete,
non-speculative desktop task to pick up this run rather than manufacture busywork.

## SESSION 2026-07-11 — breath-specificity round 5: transfer-learning + DSP-MLP, BOTH NULL (autonomous resume)
Full detail in memory `cough-detection-architecture` round 5. Round 4 found the coswara-benchmark
linear->MLP breakthrough (44->9 alarms/hr) does NOT transfer in magnitude to real device audio (33.3%
best in-domain FP vs 1.0% coswara) and diagnosed it as data scarcity (1,899 device clips vs 10,716
coswara). This session tried round 4's two remaining secondary next-steps:
- **Transfer learning** (commit `91ff977`): `Mlp.kt` gained `initFrom: Model?` warm-start; `BreathSpecCli`
  saves one final full-data coswara MLP to `data/codebooks/breath_mlp_coswara.json`; `DeviceHubertEvalCli`
  warm-starts device folds from it. **NULL: 37.4% FP (transfer-init) vs 37.2% (from-scratch) — a wash.**
- **DSP nonlinearity** (commit `6563e93`): added DSP-only(MLP) and FUSED(HuBERT-MLP+DSP-MLP) variants.
  **NULL: fused-with-DSP-MLP 33.8% vs fused-with-linear-DSP 33.3% — no compounding gain.**
- **Real bugfix found+fixed** (commit `958f6c5`): `deviceHubertEval`'s gradle task always passed
  `-Ddevice.dir=""` when unset, silently defeating the CLI's own `p3`/`device_ingest` fallback and making
  the FIRST re-run of this session report "0 labelled clips" with no error. Fixed with `.takeIf{isNotBlank()}`.
- All 4 commits green (jars 'l'/'m'(via breathSpec)/'o'/'p'/'q'/'r' across the session), all pushed to
  `port_windows`.

**CONCLUSION: the algorithmic toolkit (hard-neg mining, cascading, linear->MLP, transfer-learning,
DSP-nonlinearity) is now exhausted at the 33.3% in-domain breath-FP floor. Desktop algorithm work on this
line is DEPRIORITIZED until the labelled device set grows past 1,899 clips.** Next real lever: get the
FFTT04M Ground Truth Review tooling (blue_sky `fb6285e`/`5b05870`, built but still NOT device-tested) onto
an actual phone and used to collect more labelled breath/voice/cough data — see `mobile-capture-transfer`
memory. That is a mobile/device-testing task, not something further desktop CLI work can unlock.

## SESSION 2026-07-10 — cough-isolation stacked gate resumed (autonomous scheduled-task run)

Resumed paused work: `COUGH_ISOLATION.md`, `CoughGate.kt`, `CoughGateCli.kt` were sitting UNTRACKED
with no Gradle task wired up (the CLI was fully written but unreachable — the prior session almost
certainly paused right after writing the code, before it could run/validate/commit). Added
`:desktop:coughGate` to `desktop/build.gradle.kts`, built green (`compileKotlin` + `fatJar`, jar 'D'),
then ran the CLI over the full 175,483-segment `cough_harvest`. See `COUGH_ISOLATION.md` and memory
[[cough-isolation-ensemble]] for the eval numbers and the squiggle-join id-space gotcha found while
scoping signal 4 (parent-clip id vs segment sub-span id — needs offset parsing, not a naive CSV join).
Committed + pushed: port_windows `568279e`. FFTT04M/FFTT04L had no local unpushed work this run.

## SESSION 2026-07-06 — cough-harvest → verify → classify → forest → hallmark-phoneme pipeline

Goal: naive-isolate likely coughs from ALLDATA, auto-verify them cheaply, and cross-check classifiers so
manual labelling is minimised. New headless gradle tasks (all `maxHeapSize=3g`, `--no-daemon`; GPU ones need
`-PuseOnnxGpu`). Outputs land under `<repoRoot>/../cough_harvest/` (sibling of the projects, NOT in any repo).

- **`harvestCoughs`** (`HarvestCli`/`CoughIsolator.harvest`) — non-destructive, DSP-only, all cores. Extracts
  each `isLikelyCough` event tightly (>4s skipped, monster-file guard) into buckets by filename metadata:
  `cough_confirmed` (111,027), `cough_found_in_other` (61,140, high-recall/low-precision), `cough_unknown`
  (3,316) + `harvest_manifest.csv`.
- **`verifyHarvest`** (`HarvestVerify`, GPU) — HuBERT whole-clip embedding → `cough_head_ALLDATA.json` P(cough);
  low-P → `_rejected_lowP/`, writes `<bucket>_verified.csv`. found_in_other kept 6,534 @ P≥0.75. **WINDOWS
  MOUNTVOL GOTCHA**: `File.renameTo()` silently returns false ~85% under concurrent load on the G:/D: NVMe
  mount — the tool now scores in parallel then MOVES in a sequential `java.nio.file.Files.move` pass. Never
  trust renameTo's boolean here.
- **`cwtImages`** (`ImageCli`/`ImageBatch`, GPU jcufft) — CWT `.jpg` scalograms over a folder (resumable,
  all cores). `-Dimage.skip=<subdir>` prunes a subtree. `ImageBatch.run` gained an optional `skipDirName`.
- **`harvestClassify`** (`HarvestClassifyCli`, CPU) — trains a linear wavelet-image cough classifier on 30k
  ALLDATA scalograms (81% fit), predicts on all 175k harvest jpgs, joins the head scores, writes
  `harvest_compare.csv`. **BAG-AWARE eval** ("cough" filename = a cough is present SOMEWHERE, not that every
  segment is cough; speech/breath/counting DENY cough → hard per-segment negatives).
- **`forestScore`** (`HarvestForestCli`, CPU) — re-gate through the trained CoughForest (mobile's real gate).
  **NEGATIVE RESULT**: forest over-fires as a POST-segmentation filter (77.9% FP on hard negatives vs head
  13.7% / wavelet 17.5%) because every candidate is already a pre-selected burst; the forest belongs on the
  RAW STREAM (mobile's `CoughDetector`), not on isolated candidates. Keep the head+wavelet 2-way.
- **`coughPhonemes`** (`CoughPhonemeCli`, GPU) — discovers a fresh HuBERT acoustic-unit vocabulary (180/90ms
  windows → HuBERT-768 → k-means K=256) from consensus-labelled segments; ranks units by cough-specificity.
  **~80 of 256 are HALLMARKS** (train precision ≥90%; top ~9 are 100% precise, lift up to 111×, far from all
  non-cough units). Detector "≥1 hallmark unit ⇒ cough" = ~85% precision / ~84% recall on held-out TEST — an
  interpretable, LANGUAGE-AGNOSTIC speech/breath rejector. `-Dcp.export` writes the deployable
  `data/codebooks/cough_hallmark_units.json` (256 centroids + norm + isHallmark flags). NOTE: k-means picks up
  slight run-to-run variation from parallel-embedding row order (sort windows first for bit-reproducibility).
- **`hallmarkDecode`** (`HallmarkDecodeCli`, GPU) — decode all 175k with the exported codebook →
  `harvest_hallmark.csv`, fuse with head+wavelet → `harvest_triage.csv` (final label + confidence tier).

Comparison headline (175,483 segments, from `harvest_compare.csv`): wavelet↔head per-segment agreement 68.8%,
r=0.42 (correlated, NOT interchangeable). Consensus triage: 30.8% both-cough (auto-accept), 38.1% both-not
(auto-reject), 31.2% disagree (manual review). 19,725 confirmed segments both call not-cough = suspected
pre/post-cough phonemes (flagged `suspectPhoneme` in the CSV). GUI: third **Harvest ⇱ window** breakout button
(`Main.kt openHarvestBucket()`) pops a per-bucket chooser, loads NON-recursively (`DatasetLoader.loadFolder(…,
recursive=false)`) so found_in_other shows only kept coughs, not its `_rejected_lowP` subfolder.

## HARDWARE (this desktop, 2026-06-13) + acceleration status
- **GPU: NVIDIA RTX 4060 Ti** (cuFFT path applies) · **CPU: Intel Core Ultra 7 265 (20C)** ·
  **NPU: Intel AI Boost** · iGPU: Intel Graphics.
- **CUDA 12.6 DLLs are installed** at `desktop/native/cuda/` (`cudart64_12.dll`, `cufft64_11.dll`,
  `nvJitLink_120_0.dll`, from the NVIDIA 12.6.3 redist — JCuda dep is 12.6.0). `GpuFft.available()`
  now returns **true** on this box. (The toolkit at v13.0 is the WRONG version for JCuda 12.6 — not used.)
  GpuFft DLL discovery walks up from the jar so the icon/VBS launch finds them with no env var.
- **Measured (warm, 5 s clips):** GPU(serialized) **60.8 ms/clip** vs CPU **162.8 ms/clip** single-thread,
  but ~**8.1 ms/clip** across 20 cores. So one serialized GPU is ~7.5× SLOWER than the 20-core CPU pool
  for short clips — funnelling every clip through the GPU (`useGpu=true` everywhere) would SLOW the build.
- **Fix: HETEROGENEOUS CWT** — `renderImages` runs a clip on the GPU only when a 1-permit semaphore is
  free, else CPU; the GPU's throughput ADDS to the 20 CPU cores (≈+13%) instead of serializing.
  A bigger GPU win would need cross-clip batching / multiple CUDA streams (GpuFft is single-device today).
- ffmpeg audio transcode is CPU/IO-bound (one-per-core, 20 cores) — GPU/NPU don't help there.
  **NPU (AI Boost) is for the future NEURAL tier** (EAT/CNN log-mel via ONNX-Runtime + OpenVINO EP),
  NOT FFT/CWT/transcode — wire it when that model lands.

## SESSION 2026-06-13b — ALLDATA metadata extraction fix + train / UrbanSound8K sources
Fixes a real transfer bug + adds two sources (user is still importing audio; full ALLDATA build runs
tomorrow — code compiles + fatJar builds, metadata rule validated against the real CSVs).
- **Boolean metadata was transferred wrong.** The old merges (`if (v.isNotBlank()) meta[k]=v`) copied
  boolean flags verbatim → e.g. COUGHVID `respiratory_condition=False` on 17,107 rows, Coswara
  `smoker=False`/`smoker=n`. New **`AllDataBuilder.mergeMeta`** (used by COUGHVID, Coswara, and the new
  collectors): a **true-like** field (`true/yes/y`) contributes only its KEY as a qualifier
  (`key=true`); a **false-like** field (`false/no/n`) is OMITTED; anything else is kept as `key=value`.
  Numeric 0/1 are NOT treated as boolean (cough_detected="0.0" is a real probability). This is exactly
  the "reject misleading metadata incl. false-attached" rule.
- **New `collectUrbanSound8K`** — real metadata (metadata/UrbanSound8K.csv keyed by slice_file_name;
  audio/fold1..10/), all classes are non-cough negatives (is_cough=false, sound_type=class).
- **New `collectTrain`** — long-form radio speech (52 × ~25-min mp3) ffmpeg-segmented into 6 s WAV
  chunks (capped 60/episode via `AudioDecoder.segmentToWav`) labelled "mostly speech" negatives.
- ALLDATA→ALLDATA still guarded (excludeDir). `diagnose()` now lists UrbanSound8K + train.
- Residual: 2 Coswara rows have a state name in the `smoker` column (upstream CSV shift) — flagged,
  not auto-repaired.

## SESSION 2026-06-13 — Acoustic Unit Discovery (cough "phoneme" codebook) — BUILT, NOT YET RUN
Workspace is now **D:\AndroidProjects** (fresh clones; datasets still being imported by the user).
Plan agreed: treat each discrete respiratory event-type as an acoustic unit ("phoneme"); desktop does
the hard discovery and exports ONE joint codebook; the M apps load it to keep RESPIRATORY tokens and
reject SPEECH/NOISE (train-on-desktop / infer-on-device, same pattern as cough_forest.txt.gz).
- **New: `AcousticUnitDiscovery.kt`** — decode + `WholeClipFeatures` (14-dim, byte-identical on M, so
  the codebook is portable) → z-score (`CoughSimilarity.standardize`) → **k-means** (k-means++, fixed
  seed 42 = reproducible) → tag each unit's coarse group by labelled-majority + purity + a centroid
  exemplar. Exports `codebook.json` = {feature_names, standardization mean/std, units[{id,group,fine,
  centroid,size,purity,exemplar}]}. Device decode = extract WholeClipFeatures → standardize with these
  stats → nearest centroid → keep if group==RESPIRATORY.
- **New: `RespiratoryTaxonomy.kt`** — metadata→{RESPIRATORY,SPEECH,NOISE,UNKNOWN}+fine. Heuristic &
  extensible (Coswara sound_type, ESC-50 category, CoughDataset1, USB). stridor/wheeze have no public
  data yet → those units won't appear until collected.
- **UI:** "Discover Codebook" button (prompts K, default 64) → runs on the loaded `recordings`,
  writes codebook_NNN.json (persistent dir + increment), reports units/groups/purity.
- **STATUS: compiles + fatJar builds (14 MB). NOT run on data — user is still importing DBs; first
  real discovery run is TOMORROW.** Group-tagging should be label-supervised (it is) to keep keep/
  reject accuracy high; v1 operates per loaded dataset — a "load all sources" sweep is a tomorrow add.
- **Next:** run discovery on the full corpus; then build the M-side codebook decode (replace/augment
  the forest gate with nearest-centroid respiratory tokenization; store token id in cough metadata).


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
