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
            val segs = segments(DecodeStore.get(wav.nameWithoutExtension)?.word ?: emptyList(), durMs)
            SwingUtilities.invokeLater { open(parent, wav, pcm, img, segs, durMs) }
        }
    }

    /** Fixed-grid windows (mirrors the decode) → merge consecutive equal codes into labelled segments. */
    private fun segments(word: List<String>, durMs: Int): List<Triple<Int, Int, String>> {
        val wins = ArrayList<Pair<Int, Int>>()
        if (durMs <= WIN_MS) wins.add(0 to durMs)
        else { var s = 0; while (s < durMs) { val e = (s + WIN_MS).coerceAtMost(durMs); if (e - s >= WIN_MS / 2) wins.add(s to e); if (e >= durMs) break; s += HOP_MS } }
        if (word.isEmpty() || wins.isEmpty()) return emptyList()
        val out = ArrayList<Triple<Int, Int, String>>()
        var i = 0
        while (i < wins.size) {
            val code = word.getOrElse(i) { "?" }
            var j = i
            while (j + 1 < wins.size && word.getOrElse(j + 1) { "?" } == code) j++
            out.add(Triple(wins[i].first, wins[j].second, code))
            i = j + 1
        }
        return out
    }

    private fun open(parent: Component?, wav: File, pcm: FloatArray, img: BufferedImage?, segs: List<Triple<Int, Int, String>>, durMs: Int) {
        val p = panel ?: PlayerPanel().also { panel = it }
        val f = frame ?: JFrame("Phoneme player").apply {
            contentPane.add(p, BorderLayout.CENTER); size = Dimension(1040, 440); setLocationByPlatform(true)
            defaultCloseOperation = JFrame.HIDE_ON_CLOSE       // keep the singleton alive for the next cell
            frame = this
        }
        f.title = "Phoneme player — ${wav.nameWithoutExtension.take(60)}"
        p.load(wav, img, segs, durMs, pcm)
        f.isVisible = true; f.toFront()
        p.play()
    }

    private fun peakNorm(x: FloatArray, target: Float = 0.9f) {
        var m = 1e-6f; for (v in x) if (kotlin.math.abs(v) > m) m = kotlin.math.abs(v)
        val g = target / m; if (g < 1f || g > 1.2f) for (i in x.indices) x[i] *= g
    }

    private class PlayerPanel : JPanel(BorderLayout()) {
        private var img: BufferedImage? = null
        private var segs: List<Triple<Int, Int, String>> = emptyList()
        private var durMs = 1
        private var pcm: FloatArray = FloatArray(0)
        private var wav: File? = null
        private var curMs = 0
        private var status = ""
        private var clip: Clip? = null
        private var timer: Timer? = null
        private var tmpWav: File? = null
        private val TOP = 26
        private val CLASSES = listOf("voice", "snoring", "noise", "dry", "dry hacking", "bronchitis", "typical bronchitis", "croup", "sneeze")

        init {
            background = Color(0x14, 0x14, 0x18)
            preferredSize = Dimension(1040, 440)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) showMenu(e) }
                override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) showMenu(e) }
                override fun mouseClicked(e: java.awt.event.MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) play() }
            })
        }

        fun load(w: File?, image: BufferedImage?, s: List<Triple<Int, Int, String>>, dur: Int, samples: FloatArray) {
            wav = w; img = image; segs = s; durMs = dur.coerceAtLeast(1); pcm = samples; curMs = 0; status = ""; repaint()
        }

        /** Tier-A reclassification: clip-level feedback/label that the next phonemeCodebookCli rebuild consumes. */
        private fun showMenu(e: java.awt.event.MouseEvent) {
            val id = wav?.nameWithoutExtension ?: return
            val dec = DecodeStore.get(id); val cls = dec?.classLabel ?: dec?.letter ?: "?"
            val fb = DecodeFeedback.get(id); val lbl = ManualComments.get(id)
            fun note(msg: String) { status = msg; repaint() }
            val seg = segAt(e.x)
            javax.swing.JPopupMenu().apply {
                if (seg != null) {
                    add(javax.swing.JMenuItem("▶  Play phoneme only (${seg.third})").apply { addActionListener { playRange(seg.first, seg.second) } })
                    addSeparator()
                }
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

        /** The phoneme segment under an x pixel (for right-click "play phoneme only"). */
        private fun segAt(x: Int): Triple<Int, Int, String>? {
            val ms = (x.toDouble() / width.coerceAtLeast(1) * durMs).toInt()
            return segs.firstOrNull { ms >= it.first && ms < it.second }
        }

        private fun stop() { timer?.stop(); timer = null; runCatching { clip?.stop(); clip?.close() }; clip = null }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0 as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width; val h = height; val specY = TOP; val specH = h - TOP
            fun xOf(ms: Int) = (ms.toDouble() / durMs * w).toInt().coerceIn(0, w)
            // spectrogram
            if (img != null) g.drawImage(img, 0, specY, w, specH, null)
            else { g.color = Color(0x22, 0x22, 0x28); g.fillRect(0, specY, w, specH) }
            // top strip = phoneme ribbon. Each segment: faint class TINT + a bright full-height boundary at
            // its START, with its code LEFT-ALIGNED just inside that boundary (2-row stagger so crowded labels
            // never collide). So a label always sits at the left edge of its own segment — unambiguous.
            g.color = Color(0x0a, 0x0a, 0x0e); g.fillRect(0, 0, w, TOP)
            g.font = g.font.deriveFont(java.awt.Font.BOLD, 11f)
            val lastRight = intArrayOf(-1000, -1000)
            for ((s, e, code) in segs) {
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
            val hint = "click: replay   ·   right-click: reclassify"
            g.drawString(hint, w - g.fontMetrics.stringWidth(hint) - 6, h - 7)
        }
    }
}
