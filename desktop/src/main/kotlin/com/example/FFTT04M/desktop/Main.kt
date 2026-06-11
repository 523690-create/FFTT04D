package com.example.FFTT04M.desktop

import com.example.FFTT04M.desktop.cough.CoughEvent
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.*
import java.io.File
import kotlin.concurrent.thread

fun main() {
    SwingUtilities.invokeLater {
        AnalyzerWindow().isVisible = true
    }
}

class AnalyzerWindow : JFrame("Cough Analysis Desktop") {
    private var selectedDataset: Dataset? = null
    private var datasetPath = ""
    private var isAnalyzing = false
    private val recordings = mutableListOf<AudioRecording>()
    private val engine = ParallelCoughAnalyzer()
    private var lastResults: List<ParallelCoughAnalyzer.ClipResult> = emptyList()

    private val statusLabel = JLabel("Ready")
    private val recordingsList = JList<String>(DefaultListModel())
    private val progressBar = JProgressBar(0, 100)
    private val analysisResultsArea = JTextArea(10, 60)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        size = Dimension(1000, 700)
        setLocationRelativeTo(null)

        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Title
        val titleLabel = JLabel("Cough Analysis Desktop — Tier-1 DSP, ${engine.workers} cores")
        titleLabel.font = Font("Dialog", Font.BOLD, 24)
        panel.add(titleLabel, BorderLayout.NORTH)

        // Central panel with split view
        val centerPanel = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

        // Left: Controls and list
        val leftPanel = JPanel(BorderLayout(5, 5))

        // Dataset selection buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(createButton("Load Cough Dataset 1") {
            datasetPath = "H:\\cough dataset 1"
            selectedDataset = Dataset.COUGH_DATASET_1
            loadDataset()
        })
        buttonPanel.add(createButton("Load ESC-50") {
            datasetPath = "H:\\ESC-50-master"
            selectedDataset = Dataset.ESC_50
            loadDataset()
        })
        buttonPanel.add(createButton("Load Coswara") {
            datasetPath = "H:\\Coswara-Data-master"
            selectedDataset = Dataset.COSWARA
            loadDataset()
        })
        buttonPanel.add(createButton("Request from USB Device") {
            loadFromUsb()
        })
        leftPanel.add(buttonPanel, BorderLayout.NORTH)

        // Recordings list
        val listLabel = JLabel("Loaded Recordings:")
        listLabel.font = Font("Dialog", Font.BOLD, 12)
        val listPanel = JPanel(BorderLayout(5, 5))
        listPanel.add(listLabel, BorderLayout.NORTH)
        (recordingsList.model as DefaultListModel<String>).clear()
        listPanel.add(JScrollPane(recordingsList), BorderLayout.CENTER)
        leftPanel.add(listPanel, BorderLayout.CENTER)

        // Analysis controls
        val analysisPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val startButton = createButton("Analyze All") {
            if (recordings.isNotEmpty()) analyzeAll() else showStatus("No recordings loaded")
        }
        val exportButton = createButton("Export segments.jsonl") {
            exportJsonl()
        }
        val metaButton = createButton("Meta-Analysis (Tensor)") {
            metaAnalysis()
        }
        analysisPanel.add(startButton)
        analysisPanel.add(metaButton)
        analysisPanel.add(exportButton)
        leftPanel.add(analysisPanel, BorderLayout.SOUTH)

        // Right: Results display
        val rightPanel = JPanel(BorderLayout(5, 5))
        val resultsLabel = JLabel("Analysis Results:")
        resultsLabel.font = Font("Dialog", Font.BOLD, 12)
        rightPanel.add(resultsLabel, BorderLayout.NORTH)

        analysisResultsArea.isEditable = false
        analysisResultsArea.font = Font("Monospaced", Font.PLAIN, 10)
        rightPanel.add(JScrollPane(analysisResultsArea), BorderLayout.CENTER)

        centerPanel.leftComponent = leftPanel
        centerPanel.rightComponent = rightPanel
        centerPanel.dividerLocation = 400
        panel.add(centerPanel, BorderLayout.CENTER)

        // Bottom panel: progress + status
        val bottomPanel = JPanel(BorderLayout(0, 5))
        progressBar.isStringPainted = true
        bottomPanel.add(progressBar, BorderLayout.NORTH)
        statusLabel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        bottomPanel.add(statusLabel, BorderLayout.SOUTH)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        contentPane = panel
    }

    private fun loadDataset() {
        thread {
            showStatus("Loading ${selectedDataset?.displayName}...")
            recordings.clear()
            val list = when (selectedDataset) {
                Dataset.COUGH_DATASET_1 -> DatasetLoader.loadCoughDataset1(datasetPath)
                Dataset.ESC_50 -> DatasetLoader.loadESC50(datasetPath)
                Dataset.COSWARA -> DatasetLoader.loadCoswara(datasetPath)
                else -> emptyList()
            }
            recordings.addAll(list)
            updateRecordingsList()
            showStatus("Loaded ${recordings.size} recordings from ${selectedDataset?.displayName}")
        }
    }

    /** Pull recordings + metadata off a USB-connected Android device (legacy or modern) via adb. */
    private fun loadFromUsb() {
        if (isAnalyzing) { showStatus("Busy analyzing…"); return }
        thread {
            if (!UsbImporter.adbAvailable()) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this,
                        "adb not found. Install Android platform-tools, or set ANDROID_HOME.",
                        "USB import", JOptionPane.WARNING_MESSAGE)
                }
                showStatus("adb not found"); return@thread
            }
            showStatus("Scanning for USB devices…")
            val devices = UsbImporter.listDevices()
            if (devices.isEmpty()) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this,
                        "No authorized device found.\nConnect via USB, enable USB debugging, and accept the prompt.",
                        "USB import", JOptionPane.INFORMATION_MESSAGE)
                }
                showStatus("No USB device"); return@thread
            }
            // Pick the device (auto if one, else ask on the EDT).
            val device = if (devices.size == 1) devices[0] else {
                val labels = devices.map { "${it.model} (${it.serial})" }.toTypedArray()
                val picked = arrayOfNulls<String>(1)
                SwingUtilities.invokeAndWait {
                    picked[0] = JOptionPane.showInputDialog(this, "Select device to import from:", "USB import",
                        JOptionPane.QUESTION_MESSAGE, null, labels, null) as String?
                }
                val choice = picked[0] ?: run { showStatus("USB import cancelled"); return@thread }
                devices[labels.indexOf(choice).coerceAtLeast(0)]
            }

            // Cooperative handshake: did the phone publish an offer (SHARE → "Offer to desktop")?
            showStatus("Checking ${device.model} for an offer…")
            val offer = UsbImporter.readOffer(device)
            if (offer != null) {
                val ageMin = if (offer.createdMs > 0)
                    ((System.currentTimeMillis() - offer.createdMs) / 60000).coerceAtLeast(0) else -1L
                val whenStr = if (ageMin in 0..600) "offered ${ageMin}m ago" else "offer pending"
                val accept = arrayOf<Int>(0)
                SwingUtilities.invokeAndWait {
                    accept[0] = JOptionPane.showConfirmDialog(this,
                        "${offer.deviceModel} is offering ${offer.count} recording(s) ($whenStr).\nAccept and import?",
                        "USB offer", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
                }
                if (accept[0] != JOptionPane.YES_OPTION) { showStatus("USB offer declined"); return@thread }
            } else {
                val proceed = arrayOf<Int>(0)
                SwingUtilities.invokeAndWait {
                    proceed[0] = JOptionPane.showConfirmDialog(this,
                        "No active offer from ${device.model}.\nTip: on the phone tap SHARE → \"Offer recordings to desktop (USB)\".\n\nPull whatever is already staged anyway?",
                        "USB import", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
                }
                if (proceed[0] != JOptionPane.YES_OPTION) { showStatus("USB import cancelled"); return@thread }
            }

            showStatus("Pulling recordings from ${device.model} via USB…")
            val importRoot = File(System.getProperty("user.home"), "FFTT04M_usb_import")
            val res = UsbImporter.pull(device, importRoot)
            if (!res.ok) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this, res.message, "USB import", JOptionPane.WARNING_MESSAGE)
                }
                showStatus(res.message); return@thread
            }
            recordings.clear()
            recordings.addAll(DatasetLoader.loadDeviceImport(res.dir, device.model))
            selectedDataset = null
            updateRecordingsList()
            // Acknowledge back to the phone so its Offer dialog confirms the transfer.
            val acked = UsbImporter.sendAck(device, res.wavCount)
            showStatus("USB: imported ${recordings.size} from ${device.model}" +
                if (acked) " · acknowledged to phone" else "")
        }
    }

    private fun updateRecordingsList() {
        SwingUtilities.invokeLater {
            val model = recordingsList.model as DefaultListModel<String>
            model.clear()
            recordings.forEach { rec ->
                val label = rec.label() ?: "unknown"
                model.addElement("${rec.id}: $label")
            }
        }
    }

    private fun analyzeAll() {
        if (isAnalyzing) return
        isAnalyzing = true
        thread {
            SwingUtilities.invokeLater { analysisResultsArea.text = "" }
            progressBar.value = 0
            val n = recordings.size
            val startNs = System.nanoTime()
            showStatus("Analyzing $n recordings on ${engine.workers} cores (full Tier-1 DSP)...")

            // Fan the full Tier-1 cough engine across every CPU core.
            val results = engine.analyzeAll(recordings) { doneCount, total ->
                SwingUtilities.invokeLater { progressBar.value = doneCount * 100 / total }
                if (doneCount % 5 == 0 || doneCount == total) {
                    showStatus("Analyzed $doneCount/$total on ${engine.workers} cores...")
                }
            }
            lastResults = results

            val elapsedS = (System.nanoTime() - startNs) / 1e9
            val analyzed = results.count { it.analysis != null }
            val skipped = results.size - analyzed
            val totalEvents = results.sumOf { it.analysis?.events?.size ?: 0 }
            val totalCoughs = results.sumOf { it.analysis?.coughCount ?: 0 }

            val sb = StringBuilder()
            sb.append("=== Tier-1 DSP ANALYSIS (${engine.workers} cores) ===\n")
            sb.append(String.format("%d clips in %.1fs  ·  %.1f clips/s\n", n, elapsedS,
                if (elapsedS > 0) n / elapsedS else 0.0))
            sb.append("$totalEvents events detected · $totalCoughs cough-like · $skipped skipped\n\n")
            for (r in results) sb.append(formatResult(r))
            val out = sb.toString()
            SwingUtilities.invokeLater { analysisResultsArea.text = out; analysisResultsArea.caretPosition = 0 }

            showStatus(String.format(
                "Done: %d analyzed, %d skipped, %d events in %.1fs on %d cores",
                analyzed, skipped, totalEvents, elapsedS, engine.workers))
            isAnalyzing = false
            progressBar.value = 100
        }
    }

    /** Render one clip's full feature set (mirrors the blue_sky on-device analysis text). */
    private fun formatResult(r: ParallelCoughAnalyzer.ClipResult): String {
        val sb = StringBuilder()
        sb.append("${r.recording.id}")
        r.recording.label()?.let { sb.append("  [$it]") }
        sb.append("\n")
        if (r.analysis == null) {
            sb.append("  (${r.error ?: "no analysis"})\n\n")
            return sb.toString()
        }
        val a = r.analysis
        sb.append(String.format("  %.2fs · %d event(s) · %d cough-like\n",
            r.durationSec, a.events.size, a.coughCount))
        for (e in a.events) sb.append(formatEvent(e))
        sb.append("\n")
        return sb.toString()
    }

    private fun formatEvent(e: CoughEvent): String {
        val sb = StringBuilder()
        sb.append(String.format("    #%d  %.2f–%.2fs\n", e.index, e.segment.startSec, e.segment.endSec))
        sb.append(String.format("      FFT   Q=%.2f  Fmax=%.0fHz\n", e.fft.qRatio, e.fft.fmaxHz))
        if (e.ridge.valid) {
            sb.append(String.format("      Ridge f0=%.0fHz  curv=%.0f  slope=%.0f  R²=%.2f\n",
                e.ridge.centerFreqHz, e.ridge.curvature, e.ridge.slope, e.ridge.rSquared))
        } else {
            sb.append("      Ridge (insufficient points)\n")
        }
        e.phases?.let { sb.append(String.format("      Phase T1=%.3fs T2=%.3fs T3=%.3fs\n", it.t1Sec, it.t2Sec, it.t3Sec)) }
        e.mfcc?.let {
            val c = it.mean.take(4).joinToString(", ") { v -> String.format("%.1f", v) }
            sb.append("      MFCC[$c, ...]\n")
        }
        sb.append(String.format("      %s (speech-likelihood %.2f, flatness %.2f, pitch %.2f)\n",
            if (e.speech.isLikelyCough) "COUGH" else "not-cough",
            e.speech.speechLikelihood, e.speech.spectralFlatness, e.speech.pitchStrength))
        return sb.toString()
    }

    private val exportPrefs = java.util.prefs.Preferences.userRoot().node("FFTT04D/export")

    private fun exportJsonl() {
        if (lastResults.isEmpty()) { showStatus("Nothing to export — run Analyze All first"); return }

        // Remember the last-used directory across runs (defaults to Documents the first time).
        val lastDir = exportPrefs.get("dir", null)?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Documents")

        val chooser = JFileChooser(lastDir).apply {
            dialogTitle = "Export segments.jsonl"
            // Suggest the next non-colliding name so existing exports are never overwritten.
            selectedFile = nextFreeFile(lastDir, "segments", "jsonl")
            fileFilter = FileNameExtensionFilter("JSON Lines (*.jsonl)", "jsonl")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return

        var target = chooser.selectedFile
        if (target.extension.lowercase() != "jsonl") target = File(target.parentFile, target.name + ".jsonl")
        // Even if the user kept/typed an existing name, bump to the next free index — never overwrite.
        target = incrementUntilFree(target)
        target.parentFile?.let { exportPrefs.put("dir", it.absolutePath) }

        val out = target
        thread {
            try {
                val jsonl = engine.toSegmentsJsonl(lastResults)
                out.writeText(jsonl)
                val lines = jsonl.count { it == '\n' }
                showStatus("Exported $lines segment rows -> ${out.name}  (in ${out.parent})")
            } catch (e: Exception) {
                showStatus("Export failed: ${e.message}")
            }
        }
    }

    /**
     * Build the big cough feature tensor over all analysed events, z-score it, measure Euclidean
     * distances (nearest neighbours + pairwise stats), show a report, and offer a CSV export.
     */
    private fun metaAnalysis() {
        if (lastResults.isEmpty()) { showStatus("Run Analyze All first, then Meta-Analysis"); return }
        if (isAnalyzing) { showStatus("Busy analyzing…"); return }
        thread {
            showStatus("Building cough tensor…")
            val tensor = MetaAnalyzer.buildTensor(lastResults)
            if (tensor.n == 0) { showStatus("No cough events to tensorize"); return@thread }
            val s = MetaAnalyzer.analyze(tensor)

            val sb = StringBuilder()
            sb.append("=== META-ANALYSIS: cough tensor ===\n")
            sb.append("tensor shape: ${s.n} events × ${s.dim} features\n")
            sb.append("cough-like events: ${s.coughCount} / ${s.n}\n")
            sb.append("features: ${MetaAnalyzer.featureNames.joinToString(", ")}\n\n")
            if (s.pairwiseComputed) {
                sb.append(String.format("pairwise Euclidean distance — mean %.3f, min %.3f, max %.3f%n",
                    s.meanDist, s.minDist, s.maxDist))
            } else {
                sb.append("(>3000 events: skipped full pairwise matrix; nearest-neighbours below)\n")
            }
            sb.append("\nnearest neighbour (most similar cough) — sampled:\n")
            for ((a, b, d) in s.nnExamples) sb.append(String.format("  %-28s ~ %-28s  d=%.3f%n", a, b, d))
            val report = sb.toString()
            SwingUtilities.invokeLater { analysisResultsArea.text = report; analysisResultsArea.caretPosition = 0 }
            showStatus("Tensor: ${s.n}×${s.dim}. Export tensor CSV? use the dialog…")

            // Offer to save the standardized tensor as CSV.
            SwingUtilities.invokeLater { maybeExportTensor(tensor) }
        }
    }

    private fun maybeExportTensor(tensor: MetaAnalyzer.Tensor) {
        val lastDir = exportPrefs.get("dir", null)?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Documents")
        val chooser = JFileChooser(lastDir).apply {
            dialogTitle = "Export cough tensor (CSV) — Cancel to skip"
            selectedFile = nextFreeFile(lastDir, "cough_tensor", "csv")
            fileFilter = FileNameExtensionFilter("CSV (*.csv)", "csv")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) { showStatus("Tensor not exported"); return }
        var target = chooser.selectedFile
        if (target.extension.lowercase() != "csv") target = File(target.parentFile, target.name + ".csv")
        target = incrementUntilFree(target)
        target.parentFile?.let { exportPrefs.put("dir", it.absolutePath) }
        val out = target
        thread {
            try {
                out.writeText(MetaAnalyzer.tensorCsv(tensor))
                showStatus("Tensor exported: ${out.name} (${tensor.n}×${tensor.dim}) in ${out.parent}")
            } catch (e: Exception) { showStatus("Tensor export failed: ${e.message}") }
        }
    }

    /** `<base>.<ext>` if free, else the first free `<base>_NNN.<ext>` in [dir]. */
    private fun nextFreeFile(dir: File, base: String, ext: String): File =
        incrementUntilFree(File(dir, "$base.$ext"))

    /** Returns [file] if it doesn't exist, else `<stem>_NNN.<ext>` with the lowest free NNN. */
    private fun incrementUntilFree(file: File): File {
        if (!file.exists()) return file
        val dir = file.parentFile
        val name = file.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val cand = File(dir, String.format("%s_%03d%s", stem, i, ext))
            if (!cand.exists()) return cand
            i++
        }
    }

    private fun showStatus(message: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = message
        }
    }

    private fun createButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            addActionListener { action() }
        }
    }
}

enum class Dataset(val displayName: String) {
    COUGH_DATASET_1("Cough Dataset 1"),
    ESC_50("ESC-50"),
    COSWARA("Coswara"),
}
