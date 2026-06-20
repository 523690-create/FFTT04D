# On-device phoneme decode (M app, Tier-1) — plan

Status: **planned** (2026-06-20). Goal: the desktop batch decode (fractionate → 13-dim fragment
features → nearest-phoneme word against the shipped codebook) runs **on the phone** — for the backlog
of existing recordings *and* on-the-fly for each new capture. See [[analysis-phoneme-direction]],
PHONEME_CODEBOOK.md.

## Why it's close
The M app already shares the DSP core with the desktop, in package `com.example.FFTT04M.cough`:
- **`WholeClipFeatures.kt` is byte-identical to the desktop's** (diff = one import line). The 14-dim
  vector — `crest, zcr, onset_sharp, env_peak_ratio, active_frac, centroid_hz, flatness, rolloff85_hz,
  bandwidth_hz, hf_ratio, q_ratio, pitch_strength, syllabic, spectral_crest` — is the same in the same
  order. **So a desktop-built codebook decodes correctly on-device** (the linchpin).
- `MfccExtractor`, `FftFeatureExtractor`, `CoughAnalyzer` also present.

The codebook (`p3_phonemes.json`) is tiny — 127 phonemes × 13 doubles + a 13-dim mean/std ≈ a few KB.

## What to port (small, pure-Kotlin, no Android deps)
1. **Spectral-Flux Onset fractionator** — the M app has no `fractionation` package. Copy
   `SpectralFluxOnset` from `FFTT04D/desktop/.../fractionation/` (one file, STFT + flux peak-picking).
2. **Decoder** (~60 lines, lift from `PhonemeCodebookCli`): per fragment → `fragVec` (the 13-dim =
   WholeClipFeatures minus `syllabic`, keep `spectral_crest`) → z-norm with the codebook's mean/std →
   nearest centroid; `> radius` ⇒ `?`. Produce **word** (ordered codes), **histogram**, **inferred
   letter**.
3. **Codebook loader** — read `p3_phonemes.json` from APK `assets/` (or app storage when updated).
4. **`AutoLabel.forId`** — trivial copy (filename-prefix rules), if device clips carry source ids.

## On-the-fly (each new recording)
After save, kick a background coroutine: decode the clip → persist `{word, inferredLetter, histogram,
codebookVersion}` next to the recording (Room row or sidecar JSON) → surface in the recordings list
(e.g. a colored class chip + the word, mirroring the desktop grid's ≈ line). Cheap enough to feel
instant (see Performance).

## Backlog (existing recordings)
A resumable batch pass (WorkManager or a coroutine on the recordings screen): iterate recordings with
no decode (or an older `codebookVersion`), run the same pipeline, write results. Skip-done so it
resumes; throttle to keep the UI smooth.

## Performance on Tier-1
No GPU, no HuBERT, no ONNX. Per clip: one STFT pass (flux) + ~20 fragments × cheap time/spectral
features + nearest-of-127-centroids (13-dim Euclidean). Milliseconds per clip on low-end hardware —
fine both on-the-fly and for a background backlog sweep.

## Codebook delivery + versioning
- Ship `p3_phonemes.json` as an APK asset; allow override from app storage so it can be **updated**
  without a rebuild.
- Stamp the codebook with a `version`; store it on each decode so an updated codebook can re-decode
  only stale rows.
- Push updates over the **existing desktop↔device transfer** (the USB "offer" path) — desktop builds
  the codebook, offers the new JSON, phone swaps it in.

## Re-learning loop (the point)
On-device, show the decoded word + a **confirm / relabel** control (like the desktop correct/error
toggle). Confirmed/corrected labels sync back to the desktop on the next transfer → fold into
`manual_comments.json` → rebuild the (cross-dataset, balanced) codebook → ship the new version back to
phones. The phone both *consumes* the codebook and *feeds* its growth.

## Risks / notes
- **DSP parity must stay in sync** across M and D (separate dirs, shared git remote). `WholeClipFeatures`
  is identical today; verify `FFTUtils` (separately imported) matches too, and keep the ported
  fractionator/decoder in lockstep — any drift silently corrupts decodes against a shared codebook.
  Long-term fix: a shared pure-Kotlin DSP module; near-term: copy + a parity test.
- The codebook is currently tagged `p3` but is cross-dataset + auto-labelled; rename the shipped asset
  to something neutral (e.g. `codebook.json`) when wiring the phone.
