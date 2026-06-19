# Manual-comment codebook — plan

Status: **planned** (2026-06-19). A second codebook built **only** from human-labeled clips, to sit
alongside the existing unsupervised one and serve the re-learning loop.

## Why a second codebook
The current codebook (`AcousticUnitDiscovery.kt`) is **unsupervised**: every clip → one 14-dim
`WholeClipFeatures` vector → k-means → cluster ids. No human labels involved. We've now started
collecting **manual comments** (`data/manual_comments.json`, `id → comment`, written from the
recordings-grid right-click "Add manual comment"). Those are ground-truth-ish human labels. A
codebook built from *only* the commented clips gives:
- a **curated/supervised** vocabulary keyed by what the human actually called each sound;
- a yardstick to score the unsupervised clusters against (do cluster ids line up with human labels?);
- the seed of a **re-learning loop**: human labels → prototypes → classify the rest → review → relabel.

## Inputs (all already exist)
- `data/manual_comments.json` — `id → comment` (the labels).
- The loaded `recordings` (id → wav), incl. ALLDATA and device imports.
- Feature extractors: `WholeClipFeatures` (14-dim, what AUD uses), `MfccExtractor` (per-frame),
  the 7 fractionation methods (sub-segments), and `HubertKMeansUnits` embeddings (richest).

## Label hygiene (do first)
Manual comments are free text. Before they're usable as labels:
1. **Normalize** → canonical label: lowercase, trim, map synonyms (e.g. "wet cough"/"productive" →
   `cough_wet`). Keep a small alias map; everything else passes through as-is.
2. Optionally split a comment into **tags** (multi-label) vs a single class — start single-class
   (the dominant token) for simplicity.
3. Report label histogram (how many clips per label) — many labels will have N=1–3.

## Approach (build order)
**A. Whole-clip prototypes (MVP, reuse AUD path).**
- For each commented clip: `WholeClipFeatures` → 14-dim vector (z-score normalize across the set).
- Group by canonical label; per label compute the **centroid** (+ covariance/spread if N≥3).
- Codebook = `{label → centroid[14], n, spread}`. Classify any new clip by nearest centroid
  (Mahalanobis if spread available, else Euclidean), with a distance threshold → "unknown".
- Export `data/codebooks/manual_codebook.json`.

**B. Sub-unit ("phoneme") prototypes (build on fractionation).**
- Fractionate each commented clip (Feature Cluster Boundaries or HuBERT) → sub-segments.
- Featurize each sub-segment (MFCC+Δ mean/std, or HuBERT frame-mean) → per-label sub-unit set →
  cluster within each label → per-label sub-unit prototypes. Richer than whole-clip; needs more N.

**C. HuBERT-embedding prototypes (best quality).**
- Per commented clip: mean (or attentive-pool) the HuBERT `[T,768]` embedding → 768-dim vector →
  per-label centroid. Highest fidelity; GPU already wired.

Ship A first (smallest, reuses `AcousticUnitDiscovery`/`WholeClipFeatures`), then C, then B.

## Integration
- New desktop button **"Build Codebook (manual)"** next to "Discover Codebook", and/or a headless
  `:desktop:manualCodebookCli`. Reads `manual_comments.json`, builds A, writes the JSON, prints the
  label histogram + per-label N.
- Reuse the k-means/feature plumbing in `AcousticUnitDiscovery.kt`; the only new piece is
  **grouping by label instead of clustering blind**, plus the label-normalizer.

## Validation / the re-learning loop
1. Build manual codebook A from the commented clips.
2. Cross-tab the **unsupervised cluster id** vs the **manual label** over the commented clips
   (purity / NMI) — does the blind clustering already separate the human classes?
3. Classify *uncommented* clips by nearest manual centroid → surface low-confidence ones in the grid
   for the human to comment → retrain. That's the loop.

## Open questions
- Single-label vs multi-label per comment (start single).
- Min N per label to form a usable prototype (suggest ≥3; below that, keep as exemplars not centroids).
- Distance threshold for "unknown" (tune on held-out commented clips).
- Whether to weight by clip duration / cough phase.

## Status of related pieces
- Manual comments capture: **done** (recordings grid → right-click → Add manual comment →
  `data/manual_comments.json`). See [[fractionation-validation-hubert]] for the fractionation methods
  this can build on, and `AcousticUnitDiscovery.kt` / `WholeClipFeatures.kt` for the existing path.
