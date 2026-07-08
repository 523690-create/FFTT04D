package com.example.FFTT04M.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Expanded playback view for a clip: a large FFT spectrogram with the clip's PHONEME decode drawn as
 * vertical black dividers + code labels along the top, and a white cursor that sweeps in sync with audio.
 * Stays on screen; click it to replay. One reusable window — clicking another atlas cell replaces it.
 * Phoneme sequence comes from the precomputed decodes ([DecodeStore], the `*_decoded.json` words).
 */
object PhonemePlayer {
    private const val SR = 44100
    private const val WIN_MS = 180
    private const val HOP_MS = 90

    private var frame: JFrame? = null
    private var panel: PlayerPanel? = null

    fun show(parent: Component?, wav: File) {
        thread {
            val pcm = AudioDecoder.decode(wav)?.also { peakNorm(it) } ?: return@thread
            val durMs = (pcm.size.toLong() * 1000 / SR).toInt().coerceAtLeast(1)
            val tmp = File.createTempFile("phonspec", ".png")
            val img = try { SpectrogramRenderer.renderFftPng(pcm, SR, tmp); ImageIO.read(tmp) } catch (_: Exception) { null } finally { tmp.delete() }
            val word = DecodeStore.get(wav.nameWithoutExtension)?.word ?: emptyList()
            SwingUtilities.invokeLater { open(parent, wav, pcm, img, word, durMs) }
        }
    }

    /** A decoded phoneme segment: [sMs,eMs) on the timeline, its code, and its decode-window index range. */
    data class Seg(val sMs: Int, val eMs: Int, val code: String, val wFrom: Int, val wTo: Int)

    /** Fixed-grid decode windows (180 ms / 90 ms), mirroring PhonemeCodebookCli. */
    private fun windowsFor(durMs: Int): List<Pair<Int, Int>> {
        val wins = ArrayList<Pair<Int, Int>>()
        if (durMs <= WIN_MS) wins.add(0 to durMs)
        else { var s = 0; while (s < durMs) { val e = (s + WIN_MS).coerceAtMost(durMs); if (e - s >= WIN_MS / 2) wins.add(s to e); if (e >= durMs) break; s += HOP_MS } }
        return wins
    }

    /** Merge consecutive equal codes — after applying any Tier-B per-window overrides — into segments. */
    private fun segments(id: String, word: List<String>, durMs: Int): List<Seg> {
        val wins = windowsFor(durMs)
        if (word.isEmpty() || wins.isEmpty()) return emptyList()
        val edits = PhonemeSegmentEdits.get(id)
        fun codeAt(i: Int) = edits?.get(i) ?: word.getOrElse(i) { "?" }
        val out = ArrayList<Seg>()
        var i = 0
        while (i < wins.size) {
            val code = codeAt(i); var j = i
            while (j + 1 < wins.size && codeAt(j + 1) == code) j++
            out.add(Seg(wins[i].first, wins[j].second, code, i, j))
            i = j + 1
        }
        return out
    }

    private fun open(parent: Component?, wav: File, pcm: FloatArray, img: BufferedImage?, word: List<String>, durMs: Int) {
        val p = panel ?: PlayerPanel().also { panel = it }
        val f = frame ?: JFrame("Phoneme player").apply {
            contentPane.add(p, BorderLayout.CENTER); size = Dimension(1040, 440); setLocationByPlatform(true)
            defaultCloseOperation = JFrame.HIDE_ON_CLOSE       // keep the singleton alive for the next cell
            frame = this
        }
        f.title = "Phoneme player — ${wav.nameWithoutExtension.take(60)}"
        p.load(wav, img, word, durMs, pcm)
        f.isVisible = true; f.toFront()
        p.play()
    }

    private fun peakNorm(x: FloatArray, target: Float = 0.9f) {
        var m = 1e-6f; for (v in x) if (kotlin.math.abs(v) > m) m = kotlin.math.abs(v)
        val g = target / m; if (g < 1f || g > 1.2f) for (i in x.indices) x[i] *= g
    }

    private class PlayerPanel : JPanel(BorderLayout()) {
        private var img: BufferedImage? = null
        private var segs: List<Seg> = emptyList()
        private var word: List<String> = emptyList()
        private var id: String = ""
        private var durMs = 1
        private var pcm: FloatArray = FloatArray(0)
        private var wav: File? = null
        private var curMs = 0
        private var status = ""
        private var clip: Clip? = null
        private var timer: Timer? = null
        private var tmpWav: File? = null
        private var selStartX = -1
        private var selEndX = -1
        private var dragging = false
        private val TOP = 26
        // renderFftPng's STFT (2048/1024 @ 44.1 kHz) puts the first/last frame CENTRE ~23 ms in from each
        // edge, so the spectrogram content spans [HALF_WIN, dur-HALF_WIN] — map boundaries/cursor to that.
        private val HALF_WIN = 1024.0 * 1000.0 / SR
        private val CLASSES = listOf("voice", "snoring", "noise", "dry", "dry hacking", "bronchitis", "typical bronchitis", "croup", "sneeze")

        init {
            background = Color(0x14, 0x14, 0x18)
            preferredSize = Dimension(1040, 440)
            val ma = object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) { showMenu(e); return }
                    if (SwingUtilities.isLeftMouseButton(e)) { selStartX = e.x; selEndX = e.x; dragging = false }
                }
                override fun mouseDragged(e: java.awt.event.MouseEvent) {
                    if (selStartX >= 0) { selEndX = e.x; if (kotlin.math.abs(selEndX - selStartX) >= 5) dragging = true; repaint() }
                }
                override fun mouseReleased(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger) { showMenu(e); return }
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (dragging) spanRelabel(selStartX, selEndX) else play()
                        selStartX = -1; dragging = false; repaint()
                    }
                }
            }
            addMouseListener(ma); addMouseMotionListener(ma)
        }

        fun load(w: File?, image: BufferedImage?, wrd: List<String>, dur: Int, samples: FloatArray) {
            wav = w; id = w?.nameWithoutExtension ?: ""; img = image; word = wrd; durMs = dur.coerceAtLeast(1)
            pcm = samples; curMs = 0; status = ""; rebuildSegs(); repaint()
        }

        private fun rebuildSegs() { segs = segments(id, word, durMs) }

        /**
         * Right-click menu. Tier-B (top): per-PHONEME edits on the segment under the cursor — merge with a
         * neighbour, set an arbitrary code, or reset — persisted to [PhonemeSegmentEdits] and reflected live.
         * Tier-A (bottom): clip-level feedback/label consumed by the next phonemeCodebookCli rebuild.
         */
        private fun showMenu(e: java.awt.event.MouseEvent) {
            if (id.isEmpty()) return
            val dec = DecodeStore.get(id); val cls = dec?.classLabel ?: dec?.letter ?: "?"
            val fb = DecodeFeedback.get(id); val lbl = ManualComments.get(id)
            fun note(msg: String) { status = msg; repaint() }
            fun applyEdit(from: Int, to: Int, code: String) { PhonemeSegmentEdits.setRange(id, from, to, code); rebuildSegs() }
            val idx = segIndexAt(e.x); val seg = segs.getOrNull(idx)
            javax.swing.JPopupMenu().apply {
                if (seg != null) {
                    val prev = segs.getOrNull(idx - 1); val next = segs.getOrNull(idx + 1)
                    add(javax.swing.JMenuItem("▶  Play phoneme only (${seg.code})").apply { addActionListener { playRange(seg.sMs, seg.eMs) } })
                    addSeparator()
                    add(javax.swing.JMenuItem("phoneme #${idx + 1}: ${seg.code}   (win ${seg.wFrom}–${seg.wTo})").apply { isEnabled = false })
                    if (prev != null) add(javax.swing.JMenuItem("⇤  Merge with previous  → ${prev.code}").apply { addActionListener { applyEdit(seg.wFrom, seg.wTo, prev.code); note("merged into ${prev.code}") } })
                    if (next != null) add(javax.swing.JMenuItem("⇥  Merge with next  → ${next.code}").apply { addActionListener { applyEdit(seg.wFrom, seg.wTo, next.code); note("merged into ${next.code}") } })
                    add(javax.swing.JMenuItem("✎  Set this phoneme's code…").apply { addActionListener {
                        val t = javax.swing.JOptionPane.showInputDialog(this@PlayerPanel, "New code for phoneme #${idx + 1} (e.g. BT18):", seg.code)
                        if (t != null && t.isNotBlank()) { applyEdit(seg.wFrom, seg.wTo, t.trim()); note("phoneme → ${t.trim()}") }
                    } })
                    add(javax.swing.JMenuItem("↺  Reset this phoneme").apply { addActionListener { PhonemeSegmentEdits.clearRange(id, seg.wFrom, seg.wTo); rebuildSegs(); note("phoneme reset to decode") } })
                    addSeparator()
                }
                add(javax.swing.JMenuItem("🔍  Auto-detect squiggles (this clip)").apply { addActionListener { autoDetectSquiggles() } })
                addSeparator()
                add(javax.swing.JMenuItem("decoded: $cls${fb?.let { if (it) "  ✓" else "  ✗" } ?: ""}${lbl?.let { "  · label: $it" } ?: ""}").apply { isEnabled = false })
                addSeparator()
                add(javax.swing.JMenuItem("✓  Confirm decode ($cls)").apply { addActionListener { DecodeFeedback.setAll(listOf(id), true); note("confirmed → $cls (feeds training on next rebuild)") } })
                add(javax.swing.JMenuItem("✗  Mark decode wrong").apply { addActionListener { DecodeFeedback.setAll(listOf(id), false); note("marked wrong (excluded from training)") } })
                add(javax.swing.JMenu("Re-label clip →").apply {
                    CLASSES.forEach { c -> add(javax.swing.JMenuItem(c).apply { addActionListener { ManualComments.setAll(listOf(id), c); DecodeFeedback.clear(id); note("re-labelled → $c") } }) }
                    add(javax.swing.JMenuItem("custom…").apply { addActionListener {
                        val t = javax.swing.JOptionPane.showInputDialog(this@PlayerPanel, "Label for $id:", lbl ?: "")
                        if (t != null) { ManualComments.setAll(listOf(id), t.trim()); DecodeFeedback.clear(id); note(if (t.isBlank()) "label cleared" else "re-labelled → ${t.trim()}") }
                    } })
                })
                add(javax.swing.JMenuItem("Clear label + feedback").apply { addActionListener { ManualComments.setAll(listOf(id), ""); DecodeFeedback.clear(id); note("cleared") } })
            }.show(this, e.x, e.y)
        }

        fun play() = playRange(0, durMs)

        /** Play [sMs, eMs) of the clip (full clip, or a single phoneme segment) with the cursor swept over it. */
        private fun playRange(sMs: Int, eMs: Int) {
            stop()
            val a = (sMs.toLong() * SR / 1000).toInt().coerceIn(0, pcm.size)
            val b = (eMs.toLong() * SR / 1000).toInt().coerceIn(a, pcm.size)
            if (b - a < 200) return
            try {
                val tmp = File.createTempFile("phonplay", ".wav").apply { deleteOnExit() }
                AudioDecoder.writeWavMono16(pcm.copyOfRange(a, b), SR, tmp); tmpWav = tmp
                val c = AudioSystem.getClip(); c.open(AudioSystem.getAudioInputStream(tmp)); clip = c; c.start()
                curMs = sMs
                timer = Timer(25) {
                    val len = c.microsecondLength.coerceAtLeast(1)
                    curMs = (sMs + c.microsecondPosition * (eMs - sMs) / len).toInt().coerceIn(sMs, eMs)
                    if (!c.isRunning && c.microsecondPosition >= len) { curMs = eMs; timer?.stop() }
                    repaint()
                }.also { it.start() }
            } catch (_: Exception) {}
        }

        /** Pixel x → clip time (ms), inverse of the frame-centre xOf mapping used for drawing. */
        private fun msAt(x: Int): Int {
            val span = durMs - 2 * HALF_WIN
            return if (span > 1) (HALF_WIN + x.toDouble() / width.coerceAtLeast(1) * span).toInt().coerceIn(0, durMs)
                   else (x.toDouble() / width.coerceAtLeast(1) * durMs).toInt().coerceIn(0, durMs)
        }

        /** Index of the phoneme segment under an x pixel (-1 if none), for right-click per-phoneme edits. */
        private fun segIndexAt(x: Int): Int {
            val ms = msAt(x)
            return segs.indexOfFirst { ms >= it.sMs && ms < it.eMs }
        }

        /** Drag-select a span of the ribbon → set every decode window it covers to ONE code. Default is the
         *  loudest (peak-RMS) window's code — a squiggle's vertex — so a chopped parabola becomes one phoneme
         *  in a single gesture. Reversible: right-click any window → "Reset this phoneme". */
        private fun spanRelabel(xa: Int, xb: Int) {
            if (id.isEmpty() || word.isEmpty()) return
            val msLo = msAt(minOf(xa, xb)); val msHi = msAt(maxOf(xa, xb))
            val wins = windowsFor(durMs)
            val idxs = wins.indices.filter { wins[it].second > msLo && wins[it].first < msHi }
            if (idxs.isEmpty()) return
            val wFrom = idxs.first(); val wTo = idxs.last()
            fun codeAt(i: Int) = PhonemeSegmentEdits.get(id)?.get(i) ?: word.getOrElse(i) { "?" }
            val peak = idxs.maxByOrNull { winRms(wins[it].first, wins[it].second) } ?: wFrom
            val def = codeAt(peak)
            val t = javax.swing.JOptionPane.showInputDialog(this, "Set ${wTo - wFrom + 1} windows (one phoneme span) to code:", def)
            if (t != null && t.isNotBlank()) {
                PhonemeSegmentEdits.setRange(id, wFrom, wTo, t.trim()); rebuildSegs()
                status = "span → ${t.trim()}  (win $wFrom–$wTo)"
            }
            repaint()
        }

        /** Multi-event ridge detection over the whole clip → relabel each detected squiggle's covering
         *  decode windows to its vertex's code, same as a manual [spanRelabel] drag but automatic and
         *  batched over every chirp found. Skips spans that are already a single code (nothing to fix).
         *  Reviewable/reversible exactly like manual edits: written through [PhonemeSegmentEdits], so any
         *  span can be right-clicked → "Reset this phoneme" afterward. */
        private fun autoDetectSquiggles() {
            if (id.isEmpty() || word.isEmpty()) { status = "no decode to relabel"; repaint(); return }
            val wins = windowsFor(durMs)
            fun codeAt(i: Int) = PhonemeSegmentEdits.get(id)?.get(i) ?: word.getOrElse(i) { "?" }
            val events = com.example.FFTT04M.desktop.cough.MultiRidgeExtractor().detect(pcm, 0, pcm.size, SR)
            var applied = 0
            for (ev in events) {
                val msLo = (ev.t0Sec * 1000).toInt(); val msHi = (ev.t1Sec * 1000).toInt()
                val idxs = wins.indices.filter { wins[it].second > msLo && wins[it].first < msHi }
                if (idxs.size < 2) continue
                val wFrom = idxs.first(); val wTo = idxs.last()
                if (idxs.map { codeAt(it) }.toSet().size <= 1) continue
                val vMs = (ev.vertexTimeSec * 1000).toInt()
                val vertexWin = idxs.minByOrNull { w -> kotlin.math.abs((wins[w].first + wins[w].second) / 2 - vMs) } ?: wFrom
                PhonemeSegmentEdits.setRange(id, wFrom, wTo, codeAt(vertexWin))
                applied++
            }
            rebuildSegs()
            status = if (applied == 0) "auto-detect: no chopped squiggles found" else "auto-detect: relabeled $applied span(s)"
            repaint()
        }

        private fun winRms(sMs: Int, eMs: Int): Double {
            val a = (sMs.toLong() * SR / 1000).toInt().coerceIn(0, pcm.size)
            val b = (eMs.toLong() * SR / 1000).toInt().coerceIn(a, pcm.size)
            if (b <= a) return 0.0
            var s = 0.0; for (i in a until b) s += pcm[i].toDouble() * pcm[i]
            return kotlin.math.sqrt(s / (b - a))
        }

        private fun stop() { timer?.stop(); timer = null; runCatching { clip?.stop(); clip?.close() }; clip = null }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width; val h = height; val specY = TOP; val specH = h - TOP
            val xSpan = durMs - 2 * HALF_WIN
            fun xOf(ms: Int) = if (xSpan > 1) (((ms - HALF_WIN) / xSpan) * w).toInt().coerceIn(0, w)
                               else (ms.toDouble() / durMs * w).toInt().coerceIn(0, w)
            // spectrogram
            if (img != null) g.drawImage(img, 0, specY, w, specH, null)
            else { g.color = Color(0x22, 0x22, 0x28); g.fillRect(0, specY, w, specH) }
            // top strip = phoneme ribbon. Each segment: faint class TINT + a bright full-height boundary at
            // its START, with its code LEFT-ALIGNED just inside that boundary (2-row stagger so crowded labels
            // never collide). So a label always sits at the left edge of its own segment — unambiguous.
            g.color = Color(0x0a, 0x0a, 0x0e); g.fillRect(0, 0, w, TOP)
            g.font = g.font.deriveFont(java.awt.Font.BOLD, 11f)
            val lastRight = intArrayOf(-1000, -1000)
            for (sg in segs) {
                val s = sg.sMs; val e = sg.eMs; val code = sg.code
                val x0 = xOf(s); val x1 = xOf(e); val bw = (x1 - x0).coerceAtLeast(1)
                val cc = if (code == "?") Color(0x55, 0x55, 0x5a) else PhonemeCloud.classColor(code.takeWhile { it.isLetter() })
                g.color = Color(cc.red, cc.green, cc.blue, 110); g.fillRect(x0, 0, bw, TOP - 1)              // faint class tint
                g.color = Color(0xcc, 0xcc, 0xcc); g.stroke = java.awt.BasicStroke(1.1f); g.drawLine(x0, 0, x0, h)   // boundary, full height
                if (bw >= 3) {
                    val lx = x0 + 3; val lw = g.fontMetrics.stringWidth(code)
                    val r = if (lx >= lastRight[0]) 0 else if (lx >= lastRight[1]) 1 else if (lastRight[0] <= lastRight[1]) 0 else 1
                    g.color = cc.brighter(); g.drawString(code, lx, if (r == 0) 12 else 23); lastRight[r] = lx + lw + 3
                }
            }
            if (segs.isEmpty()) { g.color = Color(0x88, 0x88, 0x88); g.drawString("(no phoneme decode for this clip)", 6, 15) }
            // drag-selection highlight (span → one code)
            if (dragging && selStartX >= 0) {
                val a = minOf(selStartX, selEndX); val bb = maxOf(selStartX, selEndX)
                g.color = Color(0x66, 0xcc, 0xff, 60); g.fillRect(a, 0, bb - a, h)
                g.color = Color(0x66, 0xcc, 0xff, 170); g.stroke = java.awt.BasicStroke(1f); g.drawRect(a, 0, (bb - a).coerceAtLeast(1), h - 1)
            }
            // white sweep cursor
            val cx = xOf(curMs)
            g.color = Color(0xff, 0xff, 0xff); g.stroke = java.awt.BasicStroke(1.5f)
            g.drawLine(cx, 0, cx, h)

            // status (after a reclassify action) + interaction hint
            if (status.isNotEmpty()) {
                g.font = g.font.deriveFont(11f); val sw = g.fontMetrics.stringWidth(status)
                g.color = Color(0, 0, 0, 180); g.fillRect(4, h - 20, sw + 10, 16)
                g.color = Color(0x9f, 0xe0, 0x9f); g.drawString(status, 9, h - 8)
            }
            g.color = Color(0x88, 0x88, 0x88); g.font = g.font.deriveFont(9.5f)
            val hint = "click: replay   ·   drag: set a span to one code   ·   right-click: merge / set code / relabel"
            g.drawString(hint, w - g.fontMetrics.stringWidth(hint) - 6, h - 7)
        }
    }
}
