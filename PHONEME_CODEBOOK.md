# Phoneme codebook — supervised, fragment-level (spec)

Status: **building** (2026-06-19). Supersedes the whole-clip MVP in `MANUAL_CODEBOOK_PLAN.md`.

## Idea
A clip is a **word** = a sequence of **phonemes**. A phoneme is a cluster of acoustically-similar
**fragments** (sub-segments from fractionation). Phonemes are **supervised**: derived per manual
label, so each phoneme is coded `[letter][number]` where the letter is the label it came from and the
number is the cluster index within that label.

- Fragmenter: **Spectral Flux Onset** (best HuBERT agreement among the fast methods; boundaries
  already on disk in `data/fractionation/<dataset>/Spectral_Flux_Onset_segments.jsonl`).
- Fragment feature: **13-dim** = `WholeClipFeatures` minus `syllabic` (a whole-clip 3–8 Hz rhythm
  feature, meaningless on a <333 ms fragment). z-normalized over the fragment population.
- Total phonemes **K=128**, distributed across labels **√-proportionally** to each label's fragment
  count (so an over-represented label like bronchitis gets more phonemes than a rare one but stops
  dominating the codebook's catchment), capped by each label's fragment count.
- The build **aggregates labelled clips across all datasets** (merges every
  `data/fractionation/<dataset>/Spectral_Flux_Onset_segments.jsonl` and indexes wavs from each dataset
  dir), so labelling any dataset — p3, ALLDATA, … — feeds one shared codebook. Decode stays per-target.

## Label → letter map (extensible)
Built-in: `snoring→S, bronchitis→B, noise→N, dry→D, dry hacking→DH`. Overridable/extendable via
`data/codebooks/label_letters.json` (`{ "wet cough": "W", … }`). Labels are normalized first
(lowercase/trim + synonyms, e.g. snore=snoring). `?` (no number) is reserved for a fragment whose
nearest phoneme is beyond its radius → "not in our codebook".

## Build (from the labeled clips of a dataset, start with p3)
1. Load `manual_comments.json` → id→label (normalized) for labeled clips only.
2. Load the Spectral-Flux fragments → id→[(startMs,endMs)].
3. For each labeled clip: decode wav; per fragment compute the 13-dim vector. Tag each with the clip's label.
4. z-normalize all fragment vectors (store mean/std in the codebook).
5. Per label, **k-means** its fragments into N_label clusters (N_label ∝ fragment share, Σ=128, min 1).
   Each cluster → phoneme `{code:"S3", letter:"S", label:"snoring", centroid[13], radius}` where
   radius = e.g. 90th-percentile intra-cluster distance (the "?" threshold).
6. Write `data/codebooks/<dataset>_phonemes.json`.

## Decode (any clip, incl. unlabeled p3)
1. Fragments → 13-dim → z-normalize (codebook's mean/std).
2. Each fragment → nearest phoneme centroid; if distance > that phoneme's radius → `?`.
3. **word** = ordered phoneme codes (e.g. `S1 S3 ? B2 S1`); **histogram** = {code→count}.
4. Inferred label = dominant letter by histogram weight (ties / mostly-`?` → uncertain).
5. Write `data/codebooks/<dataset>_decoded.json`: id → {word, histogram, inferredLetter, confidence}.

## UI (next build after the engine)
- New per-clip field (the decoded word + inferredLetter) shown in the **Comments grid cell**, in a
  distinct **text color** (e.g. by inferred letter), separate from manual ✍ and auto metadata.
- For auto-decoded (no manual comment) clips: a **correct / error toggle** in the cell, written to
  `data/codebooks/decode_feedback.json` (id→bool). Feeds the re-learning loop (corrected labels become
  new training labels; "error" flags clips to relabel).

## Workflow
1. Label p3 clips (manual comments). 2. Build codebook from the labeled ones. 3. Decode the *unlabeled*
p3 clips. 4. Review the colored words + flag correct/error. 5. Fold corrections back → rebuild. 
Headless `:desktop:phonemeCodebookCli` does steps 2–3; the grid does 4. See [[fractionation-validation-hubert]].
