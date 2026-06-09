package com.example.FFTT04M

/**
 * Platform-agnostic source of truth for spectrogram colour schemes.
 * Colors are represented as Int (0xAARRGGBB).
 */
object ColorMaps {

    val names = arrayOf(
        "Turbo", "Viridis", "Magma", "Inferno", "Plasma", "Cividis", "Gray"
    )

    private val anchors: Array<IntArray> = arrayOf(
        // 0 Turbo (Google)
        hexes("#30123b", "#4145ab", "#4675ed", "#39a2fc", "#1bcfd4", "#24eca6", "#61fc6c", "#a4fc3b", "#d1e834", "#f3c63a", "#fe9b2d", "#f36315", "#d93806", "#b11901", "#7a0402"),
        // 1 Viridis
        hexes("#440154", "#482878", "#3e4a89", "#31688e", "#26828e", "#1f9e89", "#35b779", "#6ece58", "#b5de2b", "#fde725"),
        // 2 Magma
        hexes("#000004", "#140e36", "#3b0f70", "#641a80", "#8c2981", "#b73779", "#de4968", "#f7705c", "#fe9f6d", "#fcfdbf"),
        // 3 Inferno
        hexes("#000004", "#1f0c48", "#550f6d", "#88226a", "#a83655", "#cc4248", "#ec6824", "#fb9b06", "#f7d03c", "#fcffa4"),
        // 4 Plasma
        hexes("#0d0887", "#41049d", "#6a00a8", "#8f0da4", "#b12a90", "#cc4778", "#e16462", "#f2844b", "#fca636", "#fcce25", "#f0f921"),
        // 5 Cividis
        hexes("#00204d", "#00336f", "#39486b", "#575d6d", "#707173", "#8a8779", "#a69d75", "#c4b56c", "#e4cf5b", "#ffea46"),
        // 6 Grayscale
        hexes("#000000", "#FFFFFF")
    )

    val luts: Array<IntArray> = Array(anchors.size) { buildLut(anchors[it]) }

    fun lut(index: Int): IntArray = luts[index.coerceIn(0, luts.size - 1)]

    private fun hexes(vararg s: String): IntArray = IntArray(s.size) { parseColor(s[it]) }

    private fun parseColor(hex: String): Int {
        val color = hex.removePrefix("#")
        val long = color.toLong(16)
        return if (color.length == 6) {
            (0xFF000000 or long).toInt()
        } else {
            long.toInt()
        }
    }

    private fun buildLut(stops: IntArray): IntArray {
        val lut = IntArray(256)
        if (stops.size == 1) {
            for (i in 0..255) lut[i] = stops[0]
            return lut
        }
        val segs = stops.size - 1
        for (i in 0..255) {
            val t = i / 255f * segs
            val seg = t.toInt().coerceAtMost(segs - 1)
            val frac = t - seg
            lut[i] = lerp(stops[seg], stops[seg + 1], frac)
        }
        return lut
    }

    private fun lerp(c1: Int, c2: Int, f: Float): Int {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val a1 = (c1 shr 24) and 0xFF

        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF
        val a2 = (c2 shr 24) and 0xFF

        val r = (r1 + (r2 - r1) * f).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * f).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * f).toInt().coerceIn(0, 255)
        val a = (a1 + (a2 - a1) * f).toInt().coerceIn(0, 255)

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
