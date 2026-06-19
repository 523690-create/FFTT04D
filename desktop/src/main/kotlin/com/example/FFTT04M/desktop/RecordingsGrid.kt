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
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Persisted **manual** comments, kept in a file SEPARATE from any auto-generated comments so they
 * can be used as human labels for later re-learning. Map of recording-id → comment text.
 */
object ManualComments {
    private val file = Workspace.file("manual_comments.json")
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

    /** Import comments only where none exists yet (e.g. mobile `.txt`-sidecar comments on load) so
     *  they sit on equal footing with desktop-entered ones, without overwriting a desktop edit. */
    @Synchronized fun importAll(pairs: List<Pair<String, String>>) {
        var changed = false
        for ((id, text) in pairs) if (text.isNotBlank() && map[id].isNullOrBlank()) { map[id] = text; changed = true }
        if (changed) save()
    }

    private fun save() = try {
        file.parentFile?.mkdirs(); file.writeText(gson.toJson(map))
    } catch (e: Exception) { System.err.println("manual_comments save failed: ${e.message}") }
}

/** Phoneme-codebook decode results (the `_decoded.json` files under data/codebooks) — id → letter + word. */
object DecodeStore {
    data class Dec(val letter: String, val word: List<String>)
    @Volatile private var cache: Map<String, Dec>? = null
    private fun map(): Map<String, Dec> = cache ?: load().also { cache = it }
    fun reload() { cache = null }
    fun get(id: String): Dec? = map()[id]

    @Suppress("UNCHECKED_CAST")
    private fun load(): Map<String, Dec> {
        val out = HashMap<String, Dec>()
        val gson = Gson()
        Workspace.dir("codebooks").listFiles { f -> f.name.endsWith("_decoded.json") }?.forEach { f ->
            try {
                val data: Map<String, Map<String, Any>> = gson.fromJson(f.readText(),
                    object : TypeToken<Map<String, Map<String, Any>>>() {}.type) ?: emptyMap()
                for ((id, v) in data) {
                    val letter = v["inferredLetter"] as? String ?: continue
                    val word = (v["word"] as? List<*>)?.map { it.toString() } ?: emptyList()
                    out[id] = Dec(letter, word)
                }
            } catch (e: Exception) { System.err.println("decode load ${f.name}: ${e.message}") }
        }
        return out
    }
}

/** Human verdict on an auto-decode: id → true (correct) / false (error). data/codebooks/decode_feedback.json */
object DecodeFeedback {
    private val file = File(Workspace.dir("codebooks"), "decode_feedback.json")
    private val gson = Gson()
    private val map: MutableMap<String, Boolean> = try {
        if (file.isFile) gson.fromJson(file.readText(), object : TypeToken<MutableMap<String, Boolean>>() {}.type)
            ?: mutableMapOf() else mutableMapOf()
    } catch (e: Exception) { mutableMapOf() }

    @Synchronized fun get(id: String): Boolean? = map[id]
    @Synchronized fun setAll(ids: Collection<String>, correct: Boolean) {
        for (id in ids) map[id] = correct
        try { file.writeText(gson.toJson(map)) } catch (e: Exception) { System.err.println("decode_feedback save: ${e.message}") }
    }
}

/** Sequential WAV player — used for single clips and for playing a multi-selection in order. */
object AudioPlayer {
    private var clip: Clip? = null
    private val queue = ArrayDeque<File>()
    private var dialog: JDialog? = null
    private var label: JLabel? = null
    @Volatile private var failedAny = false

    @Synchronized fun playSequence(files: List<File>) {
        stop(); failedAny = false; queue.addAll(files)
        SwingUtilities.invokeLater { ensureDialog().isVisible = true }
        next()
    }

    /** End of the sequence: auto-close the popup on success, but keep it (with the diagnostic) on failure. */
    private fun onSequenceEnd() {
        if (failedAny) setBody("Done (with errors).")
        else SwingUtilities.invokeLater { dialog?.isVisible = false }
    }

    @Synchronized private fun next() {
        val f = queue.removeFirstOrNull() ?: run { onSequenceEnd(); return }
        try {
            val src = AudioSystem.getAudioInputStream(f)
            val base = src.format
            // Convert anything that isn't plain 16-bit PCM to a standard playable format.
            val stream = if (base.encoding == AudioFormat.Encoding.PCM_SIGNED && base.sampleSizeInBits == 16) src
                else AudioSystem.getAudioInputStream(AudioFormat(base.sampleRate, 16, base.channels, true, false), src)
            if (!AudioSystem.isLineSupported(DataLine.Info(Clip::class.java, stream.format))) {
                fail(f, "No audio output line supports ${stream.format}."); return
            }
            val c = AudioSystem.getClip()
            c.open(stream)
            c.addLineListener { e ->
                if (e.type == LineEvent.Type.STOP) { c.close(); try { src.close() } catch (_: Exception) {}; synchronized(this) { next() } }
            }
            clip = c
            setBody("&#9654; Playing: <b>${escapeHtml(f.name)}</b>" +
                "<br><span style='color:#888'>${base.sampleRate.toInt()} Hz · ${base.sampleSizeInBits}-bit · ${base.channels}ch · out: ${escapeHtml(defaultOut())}</span>")
            c.start()
        } catch (e: Exception) { fail(f, e.message ?: e.toString()) }
    }

    private fun fail(f: File, msg: String) {
        failedAny = true
        System.err.println("play failed ${f.name}: $msg")
        setBody("&#9888; <b>Playback failed</b>: ${escapeHtml(f.name)}" +
            "<br><span style='color:#f88'>${escapeHtml(msg)}</span>" +
            "<br><br><span style='color:#888'>Audio outputs Java can see:</span><br>${outputsHtml()}")
        next()
    }

    @Synchronized fun stop() { try { clip?.stop(); clip?.close() } catch (_: Exception) {}; clip = null; queue.clear() }

    // ---- mini player dialog ----------------------------------------------------------------------

    private fun ensureDialog(): JDialog {
        dialog?.let { return it }
        val d = JDialog(null as java.awt.Frame?, "Player")
        val lbl = JLabel(" ").apply { border = BorderFactory.createEmptyBorder(12, 14, 8, 14) }
        d.contentPane.layout = java.awt.BorderLayout()
        d.contentPane.add(lbl, java.awt.BorderLayout.CENTER)
        d.contentPane.add(JPanel().apply { add(JButton("Stop").apply {
            addActionListener { stop(); SwingUtilities.invokeLater { dialog?.isVisible = false } } }) },
            java.awt.BorderLayout.SOUTH)
        d.defaultCloseOperation = JDialog.HIDE_ON_CLOSE
        d.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) { stop() }
        })
        d.isAlwaysOnTop = true
        d.setSize(460, 150)
        d.setLocationRelativeTo(null)
        label = lbl; dialog = d
        return d
    }

    private fun setBody(html: String) = SwingUtilities.invokeLater { ensureDialog(); label?.text = "<html>$html</html>" }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** The default output mixer's name (best effort). */
    private fun defaultOut(): String = try { AudioSystem.getMixer(null).mixerInfo.name } catch (e: Exception) { "(default)" }

    /** Names of mixers that can play audio (have output lines) — so a wrong/missing speaker is visible. */
    private fun outputsHtml(): String = try {
        AudioSystem.getMixerInfo()
            .filter { AudioSystem.getMixer(it).sourceLineInfo.isNotEmpty() }
            .joinToString("<br>") { "&bull; ${escapeHtml(it.name)}" }
            .ifEmpty { "(none — Java sees no audio output device)" }
    } catch (e: Exception) { "(could not enumerate)" }
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
        val nf = frames.size

        // Per-coefficient z-score normalization across time. MFCC coeff 0 (log-energy) and the higher
        // coeffs live on very different scales, so a single global min/max washes everything to one
        // color; normalizing each coefficient against its OWN mean/std gives a balanced heatmap.
        val mean = DoubleArray(nc); val std = DoubleArray(nc)
        for (fr in frames) for (c in 0 until nc) mean[c] += fr[c]
        for (c in 0 until nc) mean[c] /= nf
        for (fr in frames) for (c in 0 until nc) { val d = fr[c] - mean[c]; std[c] += d * d }
        for (c in 0 until nc) std[c] = sqrt(std[c] / nf)

        val cw = w.toDouble() / nf
        val ch = h.toDouble() / nc
        for (fi in frames.indices) {
            val fr = frames[fi]
            for (c in 0 until nc) {
                val z = if (std[c] > 1e-9) (fr[c] - mean[c]) / std[c] else 0.0
                g.color = divergingColor((z / 2.5).coerceIn(-1.0, 1.0))   // ±2.5σ → full blue/red
                val x = (fi * cw).toInt()
                val y = (h - (c + 1) * ch).toInt()   // coeff 0 at the bottom
                g.fillRect(x, y, max(1, (cw + 1).toInt()), max(1, (ch + 1).toInt()))
            }
        }
        g.dispose()
        return img
    }

    /** Diverging blue(-1) → white(0) → red(+1) map for a normalized value [t] in [-1, 1]. */
    private fun divergingColor(t: Double): Color = if (t < 0) {
        val f = (t + 1).coerceIn(0.0, 1.0)                       // -1→blue, 0→white
        Color((255 * f).toInt(), (255 * f).toInt(), 255)
    } else {
        val f = (1 - t).coerceIn(0.0, 1.0)                       // 0→white, +1→red
        Color(255, (255 * f).toInt(), (255 * f).toInt())
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
    private val cwtCache = ConcurrentHashMap<String, ImageIcon>()
    private val renderPool = Executors.newFixedThreadPool(
        max(1, Runtime.getRuntime().availableProcessors() / 2))
    private val pending = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Notified (with the surviving recordings) after a delete/move so the host can sync its list. */
    var onRecordingsChanged: ((List<AudioRecording>) -> Unit)? = null

    private val THUMB_W = 190
    private val THUMB_H = 104

    private val model = object : AbstractTableModel() {
        private val cols = arrayOf("✓", "FFT", "Comments", "MFCC", "Wavelet")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getColumnClass(c: Int): Class<*> = when (c) {
            0 -> java.lang.Boolean::class.java
            1, 3, 4 -> ImageIcon::class.java
            else -> String::class.java
        }
        override fun isCellEditable(r: Int, c: Int) = c == 0
        override fun getValueAt(r: Int, c: Int): Any? {
            val row = rows[r]
            return when (c) {
                0 -> row.checked
                1 -> fftIcon(row.rec)
                2 -> commentHtml(row.rec)
                3 -> mfccIcon(row.rec)
                else -> cwtIcon(row.rec)   // c == 4: pre-generated CWT .jpg from disk
            }
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            if (c == 0) { rows[r].checked = v as? Boolean ?: false; fireTableCellUpdated(r, c); updateCount() }
        }
    }

    private val table = object : JTable(model) {
        override fun getToolTipText(e: MouseEvent): String? {
            val vr = rowAtPoint(e.point); val c = columnAtPoint(e.point)
            if (vr < 0) return super.getToolTipText(e)
            val r = convertRowIndexToModel(vr)
            return if (r in rows.indices && (c == 1 || c == 3 || c == 4)) rows[r].rec.audioFile.absolutePath
            else super.getToolTipText(e)
        }
    }

    private val sorter = javax.swing.table.TableRowSorter(model)
    private val searchField = JTextField(14)
    private val searchScope = JComboBox(arrayOf("in: All", "in: Filename", "in: Auto", "in: Manual"))
    private val showCombo = JComboBox(arrayOf("Show: All", "Show: Checked", "Show: Has comment", "Show: Duplicates"))
    private val countLabel = JLabel(" ")
    @Volatile private var dupIds: Set<String> = emptySet()
    @Volatile private var dupGroups: List<List<Row>> = emptyList()
    @Volatile private var dupComputed = false

    // Bidirectional synonyms for search (snore = snoring, …).
    private val synonyms = mapOf("snore" to "snoring", "snoring" to "snore")

    init {
        table.rowHeight = THUMB_H + 12
        table.fillsViewportHeight = true
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF   // fixed-width image cols + horizontal scroll
        table.columnModel.getColumn(0).apply { preferredWidth = 30; maxWidth = 36 }
        table.columnModel.getColumn(1).preferredWidth = THUMB_W + 12
        table.columnModel.getColumn(2).preferredWidth = 300
        table.columnModel.getColumn(3).preferredWidth = THUMB_W + 12
        table.columnModel.getColumn(4).preferredWidth = THUMB_W + 12

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
        table.columnModel.getColumn(4).cellRenderer = IconRenderer()

        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val vr = table.rowAtPoint(e.point); val c = table.columnAtPoint(e.point)
                if (vr < 0) return
                val r = table.convertRowIndexToModel(vr)
                if (r in rows.indices && c == 1) AudioPlayer.playSequence(listOf(rows[r].rec.audioFile))
            }
        })

        // Filter / search / selection toolbar.
        table.rowSorter = sorter
        searchField.toolTipText = "Search (synonyms apply: snore = snoring). Scope it with the 'in:' selector."
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = applyFilter()
        })
        searchScope.addActionListener { applyFilter() }
        showCombo.addActionListener {
            if (showCombo.selectedIndex == 3 && !dupComputed) computeDuplicatesThen { applyFilter() } else applyFilter()
        }
        val bar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2))
        bar.add(JLabel("Find:")); bar.add(searchField); bar.add(searchScope); bar.add(showCombo)
        bar.add(JButton("✓ screen").apply { toolTipText = "Check only the rows currently on screen"; addActionListener { setVisibleChecked(true) } })
        bar.add(JButton("✗ screen").apply { toolTipText = "Uncheck only the rows currently on screen"; addActionListener { setVisibleChecked(false) } })
        bar.add(JButton("✗ all").apply { addActionListener { deselectAll() } })
        bar.add(JButton("Delete dups…").apply { toolTipText = "Delete redundant copies (keep one per content-identical group)"; addActionListener { deleteDuplicates() } })
        bar.add(JButton("↻ decodes").apply { toolTipText = "Reload phoneme decodes from data/codebooks (after re-running the codebook)"; addActionListener { reloadDecodes() } })
        bar.add(countLabel)
        add(bar, java.awt.BorderLayout.NORTH)
        add(JScrollPane(table), java.awt.BorderLayout.CENTER)
    }

    // ---- filtering / search / selection ----------------------------------------------------------

    private fun applyFilter() {
        val q = searchField.text.trim().lowercase()
        val mode = showCombo.selectedIndex
        sorter.rowFilter = object : javax.swing.RowFilter<javax.swing.table.TableModel, Int>() {
            override fun include(entry: Entry<out javax.swing.table.TableModel, out Int>): Boolean {
                val row = rows.getOrNull(entry.identifier) ?: return false
                when (mode) {
                    1 -> if (!row.checked) return false                       // Checked
                    2 -> if (!hasComment(row.rec)) return false               // Has comment
                    3 -> if (row.rec.id !in dupIds) return false              // Duplicates
                }
                return q.isEmpty() || matches(searchable(row.rec, searchScope.selectedIndex), q)
            }
        }
        updateCount()
    }

    private fun matches(hay: String, query: String): Boolean =
        query.split(" ").filter { it.isNotBlank() }.all { w ->
            (listOf(w) + (synonyms[w]?.let { listOf(it) } ?: emptyList())).any { hay.contains(it) }
        }

    private fun searchable(rec: AudioRecording, scope: Int): String = when (scope) {
        1 -> (rec.id + " " + rec.audioFile.name).lowercase()                                  // Filename
        2 -> rec.metadata.entries.filter { it.key != "comment" }                              // Auto (metadata, sans the manual comment)
                 .joinToString(" ") { "${it.key} ${it.value}" }.lowercase()
        3 -> (ManualComments.get(rec.id) ?: "").lowercase()                                   // Manual
        else -> buildString {                                                                 // All
            append(rec.id.lowercase()); append(' ')
            rec.metadata.values.forEach { append(it.toString().lowercase()); append(' ') }
            ManualComments.get(rec.id)?.let { append(it.lowercase()) }
        }
    }

    private fun hasComment(rec: AudioRecording): Boolean =
        ManualComments.get(rec.id) != null || rec.metadata["comment"]?.toString()?.isNotBlank() == true

    /** Check/uncheck only the rows currently on screen (the scroll viewport), not all filtered rows. */
    private fun setVisibleChecked(checked: Boolean) {
        val vp = table.parent as? javax.swing.JViewport
        var first = 0; var last = table.rowCount - 1
        if (vp != null) {
            val r = vp.viewRect
            first = table.rowAtPoint(java.awt.Point(0, r.y)).let { if (it < 0) 0 else it }
            last = table.rowAtPoint(java.awt.Point(0, r.y + r.height - 1)).let { if (it < 0) table.rowCount - 1 else it }
        }
        for (vr in first..last) if (vr in 0 until table.rowCount) rows[table.convertRowIndexToModel(vr)].checked = checked
        model.fireTableDataChanged(); applyFilter()
    }

    private fun deselectAll() { rows.forEach { it.checked = false }; model.fireTableDataChanged(); applyFilter() }

    private fun markDecode(targets: List<AudioRecording>, correct: Boolean) {
        DecodeFeedback.setAll(targets.map { it.id }, correct); model.fireTableDataChanged()
    }

    private fun reloadDecodes() { DecodeStore.reload(); model.fireTableDataChanged() }

    private fun updateCount() {
        countLabel.text = "  ${table.rowCount} shown · ${rows.size} total · ${rows.count { it.checked }} checked"
    }

    /** Background: flag recordings whose audio content is byte-identical to another (size-grouped, then MD5). */
    private fun computeDuplicatesThen(after: () -> Unit) {
        countLabel.text = "  finding duplicates…"
        renderPool.submit {
            val groups = ArrayList<List<Row>>()
            val dups = HashSet<String>()
            rows.groupBy { it.rec.audioFile.length() }.forEach { (sz, group) ->
                if (sz > 0L && group.size >= 2)
                    group.groupBy { hashFile(it.rec.audioFile) }
                        .forEach { (h, g) -> if (h != null && g.size > 1) { groups.add(g); g.forEach { dups.add(it.rec.id) } } }
            }
            dupIds = dups; dupGroups = groups; dupComputed = true
            SwingUtilities.invokeLater(after)
        }
    }

    /** Delete redundant copies — keep one per content-identical group, delete the rest (files + list). */
    private fun deleteDuplicates() {
        if (!dupComputed) { computeDuplicatesThen { deleteDuplicates() }; return }
        val groups = dupGroups
        val toDelete = groups.flatMap { it.drop(1) }   // keep the first of each group
        if (toDelete.isEmpty()) { showInfo("No content-duplicate clips found."); return }
        if (JOptionPane.showConfirmDialog(this,
                "Delete ${toDelete.size} duplicate copy/copies (keeping one per ${groups.size} group)?\nFiles are removed from disk.",
                "Delete duplicates", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        val ids = toDelete.map { it.rec.id }.toSet()
        var del = 0
        for (r in toDelete) if (r.rec.audioFile.delete() || !r.rec.audioFile.exists()) del++
        rows.removeAll { it.rec.id in ids }
        dupComputed = false; dupIds = emptySet(); dupGroups = emptyList()
        model.fireTableDataChanged(); applyFilter()
        onRecordingsChanged?.invoke(rows.map { it.rec })
        showInfo("Deleted $del duplicate(s).")
    }

    private fun hashFile(f: File): String? = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val vr = table.rowAtPoint(e.point)
        if (vr < 0) return
        val r = table.convertRowIndexToModel(vr)
        if (r !in rows.indices) return
        val targets = checkedOrRow(r)
        JPopupMenu().apply {
            add(JMenuItem("Play (${targets.size})").apply { addActionListener { AudioPlayer.playSequence(targets.map { it.audioFile }) } })
            add(JMenuItem("Add manual comment…").apply { addActionListener { addComment(targets) } })
            add(JMenuItem("Move…").apply { addActionListener { moveFiles(targets) } })
            addSeparator()
            add(JMenuItem("Decode ✓ correct (${targets.size})").apply { addActionListener { markDecode(targets, true) } })
            add(JMenuItem("Decode ✗ error (${targets.size})").apply { addActionListener { markDecode(targets, false) } })
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
        fftCache.clear(); mfccCache.clear(); cwtCache.clear()
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
        // Fold mobile .txt-sidecar comments (metadata["comment"]) into the JSON manual store so the
        // two are on equal footing (only where no desktop comment already exists).
        ManualComments.importAll(list.mapNotNull { r ->
            r.metadata["comment"]?.toString()?.takeIf { it.isNotBlank() }?.let { r.id to it } })
        rows.clear()
        list.forEach { rows.add(Row(it)) }
        dupComputed = false; dupIds = emptySet(); dupGroups = emptyList()
        model.fireTableDataChanged(); applyFilter()
    }

    fun checkedRecordings(): List<AudioRecording> = rows.filter { it.checked }.map { it.rec }

    // ---- thumbnails (rendered off-EDT, cached, repaint on completion) ----------------------------

    private fun commentHtml(rec: AudioRecording): String {
        val manual = ManualComments.get(rec.id)
        // Surface the recording's metadata (coswara attrs, source, etc.). "category" duplicates
        // "sound_type", so skip it; everything else is shown key=value.
        val skip = setOf("category", "comment")   // "comment" is shown as the manual ✍ line, not auto
        val meta = rec.metadata.entries
            .filter { it.key !in skip && it.value.toString().isNotBlank() }
            .joinToString(" · ") { "${it.key}=${escape(it.value.toString().take(60))}" }
        return buildString {
            append("<html><b>").append(escape(rec.id)).append("</b>")
            if (manual != null) append("<br><span style='color:#7fd'>✍ ").append(escape(manual)).append("</span>")
            if (meta.isNotBlank()) append("<br><span style='color:#999'>").append(meta).append("</span>")
            else rec.label()?.let { append("<br><span style='color:#999'>").append(escape(it)).append("</span>") }
            DecodeStore.get(rec.id)?.let { dec ->                       // phoneme-codebook decode, coloured by class
                val fb = DecodeFeedback.get(rec.id)?.let { if (it) " ✓" else " ✗" } ?: ""
                val w = dec.word.take(10).joinToString(" ") + if (dec.word.size > 10) " …" else ""
                append("<br><span style='color:${letterColor(dec.letter)}'>≈ ")
                    .append(escape(dec.letter)).append(": ").append(escape(w)).append(fb).append("</span>")
            }
            append("</html>")
        }
    }

    private fun letterColor(l: String): String = when (l) {
        "S" -> "#5cf"; "B" -> "#f77"; "N" -> "#999"; "D" -> "#fb5"; "SP" -> "#9d9"
        "C" -> "#c9f"; "SN" -> "#fc9"; "EP" -> "#dd9"; "?" -> "#777"; else -> "#bbb"
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

    /** Pre-generated CWT scalogram (`<wav>.jpg` from the CWT image batch) — loaded from disk off the
     *  EDT, cached, never recomputed (CWT is the heavy one). Null (placeholder) when the jpg is absent
     *  — generate it with the CWT CPU/GPU button. */
    private fun cwtIcon(rec: AudioRecording): ImageIcon? {
        cwtCache[rec.id]?.let { return it }
        val jpg = File(rec.audioFile.parentFile, "${rec.audioFile.nameWithoutExtension}.jpg")
        if (!jpg.isFile) return null
        val key = "cwt:" + rec.id
        if (pending.add(key)) {
            renderPool.submit {
                try {
                    val img = ImageIO.read(jpg)
                    if (img != null) {
                        cwtCache[rec.id] = ImageIcon(img.getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH))
                        SwingUtilities.invokeLater {
                            val r = rows.indexOfFirst { it.rec.id == rec.id }
                            if (r >= 0) model.fireTableRowsUpdated(r, r)
                        }
                    }
                } catch (e: Exception) { System.err.println("cwt load ${rec.id}: ${e.message}") }
                finally { pending.remove(key) }
            }
        }
        return null
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
