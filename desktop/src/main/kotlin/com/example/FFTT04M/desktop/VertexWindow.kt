package com.example.FFTT04M.desktop

/**
 * Maps a detected squiggle's parabola VERTEX time onto the decode window whose code should stand for the
 * whole chirp — the one decision behind both the GUI "Auto-detect squiggles" action ([PhonemePlayer]) and
 * the headless [SquiggleDetectCli] harness. It lived inline in both, duplicated; sharing it here is what
 * makes the harness actually verify what the GUI does.
 *
 * Why this is not just "nearest window centre" (the original one-liner):
 *  - Decode windows are 180 ms on a 90 ms hop, i.e. **50% overlap**, so a vertex is normally inside TWO
 *    windows at once and the centres are only 90 ms apart. Nearest-centre therefore decides on a margin
 *    that can be a few ms — e.g. a real repro case fitted vertex=1302 ms with candidate centres at 1260
 *    and 1350 ms, a 6 ms margin. The fitted vertex (−b/2a over ~10 ms ridge frames, accepted at R² ≥ 0.2)
 *    is nowhere near that precise, so the pick flipped between neighbours on fit jitter.
 *  - Nearest-centre also ranked windows that merely OVERLAP the event span but do not span the vertex at
 *    all, which is how a vertex could be attributed to a window that never contained it.
 *
 * So: prefer windows that actually CONTAIN the vertex, rank them by how centred the vertex is, and break
 * near-ties ([TIE_MS]) with window loudness — a squiggle's vertex is its peak, the same assumption the
 * manual span-relabel default (peak-RMS window) has always made.
 *
 * And never adopt [UNASSIGNED]: `"?"` is the codebook's REJECT marker (no centroid within the cluster
 * radius — `PhonemeCodebookCli`), not a phoneme. Relabelling a whole chirp to `"?"` would overwrite the
 * real codes around it with a token that downstream dominant-letter inference then filters straight back
 * out — strictly worse than leaving the chirp chopped. On the 3 documented repro clips that case hit 3 of
 * 23 detected events, so it was not a corner case.
 */
object VertexWindow {

    /** Two candidate centres within this many ms of the vertex are treated as a tie and decided on
     *  loudness instead of geometry. 30 ms ≈ 3 ridge frames (10 ms STFT hop) and a third of the 90 ms
     *  window hop, so a tie group can only ever be immediate neighbours, never a run of windows. */
    const val TIE_MS = 30

    /** The codebook's "no cluster within radius" marker. Carries no phoneme identity. */
    const val UNASSIGNED = "?"

    private fun centreDist(wins: List<Pair<Int, Int>>, w: Int, vertexMs: Int): Int =
        kotlin.math.abs((wins[w].first + wins[w].second) / 2 - vertexMs)

    /**
     * The window whose code should be applied across a detected chirp, or **-1 when no candidate carries
     * a real code** — in which case the caller must leave the span alone rather than stamping `"?"` on it.
     *
     * @param idxs the decode windows overlapping the event span, ascending.
     * @param codeAt current code of a window (Tier-B edit if present, else the decode word).
     * @param rmsAt window loudness, used only to settle a [TIE_MS] near-tie.
     */
    fun pick(
        wins: List<Pair<Int, Int>>,
        idxs: List<Int>,
        vertexMs: Int,
        codeAt: (Int) -> String,
        rmsAt: (Int) -> Double,
    ): Int {
        if (idxs.isEmpty()) return -1
        val real = idxs.filter { codeAt(it) != UNASSIGNED }
        if (real.isEmpty()) return -1
        // Windows actually spanning the vertex are the honest candidates; fall back to the rest of the
        // event's windows only when the vertex lands outside every one of them (short/edge-clamped events).
        val containing = real.filter { vertexMs >= wins[it].first && vertexMs < wins[it].second }
        // Fallback: the vertex sits inside no real-coded window (short or edge-clamped event, or every
        // window spanning it is a "?"). Then take plain nearest-centre and STOP — the loudness tie-break
        // below is only justified where geometry is a genuine coin flip, i.e. among windows that each
        // contain the vertex and whose centres are <=45 ms from it. Out here the nearest candidate can be
        // 100+ ms away and loudest-wins would be picking on no evidence at all.
        if (containing.isEmpty()) return real.minByOrNull { centreDist(wins, it, vertexMs) } ?: -1
        val best = containing.minOf { centreDist(wins, it, vertexMs) }
        val tied = containing.filter { centreDist(wins, it, vertexMs) - best <= TIE_MS }
        return if (tied.size == 1) tied[0] else (tied.maxByOrNull { rmsAt(it) } ?: tied.first())
    }

    /** Geometry-only nearest-centre pick — the behaviour [pick] replaced. Kept so [SquiggleDetectCli] can
     *  print old-vs-new side by side and a future session can re-measure the change instead of trusting it. */
    fun pickNearestCentre(wins: List<Pair<Int, Int>>, idxs: List<Int>, vertexMs: Int): Int =
        idxs.minByOrNull { centreDist(wins, it, vertexMs) } ?: -1

    /** RMS of [pcm] over a [sMs,eMs) window. Shared so the GUI and the harness rank loudness identically. */
    fun rms(pcm: FloatArray, sampleRate: Int, sMs: Int, eMs: Int): Double {
        val a = (sMs.toLong() * sampleRate / 1000).toInt().coerceIn(0, pcm.size)
        val b = (eMs.toLong() * sampleRate / 1000).toInt().coerceIn(a, pcm.size)
        if (b <= a) return 0.0
        var s = 0.0
        for (i in a until b) s += pcm[i].toDouble() * pcm[i]
        return kotlin.math.sqrt(s / (b - a))
    }
}
