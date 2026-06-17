package com.example.FFTT04M.desktop.fractionation

/**
 * Method 6 — HuBERT K-Means Units (ONNX-gated).
 * Requires an ONNX Runtime dependency and a hubert_base.onnx model file — mirrors the GPU-optional
 * pattern in GpuFft.kt. Returns an empty list with a descriptive message when unavailable.
 *
 * Full implementation deferred until ONNX Runtime is wired in (see SOUND_FRACTIONATION.md §3).
 * The UI button checks [available] and shows an informational dialog when false.
 */
object HubertKMeansUnits : Fractionator {

    override val name = "HuBERT K-Means Units"

    /** True when onnxruntime JAR and a hubert_base.onnx model are both present. */
    val available: Boolean get() = false   // TODO: probe ONNX classpath + model file

    val unavailableReason: String
        get() = "ONNX Runtime not present. Add onnxruntime dependency and place hubert_base.onnx in " +
                "desktop/native/hubert/ to enable this method."

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        // Fallback: whole clip as one segment so callers never crash
        val durMs = (x.size.toDouble() / sr * 1000).toInt()
        return listOf(Segment(0, durMs, label = "unavailable-ONNX-not-present"))
    }
}
