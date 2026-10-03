# HANDOFF — FFTT04D desktop (for Claude Code)

Read this first. It captures desktop-specific context that isn't obvious from the code.
Date: 2026-06-13 (latest session appended at top: 2026-10-03).

## SESSION 2026-10-03 — autonomous resume-paused-work: ALLDATA redecoded (live codebook); music/speech/impulse negatives measured
State on arrival: D=`60cb090`, M=`019a8ba`, L=`942d9c7`, all clean/up-to-date, zero java.exe, `adb devices` empty.
No code change, no codebook rebuild (`p3_*.json`, `phoneme_segment_edits.json` untouched).

1. **`ALLDATA_decoded.json` refreshed** — decode-only with the live `p3_phonemes.json`:
   `:desktop:phonemeCodebookCli -Dhubert.feat=true -PuseOnnxGpu --args="D:/AndroidProjects/ALLDATA
   D:/AndroidProjects/data/fractionation/ALLDATA/Spectral_Flux_Onset_segments.jsonl alldata_20261003
   D:/AndroidProjects/data/codebooks/p3_phonemes.json"` (33 min CUDA, 75,705/76,132 decoded, 427 skipped
   degenerate → 414 ids from the 07-07 file now have no decode). Output moved out of `data/codebooks/`, then
   copied over `ALLDATA_decoded.json`. No id overlap with p3/harvest decodes; the 7 confirmed feedback clips
   are covered by `decode_feedback_labels.json`, so no training label moves. The gradle run bumped the build
   index to 262 ('c') without a jar; the shipped jar is still 'a'. Restart a running desktop app to see it.
2. **coughScore per negative category** (`negatives_by_category.py`, training ids excluded), λ=0.4 false-alarm
   rate: **urban8k street_music 0.3 %, old-time-radio speech 0.4 %** (the "music/out-of-domain speech" item is
   now measured), steady urban noise ≤6 %, coswara breathing 10.7 %, **impulsive sounds are the weak spot**:
   gun_shot 42 %, dog bark 27 %, esc50 knock/can-opening/rooster/dog 40–45 %. Recall on ALLDATA: coswara
   cough 70.0 %, coughvid 56.0 % (the 07-07 codebook gave 82/68 but probably trained on more of these clips).
Everything (backup of the old decode, log, script, old/new tables, RESULTS.md): `data/_backup_pre_alldata_refresh_20261003/`.

**Open next:** an impulsive-noise negative class (gun_shot/dog_bark/knock) in the next codebook rebuild, then
re-run the table + coswara holdout; device `.phon` coughScore validation (needs device); `harvest_decoded.json`
(175k, 07-07) is also stale numbering if anyone uses its tooltips.

## SESSION 2026-10-02 — autonomous resume-paused-work: p3 decode refreshed; confirmed-label relabelling bug fixed
State on arrival: D=`8316f76`, M=`019a8ba`, L=`942d9c7`, all clean/up-to-date, zero java.exe, `adb devices` empty.

1. **Bug (fixed, D `4d415c4`, jar 'a', build index 260):** `confirmedLabels` turned a "decode correct" verdict
   into the `classLabel` of whichever `*_decoded.json` was NEWEST at rebuild time, not the class the user saw.
   Refreshing p3 would have flipped 2 of the 3 confirmed device clips voice→croup as training labels (the
   7 confirmations total; coughvid ones likewise were silently re-resolved when ALLDATA was redecoded 07-07).
   `DecodeFeedback` now also writes `data/codebooks/decode_feedback_labels.json` (id → class at confirm time);
   `confirmedLabels` prefers it, glob = fallback. The 7 existing confirmations were frozen to their
   pre-refresh labels (5× voice, dry hacking, sneeze).
2. **`p3_decoded.json` refreshed** — replaced the 07-07 file with `_indomain_check_20260930/p3score_20260930_decoded.json`
   (same 10,088 ids, live 09-29 codebook, carries `coughScore`; no id overlap with ALLDATA/harvest). 40% of
   dominant letters / 45% of class labels change → grid tooltips now match the live codebook numbering for p3.
3. **`p3_phoneme_fft` atlas** — old dir+json moved to backup, `:desktop:phonemeFft -PuseOnnxGpu` regenerated: 253/256
   phonemes covered (32,810 class-matched clips, 39 min on CUDA). The gradle run bumped the build index to 261
   ('b') without producing a jar — the shipped jar is still 'a'.
Backup of everything replaced: `data/_backup_pre_p3refresh_20261002/` (old p3_decoded.json, decode_feedback.json,
p3_phoneme_fft/ + .json). A running desktop app must be restarted to see the new decode/atlas.

**Open next:** `ALLDATA_decoded.json` (76k clips, 07-07) still stale — needs a heavy decode-only run, then MOVE the
output name into place deliberately; device `.phon` coughScore validation (needs device); music negatives unmeasured.

## SESSION 2026-10-01 — autonomous resume-paused-work: coughScore consumed (grid + M app), decode-glob hazard fixed, λ checked on speech
State on arrival: D=`3a4eef8`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date, zero java.exe. Worked the four
non-device-gated follow-ups from 09-30. No codebook rebuild; `p3_*.json` and `phoneme_segment_edits.json` untouched.

1. **Grid uses coughScore** (D `36c1c48`, jar **'Y'**, build index 258). Rule moved to shared `CoughScore.kt`
   (CLI behaviour identical). The grid computes it at READ time from the decode word with Tier-B edits overlaid,
   against the live codebook's letter→class map — the production decodes (07-07) predate the field. Valid on that
   stale `p3_decoded.json`: 568 manual clips @λ=0.4 → acc 87.9 / recall 87.3 / reject 88.2 %. Tooltip shows
   `cough score +0.23 → cough`; new **Show: Likely cough (unlabeled)** filter = unlabelled clips scoring > 0.
2. **M app mirror** (M `019a8ba`, APK `FFTT04M_20261001_021554.apk`). `Decoded.coughScore`, persisted in `.phon`,
   shown in the gallery. **Display-only** — AutoReject/CoughVote unchanged, since λ was never validated on the
   (June) mobile codebook + Spectral-Flux fractionation. Old `.phon` files show it after re-decode. Not device-tested.
3. **λ on speech negatives** — held-out participants' counting/vowel clips: λ=0.4 rejects **94.9 %** of speech
   (dominant letter 96.9 %), λ=0 only 61.7 %. Default 0.4 confirmed. Vowels weakest (91.2 %). Detail + table:
   `data/_coswara_holdout_20260929/RESULTS.md` (2026-10-01 section), `speech_lambda.py`.
4. **Decode glob hazard fixed.** The four experimental coswara decodes moved out of `data/codebooks/` (to
   `data/_coswara_holdout_20260929/decodes/` and `data/_pull_stage_20260712/`; `eval_coswara.py` path updated).
   `DecodeStore` + `confirmedLabels` now load oldest-first (newest wins on overlap, was unspecified) and
   DecodeStore logs overlaps. **Decode-only CLI runs still write `<tag>_decoded.json` into `data/codebooks/` —
   move them out right after the run** (done for this session's `speechho_20261001`).

**Open next:** validate coughScore on DEVICE `.phon` decodes before letting AutoReject use it (needs device pull);
refresh the stale 07-07 `p3_decoded.json`/`ALLDATA_decoded.json` with a full decode-all (heavy); out-of-domain
speech/music negatives still unmeasured.

## SESSION 2026-09-30 — autonomous resume-paused-work: the dominant-letter rule was the bottleneck, not the codebook (`coughScore`)
State on arrival: D=`b8e2949`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin, zero java.exe,
`adb devices` empty, nothing stranded. No codebook rebuild this session — `data/codebooks/p3_*.json` and
`phoneme_segment_edits.json` are untouched.

**Finding.** Yesterday's holdout table scored one operating point per head: the dominant letter. Re-scoring
the SAME decodes with a continuous score shows that rule is what caps recall. A real cough recording is
mostly not-cough windows (held-out coswara cough clips decode as `?` 34.7%, DH 17.1%, **V 14.6%, SN 10.2%**),
so "most common letter" discards clips whose coughs are outnumbered.

`coughScore = (cough − respiratory S − λ·other not-cough) / all windows`, cough when > 0:
```
  rule (live codebook)        coswara holdout acc/recall/breath-reject   device manual acc/recall/reject
  dominant letter (09-29)        67.0 / 45.8 / 88.4 %                      88.1 / 69.6 / 96.0 %
  λ = 0                          82.8 / 92.8 / 72.7 %                      77.3 / 89.6 / 72.2 %
  λ = 0.4  (shipped default)     81.4 / 78.2 / 84.6 %                      87.6 / 83.1 / 89.5 %
  λ = 1                          68.0 / 44.7 / 91.4 %                      88.4 / 66.7 / 97.3 %
```
Coswara = 4,930 clips / 1,239 held-out participants (AUC 0.929 at λ=0, 0.921 on the participant half not
used for any threshold). Device = 792 manually-labelled p3 clips. λ=0 is the best cough-vs-breath rule but
calls 39% of device noise and 33% of device voice "cough"; λ=0.4 holds in both domains.

**Shipped:** every decode record now carries `coughScore` (additive field; `-Dcough.lambda=X`, default 0.4,
with the gradle passthrough). Verified: the Kotlin field on a fresh p3 decode gives acc 87.8 / recall 83.1 /
reject 89.7 %, matching the Python sweep. Nothing CONSUMES the field yet — the grid still shows
`inferredLetter`. fatJar rebuilt, jar letter **'X'** (build index 257).

**Yesterday's `auto.win=20` "trade", re-read:** same-AUC codebooks (live 0.929 vs 0.917 at λ=0). The
recall collapse in that table was mostly the dominant-letter rule, not the codebook. Default stays off.

**Limits:** λ was picked on these same two sets; the device clips trained the codebook (optimistic);
coswara negatives are breathing only, so out-of-domain speech/music is unmeasured; GPU HuBERT decodes of
identical inputs differ by ~0.2pp.

**Found in passing, NOT fixed:** `DecodeStore` (RecordingsGrid.kt) and `confirmedLabels`
(PhonemeCodebookCli.kt) both glob every `*_decoded.json` in `data/codebooks/`. The three
`coswara_*_20260929_decoded.json` files left there yesterday (plus `coswara_test_decoded.json`) therefore
override the grid's decode for ~5k coswara clips with whichever file lists last. This session's decodes
were moved to `data/_indomain_check_20260930/` to avoid adding to that. Also `p3_decoded.json` /
`ALLDATA_decoded.json` / `p3_phoneme_fft` date from 07-07/07-08 and predate several codebook rebuilds, so
grid tooltip codes no longer correspond to the live codebook's phoneme numbering — a full decode-all +
`phonemeFft` would refresh them (heavy; not run).

**Open next (none device-gated):** (1) make the grid/any consumer use `coughScore` instead of the dominant
letter; (2) mirror the rule into the mobile decode (M app) — it is three counters per word; (3) validate λ
on a third set with speech negatives; (4) the `*_decoded.json` glob hazard above.
Artifacts: `data/_indomain_check_20260930/` (RESULTS.md, `lambda_sweep.py`, both p3 decodes) and
`data/_coswara_holdout_20260929/roc_holdout.py`.

## SESSION 2026-09-29 — autonomous resume-paused-work: the coswara BREATHING class, measured (cross-domain Pareto win)
State on arrival: D=`d9dc076`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin, no stashes,
zero java.exe, nothing stranded. Picked up the lever `data/_pull_stage_20260712/RESULTS.md` ends on —
**"Next lever: add a real breathing class"** — which is pure desktop work and was never device-gated.

**The gap.** `cleanLabel` has merged breath↔snoring since `59073a7` (2026-07-12), but `AutoLabel.forId`
returned **null** for `coswara__…__breathing-*`, so coswara's **5,488 breathing clips had no label at all**
and contributed nothing to the codebook. The merge had no breath supply to merge. Fixed: AutoLabel maps
coswara `breathing-*` and ESC-50 `breathing` → `breathing`, and `trainLabel` now runs the filename
auto-label through the SAME `cleanLabel` as a manual comment (→ the merged respiratory class `snoring`/`S`)
instead of bypassing canonicalization. Also fixed the two copies of `cleanLabel` in `PhonemeFftCli` and
`UnsupervisedCluster` whose comments claim to mirror `PhonemeCodebookCli`'s but had drifted (no breath
clause) — same duplication trap as the pre-`1a8a59f` vertex-window one-liner.

**New measurement infrastructure** (needed, because auto-labelling coswara makes any coswara eval sample
leak-prone): `-Dexclude.ids=<file>` holds listed clip ids out of TRAINING only (with the gradle
`systemProperty` passthrough — the `813fb93` lesson), and every build now writes
`data/codebooks/<tag>_train_ids.txt` (id⇥label) so any downstream eval can prove leakage = 0.

**Held-out sample** `data/_coswara_holdout_20260929/` — 5,003 clips (2,501 cough + 2,502 breathing) from
the first 1,251 sorted coswara uids having both, held out **by participant**; `wav/` is hardlinks into
ALLDATA (no copy); `eval_holdout.py` + `RESULTS.md` live there. Rebuild the wav dir with:
`python -c "import io,os;[os.link('ALLDATA/'+i+'.wav','data/_coswara_holdout_20260929/wav/'+i+'.wav') for i in [l.strip() for l in io.open('data/_coswara_holdout_20260929/holdout_ids.txt')] if i]"` (from `D:\AndroidProjects`).

**RESULT — same 4,930 test clips, same recipe, only the codebook differs, leakage 0:**
```
  head                          acc     cough-recall  breath-reject  cough-prec
  inferredLetter  BEFORE       62.2%       40.1%          84.5%        72.4%
  inferredLetter  AFTER        67.0%       45.8%          88.4%        79.9%   ← all four improve
  classLabel      before/after 57.8→59.2%  42.7→56.8%     73.0→61.7%   61.3→59.7%
  wholeClipLabel  before/after 55.0→54.5%  62.4→63.2%     47.6→45.8%   54.4→53.9%
```
Held-out breathing clips whose dominant phoneme is the respiratory letter S: **712 → 1,227**. The BEFORE
row reproduces 2026-07-12's numbers on a differently-drawn sample (62.2 vs 62.1%), so the baseline is
independently confirmed. **In-domain cost: none** — rebuild CV 79% / dominant-letter 81% / whole-clip 54%
vs the 07-14 baseline's 80 / 81 / 52% (inside the documented k-means variance).

**Still wrong — carry forward:**
- The histogram head got WORSE at rejecting breath (73.0 → 61.7%) and its breathing→**croup** confusion
  grew 414 → 728 clips. The dominant-letter head sends ZERO breathing clips to croup in either run, so
  this is the histogram classifier mapping breath-flavoured phoneme bags onto croup — not the codebook
  putting breath in a cough cluster.
- **Only 14 breathing clips actually trained.** `AUTO_FRAG_CAP = 2000` is a per-auto-label *fragment*
  budget and breathing clips are long (~140 windows each), so 14 clips ate the whole respiratory budget
  and displaced ~24 shorter ESC-50 clips (auto clips 143 → 119). The whole gain above comes from 14
  participants' breath. Next experiment: spread the same budget over many more clips (per-auto-clip
  window cap) — cheap, same harness.

### Second find, same session: the segment-edit weighting was BOTH inert and booby-trapped
Chased down *why* the 2026-07-14 `segedit.weight` A/B came out null. It was not "too few edits to move a
global CV number" (what [[phoneme-atlas-player-todo]] records) — **zero exemplars were ever applied**:
- The only entry in `phoneme_segment_edits.json` is
  `coswara__0HIgO2Eh…__cough-heavy__…__cough0_1470-3035ms` — a harvested sub-clip with no manual comment,
  and `AutoLabel` gives coswara *cough* recordings no label, so `trainLabel` returns null and the clip is
  **not in the training set at all** (`grep -c` over the new `p3_train_ids.txt`: 0 of 1337).
  Therefore `segEditExemplars == 0` in every rebuild the feature has ever had, including both sides of the
  07-14 A/B. The 81% vs 80% gap it reported was pure run-to-run variance with the flag provably inert —
  reproduced here: weight=8 → 80%, weight=1 → 81%, with the exemplar count printed as 0 both times.
- **And the code path it would have taken was broken.** `repeat(segEditWeight - 1) { labelled.add(FV(label, v)) }`
  added N entries sharing ONE `DoubleArray`, and step 2's `znorm` is **in place** over every entry of
  `labelled` — so a weighted exemplar was z-scored N times. Measured with a synthetic eligible exemplar
  (6 windows on a training `voice` clip, weight 8), then restored:
```
  shared array (old):  z-norm check: max|z| = 11,054,878.2   fragments with max|z|>25: 48   ← 6 windows x 8 copies
  v.copyOf() (fixed):  z-norm check: max|z| = 7.0            fragments with max|z|>25: 0
```
  A "human-confirmed exemplar" became a vector ~1.5 million sigma out, which k-means hands its own
  phoneme(s). CV barely moved (6 of 22,814 fragments), so this would never have shown up as a number —
  it would just have quietly degraded the codebook the moment the user added the edits the TODO asks for.
- Fixed (`v.copyOf()`), plus two guards so neither failure can be silent again: a **z-norm sanity line**
  (`max|z|` + count over 25) printed every build, and a **WARNING** when edited clips are not training
  clips or when no override matches its clip's class letter. The production rebuild now prints
  `WARNING 1 of 1 edited clip(s) are NOT training clips`, which is the true state of this repo.
- **Actionable for the user:** a Tier-B edit only trains when (a) its clip has a manual comment or an
  auto-label AND (b) the override's letter equals that clip's own class letter. Edits on unlabelled
  harvested sub-clips do nothing. Re-measuring `segedit.weight` needs edits that satisfy both.

### Third unit: `-Dauto.win=N` — spend the auto fragment budget on many clips (measured TRADE, default OFF)
The "only 14 breathing clips trained" finding above has an obvious fix: take N evenly-strided windows per
AUTO clip instead of all of them (manual clips untouched; the original window index is preserved so Tier-B
overrides still line up). At N=20 the diversity goes exactly where intended — auto clips **119 → 351**,
breathing clips **14 → 101** — for slightly fewer total fragments (22,772 → 21,394). But on the holdout it
is a re-balance, not a win:
```
  head            acc          cough-recall   breath-reject   prec        (off → auto.win=20)
  inferredLetter  67.0 → 63.6%  45.8 → 32.9%   88.4 → 94.6%   79.9 → 86.2%
  classLabel      59.2 → 64.4%  56.8 → 39.5%   61.7 → 89.4%   59.7 → 78.9%
  wholeClipLabel  54.5 → 60.4%  63.2 → 63.4%   45.8 → 57.5%
  ensemble OR     56.4 → 65.2%  83.8 → 78.1%   28.9 → 52.2%   ← the one clear gain
```
Specificity climbs on every head and cough recall falls just as hard; best-single-head accuracy regresses.
Side effects: the small ESC-50 **sneeze** class thins 1,977 → 775 fragments (all auto classes get
subsampled, and sneeze had few clips), whole-clip CV 54 → 48%. **Default 0 = off**, because the mobile
design wants a high-recall grabber with specificity as a tunable post-filter — trading 13pp of recall
inside the codebook is the wrong place to spend it. Live codebook was rebuilt with the knob OFF.

### Measurement-quality note (applies to every CV number in this file)
Five identical rebuilds of the canonical recipe this session gave **classifier CV 79 / 80 / 81 / 75 / 80%**
while **dominant-letter held at 81–82%**. So the histogram-classifier CV swings ~6pp run to run (k-means
init → different purify routing → different classifier); it is a weak instrument and a 1–2pp difference in
it means nothing. Prefer dominant-letter, or the held-out coswara eval, for any A/B. The live codebook is
the last of those runs (CV 80%, dominant-letter 82%, 256 phonemes); the 75% draw was re-run once for the
production artifact, and both numbers are recorded here rather than the better one being presented alone.

**Codebook state:** `data/codebooks/p3_*.json` were REBUILT (backup:
`data/_backup_pre_breathclass_20260929/`). The live desktop codebook now contains breath in class S and
permanently excludes the 5,003 holdout clips from training — keep passing `-Dexclude.ids` on future
rebuilds so that eval stays honest. Restart the desktop app to pick the new codebook up. fatJar rebuilt, jar letter **'J'** (build index 243).

## SESSION 2026-09-25 — autonomous resume-paused-work: vertex-window precision CLOSED + a `"?"`-stamping bug found
First non-no-op run since 07-16 (the prior 18 were audits). State on arrival was the same as always:
D=`1f7de69`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin (fetched), no stashes, zero
java.exe, `adb devices` empty, `phoneme_segment_edits.json` unchanged since 2026-07-10 (238 bytes, 77
days stale), `device_ingest/` still 827 files. **But "no device data" only gates the field-validation
items — it never gated the `vertex-window precision` follow-up in [[phoneme-atlas-player-todo]], which is
pure desktop code with an existing headless harness. 18 audits in a row called that no-op; it wasn't.**

Commit `1a8a59f` (pushed, jar letter 'C' / build index 236):
- New `VertexWindow.kt` — the vertex→decode-window mapping, previously an inline one-liner **duplicated**
  in `PhonemePlayer.autoDetectSquiggles` and `SquiggleDetectCli` (so the "verification harness" was free
  to drift from what the GUI actually did; it now can't).
- **The documented precision issue, fixed**: windows are 180 ms on a 90 ms hop (50% overlap), so a vertex
  normally sits inside TWO windows with centres 90 ms apart, and nearest-centre was deciding on margins as
  thin as **6 ms** (real case: vertex 1302 ms, centres 1260/1350) — far below the precision of a `-b/2a`
  fit over 10 ms ridge frames accepted at R² ≥ 0.2. Now: restrict to windows that actually CONTAIN the
  vertex, rank by centring, break near-ties (30 ms ≈ 3 ridge frames) on **loudness** — the same peak-RMS
  assumption the manual span-relabel default always used. Tie-break is confined to the containing pool; in
  the fallback pool the nearest candidate can be 100+ ms away, where loudest-wins picks on no evidence.
- **The bigger defect found while measuring it**: `"?"` is the codebook's REJECT marker
  (`PhonemeCodebookCli` — no centroid within the cluster radius), not a phoneme; downstream
  dominant-letter inference filters it straight back out. Nearest-centre would select a `"?"` window and
  `setRange()` then overwrote **every real code in the chirp** with it. Since segment edits feed codebook
  retraining via `-Dsegedit.weight`, that was poisoning the retrain input, not just the display.
- **Measured, not asserted** (`:desktop:squiggleDetect` now prints old-vs-new per event + a summary
  count). Over 35 decoded clips / 673 detected events: **303 unchanged, 212 changed away from a `"?"`
  stamp (31% of all events), 34 real→real refinements, 124 all-`"?"` spans declined** (all already
  uniform, so no GUI behaviour change on those). Invariant checked: the new pick is never `"?"`.
  All 6 changes on the 3 documented repro clips were hand-audited individually.
- Mid-review self-correction worth keeping: the first cut also applied the loudness tie-break in the
  fallback pool, which turned a 127-vs-143 ms centre distance into a "tie". That was wrong for the stated
  reason (the 30 ms band is justified by fit jitter near the 90 ms decision boundary, nothing else) and
  was tightened before commit.

**Nothing else changed** — no codebook rebuild was run (that mutates `data/codebooks/*.json`, outside git),
so `phoneme_segment_edits.json` and all decodes are untouched. Re-measuring the `segedit.weight` before/after
still waits on more Tier-B edits, and the device-data items are still gated on the user.

## SESSION 2026-09-20 — autonomous resume-paused-work: no-op (18th consecutive) — all repos clean/pushed, no device connected
No HANDOFF entries for 09-18/09-19 (same skip-when-unchanged pattern). Today: D=`2927328`, M=`60f7286`,
L=`942d9c7`, all clean/up-to-date with origin (fetched), no stashes, zero java.exe, `adb devices` empty,
`phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes, now 72 days stale),
`device_ingest/` still 827 files (newest 07-08 mtime), `data/usb_import/` untouched since 06-18. Same
conclusion as the last 17 audits — no stranded work, no commits besides this entry, nothing actionable
without new device data (see [[phoneme-atlas-player-todo]] / [[on-device-cough-vote]] gating).

## SESSION 2026-09-17 — autonomous resume-paused-work: no-op (17th consecutive) — all repos clean/pushed, no device connected
D=`12f36e0`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin (fetched), no stashes, zero
java.exe, `adb devices` empty, `phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes,
now 69 days stale), `device_ingest/` still 827 files (newest 07-12), nothing new under `data/`. Same
conclusion as the last 16 audits — no stranded work, no commits besides this entry, nothing actionable
without new device data.

## SESSION 2026-09-16 — autonomous resume-paused-work: no-op (16th consecutive) — all repos clean/pushed, no device connected
No HANDOFF entries for 09-05 through 09-15 (11-day gap, same skip-when-unchanged pattern). Today:
D=`79a872d`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin (fetched), no stashes, zero
java.exe, `adb devices` empty, `phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes,
now 68 days stale), nothing new under `data/` or `device_ingest/`, memory dir untouched since 09-04.
(Workspace-root mtime bumped 09-15 with no new child entry — a transient file, nothing to act on.)
Same conclusion as the last 15 audits — no stranded work, no commits besides this entry, nothing
actionable without new device data.

## SESSION 2026-09-04 — autonomous resume-paused-work: no-op (15th consecutive) — all repos clean/pushed, no device connected
D=`f9f958f`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin, zero java.exe, `adb devices`
empty, `phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes, now 56 days stale).
Same conclusion as the last 14 audits — no stranded work, no commits, nothing actionable without new
device data.

## SESSION 2026-08-31 — autonomous resume-paused-work: no-op (14th consecutive) — all repos clean/pushed, no device connected
No HANDOFF entries for 08-14 through 08-30 (17-day gap) — same "skip the entry when nothing changed"
pattern as the 07-25–08-10 gap noted on 08-11; the daily cron is presumed still firing. Today:
D=`6060041`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin, zero java.exe, `adb devices`
empty, `phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes, now 52 days stale).
Re-confirmed [[phoneme-atlas-player-todo]] and [[on-device-cough-vote]] follow-ups remain gated on
field-validation, expensive research, or user preference — no change from the last 13 audits. No
stranded work, no commits.

## SESSION 2026-08-13 — autonomous resume-paused-work: no-op (13th consecutive) — all repos clean/pushed, no device connected
Same as every run since 07-12: D=`df06d9d`, M=`60f7286`, L=`942d9c7`, all clean/pushed, zero java.exe,
`adb devices` empty, `phoneme_segment_edits.json` unchanged since 2026-07-10 (238 bytes, now 34 days
stale). No stranded work, nothing new to act on. No commits.

## SESSION 2026-08-12 — autonomous resume-paused-work: no-op (12th consecutive) — all repos clean/pushed, no device connected
D=`41308d3`, M=`60f7286`, L=`942d9c7`, all clean/up-to-date with origin, zero java.exe, `adb devices`
empty, `phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes, now 33 days stale).
Re-checked `phoneme-atlas-player-todo` and `on-device-cough-vote` follow-ups (a)/(b)/(d) — all still
gated on field-validation, expensive research, or user preference. No stranded work, no commits.

## SESSION 2026-08-11 — autonomous resume-paused-work: no-op (11th consecutive) — all repos clean/pushed, no device connected
No HANDOFF entries exist for 07-25 through 08-10 (18-day gap) even though the daily cron
(`resume-paused-work`, 0209 ET) shows `enabled:true` throughout — consistent with the 07-16 instruction to
stay terse on repeat no-ops, taken to its conclusion (skip the entry entirely when nothing changed), not
evidence the task stopped firing. Today: D=`c0d4c2c`, M=`60f7286`, L=`942d9c7`, all clean/pushed, zero
java.exe, `adb devices` empty, `phoneme_segment_edits.json` still unchanged since 2026-07-10 (238 bytes,
now 32 days stale). Re-checked `phoneme-atlas-player-todo` and `on-device-cough-vote` follow-ups (a)/(b)/(d)
— all still explicitly gated on field-validation, expensive research, or user preference, same as every
prior run since 07-12. No stranded work, no commits.

## SESSION 2026-07-24 — autonomous resume-paused-work: no-op (10th consecutive) — all repos clean/pushed, no device connected
Same as every run since 07-12: D=`7fe2b98`, M=`60f7286`, L=`942d9c7`, all clean/pushed, zero java.exe,
`adb devices` empty, `phoneme_segment_edits.json` unchanged since 2026-07-10 (still 238 bytes). No stranded
work, nothing new to act on. No commits.

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
