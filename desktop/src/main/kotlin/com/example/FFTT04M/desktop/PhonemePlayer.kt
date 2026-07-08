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
        p.load(img, segs, durMs, pcm)
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
        private var curMs = 0
        private var clip: Clip? = null
        private var timer: Timer? = null
        private var tmpWav: File? = null
        private val TOP = 20

        init {
            background = Color(0x14, 0x14, 0x18)
            preferredSize = Dimension(1040, 440)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) { play() }   // click to replay
            })
        }

        fun load(image: BufferedImage?, s: List<Triple<Int, Int, String>>, dur: Int, samples: FloatArray) {
            img = image; segs = s; durMs = dur.coerceAtLeast(1); pcm = samples; curMs = 0; repaint()
        }

        fun play() {
            stop()
            try {
                val tmp = File.createTempFile("phonplay", ".wav").apply { deleteOnExit() }
                AudioDecoder.writeWavMono16(pcm, SR, tmp); tmpWav = tmp
                val c = AudioSystem.getClip(); c.open(AudioSystem.getAudioInputStream(tmp)); clip = c; c.start()
                curMs = 0
                timer = Timer(25) {
                    val len = c.microsecondLength.coerceAtLeast(1)
                    curMs = (c.microsecondPosition * durMs / len).toInt().coerceIn(0, durMs)
                    if (!c.isRunning && c.microsecondPosition >= len) { curMs = durMs; timer?.stop() }
                    repaint()
                }.also { it.start() }
            } catch (_: Exception) {}
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
            // top label strip
            g.color = Color(0x00, 0x00, 0x00); g.fillRect(0, 0, w, TOP)
            // phoneme segments: black dividers + centred code labels
            g.font = g.font.deriveFont(11f)
            for ((s, e, code) in segs) {
                val x0 = xOf(s); val x1 = xOf(e)
                g.color = Color(0, 0, 0); g.stroke = java.awt.BasicStroke(1.5f)
                g.drawLine(x1, specY, x1, h)                    // divider at segment end
                g.color = if (code == "?") Color(0x66, 0x66, 0x66) else Color(0xe8, 0xe8, 0xe8)
                val lw = g.fontMetrics.stringWidth(code)
                if (x1 - x0 > lw + 4) g.drawString(code, ((x0 + x1) / 2 - lw / 2), 14)
            }
            if (segs.isEmpty()) { g.color = Color(0x88, 0x88, 0x88); g.drawString("(no phoneme decode for this clip)", 6, 14) }
            // white sweep cursor
            val cx = xOf(curMs)
            g.color = Color(0xff, 0xff, 0xff); g.stroke = java.awt.BasicStroke(1.5f)
            g.drawLine(cx, 0, cx, h)
        }
    }
}
