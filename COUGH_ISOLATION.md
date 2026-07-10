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

- [x] **Signals 4+5 added (squiggle + speech cues), v2 fuser measured (2026-07-10, commit `63ac5ba`).**
      Sidestepped the id-space join entirely: the harvest segments are ALREADY isolated WAVs and the
      segment filename == the CSV id, so `HarvestDspCli` (`:desktop:harvestDsp`, all cores, no GPU, 140s
      for all 175,483 segments) computes squiggle (MultiRidgeExtractor 300–2000 Hz: maxR²+count) and
      speech cues (WholeClipFeatures pitch/flatness/syllabic) DIRECTLY on each segment →
      `harvest_dsp.csv`. `coughGate` then compares v1 vs v2 on the SAME rows:

  ```
  -- DSP subset (175,483), apples-to-apples --      acc    F1     FP-hardneg  recall
    FUSED v1 (head+wav+forest+hallmark)             78.7%  0.819    16.5%      76.0%
    FUSED v2 (+squiggle+speech)                     79.2%  0.824    16.4%      76.7%
    FUSED v2 + hard speechVeto                       70.9%  0.727    12.2%      61.2%
  -- FUSED v2 threshold sweep (the deployable knob) --
    thr 0.50  FP 16.4%  recall 76.7%  precision 89.0%
    thr 0.70  FP  8.7%  recall 65.7%  precision 92.9%
    thr 0.80  FP  5.3%  recall 56.0%  precision 94.8%
    thr 0.90  FP  1.6%  recall 31.6%  precision 97.1%
  ```

  **Honest finding (the reinvention result):** adding the two "orthogonal" DSP signals barely moved the
  aggregate metrics (+0.5% acc). Two reasons, both worth remembering: (a) **squiggle is NOT cough-
  specific post-segmentation — breath chirps too** (a breathing-deep segment scored squiggleCount 4 @
  R² 0.27), so the ridge fires on the same expulsive/turbulent bursts the segmenter already selected;
  (b) pitch/flatness/syllabic as soft LR inputs add little. The **hard `speechVeto` is too blunt** (cuts
  FP 16→12% but craters recall 77→61%) — DON'T deploy it as a veto; its cues already feed the LR.
  THE ACTUAL WIN is the **v2 stacked fuser + a tunable threshold**: one continuous operating point that
  dials FP from 16%→5%→1.6% (precision 89%→95%→97%) as recall trades off — strictly dominating the fixed
  v1 verdict and matching/beating the head∧wav consensus's 6.1% FP at a chosen point (thr≈0.78) while
  staying tunable. **Deploy v2 at thr≈0.7–0.8 when "don't call speech a cough" matters; ~0.5 for recall.**
  The deeper structural lesson is unchanged: the ~1/3 `found_in_other` FP pile is a SEGMENTATION artifact
  — the real fix is to gate DURING streaming segmentation on raw audio, not re-filter isolated segments.
  Saved: `data/codebooks/cough_gate.json` (10-dim v2 fuser), `cough_harvest/cough_gate.csv` (175,483 rows,
  now with squiggle/speech columns + pFused + speechVeto + verdict).
- [ ] Wire real Stage-B inference (`CoughGate` loads all models) into a GUI button + a re-harvest that
      gates DURING streaming segmentation over RAW audio (the true fix for post-seg over-fire).
- [ ] User-domain override: p3 multi-class letter authoritative for user voice.

See memory `cough_isolation_ensemble`.
