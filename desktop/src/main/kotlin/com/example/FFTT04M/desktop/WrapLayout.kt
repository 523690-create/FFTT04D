package com.example.FFTT04M.desktop

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/**
 * A [FlowLayout] that actually wraps. Plain FlowLayout reports a preferred size of a SINGLE row, so in
 * a fixed-height region (e.g. BorderLayout.NORTH) any components that wrap to a second row are clipped
 * and invisible. WrapLayout reports a preferred height that accounts for wrapping at the container's
 * current width, so every component (e.g. the toolbar's Legend / Phoneme-cloud buttons) stays visible.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 4, vgap: Int = 2) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)
    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).also { it.width -= (hgap + 1) }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var targetWidth = target.size.width
            if (targetWidth == 0) targetWidth = Integer.MAX_VALUE
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = 0; rowHeight = 0
                }
                rowWidth += d.width + hgap
                rowHeight = maxOf(rowHeight, d.height)
            }
            dim.width = maxOf(dim.width, rowWidth) + insets.left + insets.right + hgap * 2
            dim.height += rowHeight + insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}
