package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.MfccExtractor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.awt.Color
import java.awt.Component
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * Persisted **manual** comments, kept in a file SEPARATE from any auto-generated comments so they
 * can be used as human labels for later re-learning. Map of recording-id → comment text.
 */
object ManualComments {
    private val file = File(System.getProperty("user.home"), "FFTT04M/manual_comments.json")
    private val gson = Gson()
    private val map: MutableMap<String, String> = load()

    private fun load(): MutableMap<String, String> = try {
        if (file.isFile) gson.fromJson(file.readText(), object : TypeToken<MutableMap<String, String>>() {}.type)
            ?: mutableMapOf() else mutableMapOf()
    } catch (e: Exception) { mutableMapOf() }

    @Synchronized fun get(id: String): String? = map[id]?.takeIf { it.isNotBlank() }

    @Synchronized fun setAll(ids: Collection<String>, text: String) {
        for (id in ids) if (text.isBlank()) map.remove(id) else map[id] = text
        save()
    }

    private fun save() = try {
        file.parentFile?.mkdirs(); file.writeText(gson.toJson(map))
    } catch (e: Exception) { System.err.println("manual_comments save failed: ${e.message}") }
}

/** Sequential WAV player — used for single clips and for playing a multi-selection in order. */
object AudioPlayer {
    private var clip: Clip? = null
    private val queue = ArrayDeque<File>()

    @Synchronized fun playSequence(files: List<File>) { stop(); queue.clear(); queue.addAll(files); next() }

    @Synchronized private fun next() {
        val f = queue.removeFirstOrNull() ?: return
        try {
            val ais = AudioSystem.getAudioInputStream(f)
            val c = AudioSystem.getClip()
            c.open(ais)
            c.addLineListener { e -> if (e.type == LineEvent.Type.STOP) { c.close(); ais.close(); synchronized(this) { next() } } }
            clip = c; c.start()
        } catch (e: Exception) { System.err.println("play failed ${f.name}: ${e.message}"); next() }
    }

    @Synchronized fun stop() { try { clip?.stop(); clip?.close() } catch (_: Exception) {}; clip = null; queue.clear() }
}

/** Render a per-frame MFCC matrix as a blue→white→red diverging heatmap (time→X, coeff→Y, low at bottom). */
object MfccHeatmap {
    private val mfcc = MfccExtractor(numCoeffs = 13)

    fun render(pcm: FloatArray, sr: Int, w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0x14, 0x14, 0x18); g.fillRect(0, 0, w, h)
        val frames = mfcc.frames(pcm, 0, pcm.size, sr)
        if (frames.isEmpty()) { g.dispose(); return img }
        val nc = frames[0].size
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (fr in frames) for (v in fr) { if (v < lo) lo = v; if (v > hi) hi = v }
        val mid = (lo + hi) / 2.0
        val cw = w.toDouble() / frames.size
        val ch = h.toDouble() / nc
        for (fi in frames.indices) {
            val fr = frames[fi]
            for (c in 0 until nc) {
                g.color = divergingColor(fr[c], lo, mid, hi)
                val x = (fi * cw).toInt()
                val y = (h - (c + 1) * ch).toInt()   // coeff 0 at the bottom
                g.fillRect(x, y, max(1, (cw + 1).toInt()), max(1, (ch + 1).toInt()))
            }
        }
        g.dispose()
        return img
    }

    private fun divergingColor(v: Double, lo: Double, mid: Double, hi: Double): Color {
        if (hi <= lo) return Color.GRAY
        return if (v < mid) {                                   // blue → white
            val t = ((v - lo) / (mid - lo)).coerceIn(0.0, 1.0)
            Color((255 * t).toInt(), (255 * t).toInt(), 255)
        } else {                                                // white → red
            val t = ((v - mid) / (hi - mid)).coerceIn(0.0, 1.0)
            Color(255, (255 * (1 - t)).toInt(), (255 * (1 - t)).toInt())
        }
    }
}

/**
 * Grid view of loaded recordings — columns: [✓ multi-select] [FFT thumbnail] [comments] [MFCC map].
 * FFT cell: hover shows the full wav path, left-click plays the clip, right-click opens a menu
 * (Play / Add manual comment / Move / Delete). With clips checked, actions apply to the whole
 * checked set (play in sequence; one manual comment written to all). Thumbnails render off the EDT
 * and are cached by recording id.
 */
class RecordingsGridPanel : JPanel(java.awt.BorderLayout()) {

    private class Row(var rec: AudioRecording, var checked: Boolean = false)

    private val rows = mutableListOf<Row>()
    private val fftCache = ConcurrentHashMap<String, ImageIcon>()
    private val mfccCache = ConcurrentHashMap<String, ImageIcon>()
    private val renderPool = Executors.newFixedThreadPool(
        max(1, Runtime.getRuntime().availableProcessors() / 2))
    private val pending = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Notified (with the surviving recordings) after a delete/move so the host can sync its list. */
    var onRecordingsChanged: ((List<AudioRecording>) -> Unit)? = null

    private val THUMB_W = 190
    private val THUMB_H = 104

    private val model = object : AbstractTableModel() {
        private val cols = arrayOf("✓", "FFT", "Comments", "MFCC")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int): Class<*> = when (c) {
            0 -> java.lang.Boolean::class.java
            1, 3 -> ImageIcon::class.java
            else -> String::class.java
        }
        override fun isCellEditable(r: Int, c: Int) = c == 0
        override fun getValueAt(r: Int, c: Int): Any? {
            val row = rows[r]
            return when (c) {
                0 -> row.checked
                1 -> fftIcon(row.rec)
                2 -> commentHtml(row.rec)
                else -> mfccIcon(row.rec)
            }
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            if (c == 0) { rows[r].checked = v as? Boolean ?: false; fireTableCellUpdated(r, c) }
        }
    }

    private val table = object : JTable(model) {
        override fun getToolTipText(e: MouseEvent): String? {
            val r = rowAtPoint(e.point); val c = columnAtPoint(e.point)
            return if (r in rows.indices && (c == 1 || c == 3)) rows[r].rec.audioFile.absolutePath
            else super.getToolTipText(e)
        }
    }

    init {
        table.rowHeight = THUMB_H + 12
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).apply { preferredWidth = 30; maxWidth = 36 }
        table.columnModel.getColumn(1).preferredWidth = THUMB_W + 12
        table.columnModel.getColumn(2).preferredWidth = 260
        table.columnModel.getColumn(3).preferredWidth = THUMB_W + 12

        val commentRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
                val lbl = super.getTableCellRendererComponent(t, v, sel, foc, r, c) as JLabel
                lbl.verticalAlignment = TOP
                return lbl
            }
        }
        table.columnModel.getColumn(2).cellRenderer = commentRenderer
        table.columnModel.getColumn(1).cellRenderer = IconRenderer()
        table.columnModel.getColumn(3).cellRenderer = IconRenderer()

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val r = table.rowAtPoint(e.point); val c = table.columnAtPoint(e.point)
                if (r in rows.indices && c == 1) AudioPlayer.playSequence(listOf(rows[r].rec.audioFile))
            }
        })

        add(JScrollPane(table), java.awt.BorderLayout.CENTER)
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val r = table.rowAtPoint(e.point)
        if (r !in rows.indices) return
        val targets = checkedOrRow(r)
        JPopupMenu().apply {
            add(JMenuItem("Play (${targets.size})").apply { addActionListener { AudioPlayer.playSequence(targets.map { it.audioFile }) } })
            add(JMenuItem("Add manual comment…").apply { addActionListener { addComment(targets) } })
            add(JMenuItem("Move…").apply { addActionListener { moveFiles(targets) } })
            addSeparator()
            add(JMenuItem("Delete…").apply { addActionListener { deleteFiles(targets) } })
        }.show(table, e.x, e.y)
    }

    /** The checked recordings, or just the row under the cursor if none are checked. */
    private fun checkedOrRow(r: Int): List<AudioRecording> {
        val checked = rows.filter { it.checked }.map { it.rec }
        return if (checked.isNotEmpty()) checked else listOf(rows[r].rec)
    }

    private fun addComment(targets: List<AudioRecording>) {
        val seed = if (targets.size == 1) ManualComments.get(targets[0].id) ?: "" else ""
        val text = JOptionPane.showInputDialog(this,
            "Manual comment for ${targets.size} clip(s) (saved separately for re-learning):", seed) ?: return
        ManualComments.setAll(targets.map { it.id }, text.trim())
        model.fireTableDataChanged()
    }

    private fun moveFiles(targets: List<AudioRecording>) {
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Move ${targets.size} clip(s) to…" }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val dir = chooser.selectedFile
        var moved = 0
        for (t in targets) {
            val dest = File(dir, t.audioFile.name)
            if (t.audioFile.renameTo(dest)) {
                rows.firstOrNull { it.rec === t }?.let { it.rec = it.rec.copy(audioFile = dest) }
                moved++
            }
        }
        fftCache.clear(); mfccCache.clear()
        model.fireTableDataChanged()
        onRecordingsChanged?.invoke(rows.map { it.rec })
        showInfo("Moved $moved/${targets.size} clip(s) to ${dir.name}")
    }

    private fun deleteFiles(targets: List<AudioRecording>) {
        if (JOptionPane.showConfirmDialog(this,
                "Delete ${targets.size} clip file(s) from disk? This cannot be undone.",
                "Confirm delete", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        var deleted = 0
        val ids = targets.map { it.id }.toSet()
        for (t in targets) if (t.audioFile.delete() || !t.audioFile.exists()) deleted++
        rows.removeAll { it.rec.id in ids }
        model.fireTableDataChanged()
        onRecordingsChanged?.invoke(rows.map { it.rec })
        showInfo("Deleted $deleted/${targets.size} clip(s)")
    }

    // ---- data ------------------------------------------------------------------------------------

    fun setRecordings(list: List<AudioRecording>) {
        rows.clear()
        list.forEach { rows.add(Row(it)) }
        model.fireTableDataChanged()
    }

    fun checkedRecordings(): List<AudioRecording> = rows.filter { it.checked }.map { it.rec }

    // ---- thumbnails (rendered off-EDT, cached, repaint on completion) ----------------------------

    private fun commentHtml(rec: AudioRecording): String {
        val manual = ManualComments.get(rec.id)
        val auto = rec.label()?.let { "auto: $it" } ?: (rec.metadata["source"]?.let { "auto: $it" } ?: "")
        return buildString {
            append("<html><b>").append(rec.id).append("</b>")
            if (manual != null) append("<br><span style='color:#7fd'>✍ ").append(escape(manual)).append("</span>")
            if (auto.isNotBlank()) append("<br><span style='color:#999'>").append(escape(auto)).append("</span>")
            append("</html>")
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun fftIcon(rec: AudioRecording): ImageIcon? = thumb(rec, fftCache) { pcm, sr ->
        val tmp = File.createTempFile("fft", ".png")
        SpectrogramRenderer.renderFftPng(pcm, sr, tmp)
        val img = ImageIO.read(tmp); tmp.delete(); img
    }

    private fun mfccIcon(rec: AudioRecording): ImageIcon? = thumb(rec, mfccCache) { pcm, sr ->
        MfccHeatmap.render(pcm, sr, THUMB_W, THUMB_H)
    }

    private fun thumb(rec: AudioRecording, cache: ConcurrentHashMap<String, ImageIcon>,
                      render: (FloatArray, Int) -> BufferedImage?): ImageIcon? {
        cache[rec.id]?.let { return it }
        val key = System.identityHashCode(cache).toString() + ":" + rec.id
        if (pending.add(key)) {
            renderPool.submit {
                try {
                    val pcm = AudioDecoder.decode(rec.audioFile)
                    val icon = pcm?.let { render(it, 44100) }?.let {
                        ImageIcon(it.getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH))
                    }
                    if (icon != null) {
                        cache[rec.id] = icon
                        SwingUtilities.invokeLater {
                            val r = rows.indexOfFirst { it.rec.id == rec.id }
                            if (r >= 0) model.fireTableRowsUpdated(r, r)
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("thumb failed ${rec.id}: ${e.message}")
                } finally { pending.remove(key) }
            }
        }
        return null   // placeholder until the async render lands
    }

    private fun showInfo(msg: String) = SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(this, msg)
    }

    /** Centered icon renderer for the thumbnail columns. */
    private class IconRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = CENTER }
        override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
            val lbl = super.getTableCellRendererComponent(t, null, sel, foc, r, c) as JLabel
            lbl.icon = v as? ImageIcon
            lbl.text = if (v == null) "…" else null
            return lbl
        }
    }
}
