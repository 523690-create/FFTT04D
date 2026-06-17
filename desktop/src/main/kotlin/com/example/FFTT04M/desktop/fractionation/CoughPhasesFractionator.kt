package com.example.FFTT04M.desktop.fractionation

import com.example.FFTT04M.desktop.cough.CoughPhases
import com.example.FFTT04M.desktop.cough.CoughSegmenter

/**
 * Method 4 — Cough Phases.
 * Wraps the existing CoughSegmenter + CoughPhases pipeline: first detect cough events with the
 * energy-envelope segmenter, then split each event into its T1/T2/T3 phases.
 */
class CoughPhasesFractionator : Fractionator {

    override val name = "Cough Phases"

    private val segmenter = CoughSegmenter()
    private val phases = CoughPhases()

    override fun fractionate(x: FloatArray, sr: Int): List<Segment> {
        if (x.isEmpty()) return emptyList()
        val events = segmenter.segment(x, sr)
        if (events.isEmpty()) return listOf(Segment(0, (x.size.toDouble() / sr * 1000).toInt()))

        val segments = mutableListOf<Segment>()
        for (ev in events) {
            val pf = phases.detect(x, ev.startSample, ev.endSample, sr)
            val evStartMs = (ev.startSample.toDouble() / sr * 1000).toInt()

            // T1 (inspiratory)
            val t1EndMs = evStartMs + (pf.t1Sec * 1000).toInt()
            if (t1EndMs > evStartMs) segments.add(Segment(evStartMs, t1EndMs, "T1-inspiratory"))

            // T2 (compressive)
            val t2EndMs = t1EndMs + (pf.t2Sec * 1000).toInt()
            if (t2EndMs > t1EndMs) segments.add(Segment(t1EndMs, t2EndMs, "T2-compressive"))

            // T3 (expulsive)
            val t3EndMs = (ev.endSample.toDouble() / sr * 1000).toInt()
            val t3StartMs = t2EndMs
            if (t3EndMs > t3StartMs) segments.add(Segment(t3StartMs, t3EndMs, "T3-expulsive"))
        }
        return segments
    }
}
