package com.example.FFTT04M.desktop

import kotlin.math.ln

/**
 * Multi-signal cough-isolation gate (see COUGH_ISOLATION.md). Fuses several INDEPENDENT cough-vs-
 * {speech,noise} signals into one calibrated verdict — the reliable way to separate a genuine cough
 * from speech/breath/noise post-segmentation, where any single DSP heuristic over-fires (measured:
 * CoughForest FP 77.9% on isolated bursts). Diversity is the point: HuBERT-head and the wavelet gate
 * correlate only r=0.42, and the hallmark units resolve 61.5% of their disagreements.
 *
 * This class is the shared inference-time contract (desktop + mobile). The stacked fuser itself is a
 * small logistic regression over these signals, trained by [CoughGateCli] on the already-computed
 * per-segment scores and serialized in [WholeClipClassifier] format to data/codebooks/cough_gate.json.
 */
object CoughGate {

    /** Canonical fusion feature order. CLI v1 trains the fuser on the first five (all derivable from
     *  the existing harvest CSVs with NO GPU re-burn); squiggle + speech cues are appended once the
     *  per-segment DSP join lands (they are already carried on [Signals] for inference). */
    val FEATURES = listOf(
        "pHead", "pWavelet", "pForest", "hallmarkHit", "lnHallWin",
        "squiggleMaxR2", "lnSquiggleCount", "pitch", "flatness", "syllabic",
    )

    /** Per-candidate evidence. NaN for a probability means "this model wasn't run" → treated as 0
     *  (absent evidence), so the gate degrades gracefully when e.g. the 377 MB HuBERT model is absent. */
    data class Signals(
        val pHead: Double = Double.NaN,      // HuBERT head P(cough)
        val pWavelet: Double = Double.NaN,   // CWT-image linear gate P(cough)
        val pForest: Double = Double.NaN,    // CoughForest P(cough) — raw-stream signal, weak post-seg
        val hallmarkHit: Boolean = false,    // ≥1 cough-hallmark acoustic unit fired
        val nHallWindows: Int = 0,           // how many windows hit a hallmark unit
        val squiggleMaxR2: Double = 0.0,     // best 300–2000 Hz parabolic-ridge fit in the span
        val squiggleCount: Int = 0,          // number of ridge chirps detected
        val pitch: Double = 0.0,             // normalized autocorrelation pitch strength (speech cue)
        val flatness: Double = 1.0,          // spectral flatness (low = tonal/voiced = speech cue)
        val syllabic: Double = 0.0,          // 3–8 Hz envelope-modulation fraction (speech rhythm cue)
    ) {
        fun value(name: String): Double = when (name) {
            "pHead" -> pHead.orZero()
            "pWavelet" -> pWavelet.orZero()
            "pForest" -> pForest.orZero()
            "hallmarkHit" -> if (hallmarkHit) 1.0 else 0.0
            "lnHallWin" -> ln(1.0 + nHallWindows)
            "squiggleMaxR2" -> squiggleMaxR2
            "lnSquiggleCount" -> ln(1.0 + squiggleCount)
            "pitch" -> pitch
            "flatness" -> flatness
            "syllabic" -> syllabic
            else -> 0.0
        }

        fun vector(features: List<String> = FEATURES) = DoubleArray(features.size) { value(features[it]) }

        private fun Double.orZero() = if (isNaN()) 0.0 else this
    }

    /**
     * Cheap, language-agnostic speech/noise veto: voiced (pitched) AND tonal (low flatness) AND
     * syllabically modulated (3–8 Hz) is speech/counting, never a single cough. Applied as a HARD
     * override on top of the fused probability so an obvious utterance can't be admitted by the models.
     */
    fun speechVeto(s: Signals, pitchThr: Double = 0.55, flatThr: Double = 0.18, syllThr: Double = 0.35): Boolean =
        s.pitch >= pitchThr && s.flatness < flatThr && s.syllabic >= syllThr

    /** Human-readable trace of which signals fired, for an interpretable verdict. */
    fun reason(s: Signals): String {
        val parts = ArrayList<String>()
        if (!s.pHead.isNaN()) parts += "head=${"%.2f".format(s.pHead)}"
        if (!s.pWavelet.isNaN()) parts += "wav=${"%.2f".format(s.pWavelet)}"
        if (s.hallmarkHit) parts += "hallmark×${s.nHallWindows}"
        if (s.squiggleCount > 0) parts += "squiggle×${s.squiggleCount}@R²${"%.2f".format(s.squiggleMaxR2)}"
        if (speechVeto(s)) parts += "SPEECH-VETO(pitch=${"%.2f".format(s.pitch)})"
        return parts.joinToString(" ")
    }
}
