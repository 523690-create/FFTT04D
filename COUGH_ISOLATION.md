# Cough Isolation — multi-signal stacked-consensus design

**Goal:** isolate genuine coughs and separate them from speech and extraneous noise, using several
independent methods *simultaneously* and fusing them. Grounded in every measured finding to date
(see memory `cough_discriminator_clustering`, `hubert_gold_standard`, `squiggle_sweep`).

## Signals (independent by construction)

| # | Signal | Source / file | Measured (per-segment unless noted) | Cost |
|---|--------|---------------|-------------------------------------|------|
| 1 | **HuBERT head** P(cough) | `data/codebooks/cough_head_ALLDATA.json` (768→2 LR on mean-pooled HuBERT) | FP 13.7% on hard-neg, bag-recall 89.4%, F1 0.81 | 377 MB ONNX, GPU |
| 2 | **Wavelet-image gate** P(cough) | linear model on `<wav>.jpg` CWT (HarvestClassifyCli) | FP 17.5%, bag-recall 77.8%, F1 0.82; r=0.42 vs head | cheap (jpg already on disk) |
| 3 | **Hallmark units** hit/count | `data/codebooks/cough_hallmark_units.json` (256 centroids, 77 hallmark) | "≥1 hallmark ⇒ cough" P 85.1% / R 83.6%; language-agnostic, interpretable | medium (HuBERT windows) |
| 4 | **Squiggle** ridge evidence | `MultiRidgeExtractor` 300–2000 Hz parabola, max R² / count | bronchitis-chirp specific; cheap | cheap DSP |
| 5 | **Speech veto** | `SpeechRejector` (pitch strength + spectral flatness) + `WholeClipFeatures.syllabic_mod` (3–8 Hz env rhythm) | language-agnostic; strong on voiced/counting speech | cheap DSP |

## Architecture — two stages

**Stage A (recall, raw stream):** `CoughSegmenter` energy/onset segmentation proposes candidate spans;
be permissive. `CoughForest` belongs ONLY here (raw-stream context), NEVER as a post-seg filter —
it over-fires 77.9% on already-isolated bursts (measured). Precision is Stage B's job.

**Stage B (precision, per candidate):** compute signals 1–5 simultaneously, then **fuse with a trained
stacked logistic regression** over the per-signal probabilities — NOT naïve voting. A hard speech/noise
veto overrides the fused score only when pitch+tonality+syllabic rhythm are jointly unambiguous.

Fusion feature vector (v1): `[pHead, pWavelet, pForest, hallmarkHit, ln(1+nHallmarkWindows),
squiggleMaxR2, squiggleCount, pitchStrength, spectralFlatness, syllabicMod]`. z-normalized → softmax LR.

## Why stacking (not voting)

- Learners are representation-diverse: head↔wavelet correlate only r=0.42; hallmark resolves 61.5% of
  their disagreements. Independent errors cancel → ensemble > any single model.
- Graceful degradation: drop HuBERT (no 377 MB model / no GPU) → wavelet+hallmark+squiggle+speech still
  give a competent on-device gate (the wavelet gate alone ≈ head binary F1).
- Interpretable: each verdict carries which signals fired (unlike a monolithic deep classifier).
- User-voice OOD guard: the ALLDATA head scores the user's own voice P(cough) 0.8–0.99 (false). For
  user-domain clips the p3 multi-class letter (user-inclusive) is authoritative; the head may only ADD
  a rejection, never veto. Encode this as a domain flag input / override in the fuser.

## Training / validation without GPU re-burn

Per-segment scores for signals 1–3 already exist on all 175,483 harvested segments:
`cough_harvest/harvest_compare.csv` (pWavelet,pHead,srcLabel), `harvest_forest.csv` (pForest),
`harvest_hallmark.csv` (hallmarkHit,nHallmarkWindows). Join by `id`. Weak labels from filename metadata:
**hard negatives are trustworthy** (breathing/vowel/counting/urban8k DENY cough → honest specificity),
positives are bags (a "cough" clip contains a cough *somewhere*; ~17.8% of cough_confirmed segments are
pre/post-cough phonemes → noisy positive, use bag-aware recall).

`:desktop:coughGate` (CoughGateCli) joins these CSVs, trains the stacked LR (`WholeClipClassifier`
softmax LR reused), 5-fold CV, reports fused vs each single method (FP on hard-neg, bag recall), writes
`cough_harvest/cough_gate.csv` (id + per-signal + pFused + verdict + reason) and saves
`data/codebooks/cough_gate.json`.

## Status / next steps

- [x] Design + fusion core `CoughGate.kt` (feature assembly + transparent default rule + speech veto).
- [x] `CoughGateCli` join+train+eval on existing CSVs (no GPU) — REPORT fused vs singles. Wired as
      gradle task `:desktop:coughGate` (was missing from `build.gradle.kts` — CLI existed but had no
      task registered, likely why the prior session paused mid-work). Ran 2026-07-10 on the full
      175,483-segment harvest (`gate.harvest=D:\AndroidProjects\cough_harvest`):

  ```
  method            acc    cough-F1   FP-on-hard-neg   recall
    FUSED (stacked)     78.7%   0.819        16.5%           76.0%
    head alone          76.6%   0.796        15.5%           72.0%
    wavelet alone       65.3%   0.668        17.6%           55.3%
    hallmark alone      70.5%   0.745        25.0%           68.0%
    head∧wav consensus  63.0%   0.607         6.1%           45.1%
  ```

  **Finding:** the v1 fuser (head+wavelet+forest+hallmark only, no squiggle/speech cues yet) beats
  every single method on accuracy/F1/recall, confirming the stacking premise — but its FP-on-hard-neg
  (16.5%) is *slightly worse* than head-alone (15.5%), not better. The 2-way head∧wavelet consensus
  still has by far the lowest FP (6.1%) but at a big recall cost (45.1%). So v1 stacking optimizes
  accuracy, not specificity — if the goal is "never call speech/breath a cough" specifically, consensus
  (or a fuser retrained/thresholded to weight specificity) beats the current softmax-LR fuser. Revisit
  once signal 4 (squiggle) + speech-veto features are added — they're expected to help specificity more
  than the current 4 signals (forest and hallmark both key on burst/acoustic-unit content already
  correlated with head/wavelet; squiggle+speech cues are the two signals actually orthogonal to voice
  content). Saved: `data/codebooks/cough_gate.json` (fuser weights), `cough_harvest/cough_gate.csv`
  (175,483 rows: id + per-signal + pFused + verdict).

- [ ] Add signal 4 (squiggle): **id-space mismatch found, NOT a simple join.** The squiggle sweep's
      manifest `id` column is the PARENT clip's id (e.g. one whole ALLDATA/coswara recording), while a
      harvested segment's id encodes a sub-span suffix within that parent (e.g.
      `..._India__cough0_0-305ms`). Confirmed by direct lookup: the harvest id above has ZERO manifest
      rows, but stripping its `__cough0_0-305ms` suffix to recover the parent id finds 2+ squiggle rows
      with their own `[startMs,endMs]` in the *parent's* timeline. A correct per-segment join must (a)
      parse the segment id's trailing `_coughN_STARTms-ENDms` (or equivalent) offset, (b) look up
      squiggle events for the parsed parent id, (c) keep only squiggle spans overlapping
      `[START,END]` (or within some pad), then aggregate max-R²/count over just those. Also coverage is
      partial: the squiggle sweep only covered `cough_confirmed` + `ALLDATA` + `true_cough` + legacy
      sources (per [[squiggle-sweep]]), NOT the `cough_found_in_other` bucket, so ~1/3 of harvest
      segments (the "found in other" ones) will never have a squiggle signal — those rows must degrade
      gracefully (NaN → 0, same pattern as the other signals). Left unimplemented rather than rushed:
      wrong offset parsing would silently corrupt a training signal. Next session: write the id-parse +
      overlap-join as its own small utility, spot-check 5-10 known segments by hand before trusting it.
- [ ] Speech-cue features (pitch/flatness/syllabic) per segment — needs a per-segment DSP pass over the
      harvest WAVs (WholeClipFeatures-style); not yet run at segment granularity (only computed on
      whole clips elsewhere in the codebase).
- [ ] Wire real Stage-B inference (`CoughGate` loads all models) into a GUI button + a re-harvest that
      gates DURING streaming segmentation over RAW audio (the true fix for post-seg over-fire).
- [ ] User-domain override: p3 multi-class letter authoritative for user voice.

See memory `cough_isolation_ensemble`.
