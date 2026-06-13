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
    // Active tasks each get their own bar stacked here, so concurrent passes don't fight one bar.
    private val progressStack = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val analysisResultsArea = JTextArea(10, 60)

    private lateinit var buildAllDataButton: JButton
    @Volatile private var buildingAllData = false

    private lateinit var fftButton: JButton
    private lateinit var cwtCpuButton: JButton
    private lateinit var cwtGpuButton: JButton
    // Per-button cancel flags: each image pass runs independently so CPU and GPU CWT (and FFT) can
    // run concurrently — the GPU offloads its FFTs while the CPU cores keep working on the others.
    private val imagingTokens =
        java.util.concurrent.ConcurrentHashMap<JButton, java.util.concurrent.atomic.AtomicBoolean>()

    private lateinit var isolateButton: JButton
    @Volatile private var isolating = false
    private val isolatingToken = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        size = Dimension(1000, 700)
        setLocationRelativeTo(null)

        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Title row: name on the left, the per-build version letter right-adjusted (magenta, to
        // match the mobile launcher-icon letter — see BuildInfo / generateVersionLetter).
        val titleBar = JPanel(BorderLayout())
        val titleLabel = JLabel("Cough Analysis Desktop — Tier-1 DSP, ${engine.workers} cores")
        titleLabel.font = Font("Dialog", Font.BOLD, 24)
        titleBar.add(titleLabel, BorderLayout.WEST)
        BuildInfo.versionLetter.takeIf { it.isNotEmpty() }?.let { letter ->
            val letterLabel = JLabel(letter)
            letterLabel.font = Font("Dialog", Font.BOLD, 24)
            letterLabel.foreground = Color(0xFF, 0x00, 0xFF)   // magenta, like the launcher letter
            letterLabel.border = BorderFactory.createEmptyBorder(0, 12, 0, 8)
            letterLabel.toolTipText = "Build version letter"
            titleBar.add(letterLabel, BorderLayout.EAST)
        }
        panel.add(titleBar, BorderLayout.NORTH)

        // Central panel with split view
        val centerPanel = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

        // Left: Controls and list
        val leftPanel = JPanel(BorderLayout(5, 5))

        // Dataset selection buttons
        // GridLayout (not FlowLayout): a BorderLayout NORTH region only grants a component its
        // preferred height, and FlowLayout reports a single row — extra wrapped buttons get clipped.
        // GridLayout's preferred height counts every row, so all buttons stay visible.
        val buttonPanel = JPanel(GridLayout(0, 2, 6, 6))
        buttonPanel.add(createButton("Load Cough Dataset 1") {
            val dir = pickDirectory("Select Cough Dataset 1 folder (holds public_dataset\\)",
                "coughDataset1", "H:\\cough dataset 1") ?: return@createButton
            datasetPath = dir.absolutePath
            selectedDataset = Dataset.COUGH_DATASET_1
            loadDataset()
        })
        buttonPanel.add(createButton("Load ESC-50") {
            val dir = pickDirectory("Select ESC-50 folder (holds audio\\ and meta\\esc50.csv)",
                "esc50", "H:\\ESC-50-master") ?: return@createButton
            datasetPath = dir.absolutePath
            selectedDataset = Dataset.ESC_50
            loadDataset()
        })
        buttonPanel.add(createButton("Load Coswara") {
            val dir = pickDirectory("Select Coswara folder (holds YYYYMMDD date folders)",
                "coswara", "H:\\Coswara-Data-master") ?: return@createButton
            datasetPath = dir.absolutePath
            selectedDataset = Dataset.COSWARA
            loadDataset()
        })
        buttonPanel.add(createButton("Request from USB Device") {
            loadFromUsb()
        })
        buildAllDataButton = createButton("Build ALLDATA") { onBuildAllData() }
        buttonPanel.add(buildAllDataButton)
        // Secondary image passes over a ready ALLDATA folder (resumable; skip clips already imaged).
        // The separate CPU/GPU wavelet buttons double as a live CPU-vs-GPU benchmark.
        fftButton = createButton(ImageBatch.Mode.FFT.button) { onGenerateImages(ImageBatch.Mode.FFT, fftButton) }
        cwtCpuButton = createButton(ImageBatch.Mode.CWT_CPU.button) { onGenerateImages(ImageBatch.Mode.CWT_CPU, cwtCpuButton) }
        cwtGpuButton = createButton(ImageBatch.Mode.CWT_GPU.button) { onGenerateImages(ImageBatch.Mode.CWT_GPU, cwtGpuButton) }
        buttonPanel.add(fftButton)
        buttonPanel.add(cwtCpuButton)
        buttonPanel.add(cwtGpuButton)
        // Trim cough WAVs down to the detected cough (ALLDATA + extras, picked at runtime).
        isolateButton = createButton("ISOLATE COUGHS") { onIsolateCoughs() }
        buttonPanel.add(isolateButton)
        // Cloud meta-analysis: measure your own (extras) recordings against ALLDATA clouds.
        buttonPanel.add(createButton("Cloud Match (extras)") { onCloudMatch() })
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
        val analysisPanel = JPanel(GridLayout(0, 2, 6, 6))
        val startButton = createButton("Analyze All") { analyzeAll() }
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

        // Bottom panel: stacked per-task progress bars + status
        val bottomPanel = JPanel(BorderLayout(0, 5))
        bottomPanel.add(progressStack, BorderLayout.NORTH)
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
            showStatus(
                if (recordings.isEmpty())
                    "No recordings found in $datasetPath — expected ${selectedDataset?.structureHint}"
                else
                    "Loaded ${recordings.size} recordings from ${selectedDataset?.displayName}"
            )
        }
    }

    /** Pull recordings + metadata off a USB-connected Android device (legacy or modern) via adb. */
    private fun loadFromUsb() {
        if (isAnalyzing) { showStatus("Busy analyzing…"); return }
        // Ask where to land the pulled recordings (on the EDT, before the worker thread starts).
        val importRoot = pickDirectory("Choose folder to import USB recordings into",
            "usbImport", File(System.getProperty("user.home"), "FFTT04M_usb_import").absolutePath)
            ?: run { showStatus("USB import cancelled"); return }
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

    /**
     * Consolidate every dataset under a sources root into one ALLDATA folder: all clips
     * transcoded to WAV, all metadata merged into one metadata.csv. Runs in the background;
     * the button toggles to "Cancel ALLDATA build" while running.
     */
    private fun onBuildAllData() {
        if (buildingAllData) { AllDataBuilder.cancel(); showStatus("Cancelling ALLDATA build…"); return }
        if (isAnalyzing) { showStatus("Busy analyzing…"); return }

        val sourcesRoot = pickDirectory(
            "Select the folder that CONTAINS the datasets (Coswara, ESC-50, coughvid, …)",
            "allDataSources", "C:\\AndroidStudio") ?: return
        val outDir = pickDirectory("Select the output ALLDATA folder",
            "allDataOut", "C:\\AndroidStudio\\ALLDATA") ?: return

        if (!AudioDecoder.ffmpegAvailable()) {
            JOptionPane.showMessageDialog(this,
                "ffmpeg not found. Install it (winget install Gyan.FFmpeg) and reopen.",
                "Build ALLDATA", JOptionPane.WARNING_MESSAGE)
            return
        }

        // Image rendering is the slow part and now has its own buttons (FFT images / CWT CPU / CWT
        // GPU) that run as a resumable second pass — so default this to No. (Cancel aborts the build.)
        val imgChoice = JOptionPane.showConfirmDialog(this,
            "Also render the 512×512 FFT (PNG) + Morlet-CWT (JPEG) images inline, per clip?\n\n" +
            "Recommended: No — build WAV + metadata only, then use the\n" +
            "\"FFT images\" / \"CWT images (CPU)\" / \"CWT images (GPU)\" buttons\n" +
            "to render (and resume) images separately.",
            "Build ALLDATA — images", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
        if (imgChoice == JOptionPane.CANCEL_OPTION || imgChoice == JOptionPane.CLOSED_OPTION) return
        AllDataBuilder.generateImages = (imgChoice == JOptionPane.YES_OPTION)

        buildingAllData = true
        val tp = TaskProgress("Build ALLDATA")
        SwingUtilities.invokeLater {
            buildAllDataButton.text = "Cancel ALLDATA build"
            analysisResultsArea.text = "Building ALLDATA from ${sourcesRoot.absolutePath}\n -> ${outDir.absolutePath}\n\n"
        }
        logLine("Build ALLDATA started — sources: ${sourcesRoot.absolutePath} → out: ${outDir.absolutePath} (images: ${if (AllDataBuilder.generateImages) "yes" else "no"})")
        thread {
            val startNs = System.nanoTime()
            var lastPhase = ""
            val summary = AllDataBuilder.build(sourcesRoot, outDir) { p ->
                tp.update(p.done, p.total, p.message)
                // Log dataset/scan/obstacle milestones to the results pane (not every per-clip tick).
                if (p.phase != lastPhase || p.phase in NOTABLE_PHASES) { logLine(p.message); lastPhase = p.phase }
            }
            val elapsedS = (System.nanoTime() - startNs) / 1e9
            val sb = StringBuilder()
            sb.append(if (summary.cancelled) "=== ALLDATA build CANCELLED ===\n" else "=== ALLDATA build complete ===\n")
            sb.append(String.format("%d rows · %d converted · %d reused · %d failed · %d images · %.1fs%n",
                summary.rows, summary.converted, summary.reused, summary.failed, summary.images, elapsedS))
            if (summary.csvPath.isNotEmpty()) sb.append("metadata: ${summary.csvPath}\n")
            sb.append("\nby source:\n")
            summary.bySource.forEach { (s, n) -> sb.append("  ${s.padEnd(16)} $n\n") }
            val report = sb.toString()
            SwingUtilities.invokeLater {
                analysisResultsArea.append(report)
                analysisResultsArea.caretPosition = analysisResultsArea.document.length
                buildAllDataButton.text = "Build ALLDATA"
                statusLabel.text = if (summary.cancelled)
                    "ALLDATA cancelled — ${summary.rows} rows written"
                else
                    "ALLDATA: ${summary.rows} clips, ${summary.failed} failed, in ${"%.1f".format(elapsedS)}s"
            }
            tp.finish()
            buildingAllData = false
        }
    }

    /**
     * Second-pass image generation over a ready ALLDATA folder. Each mode renders only the clips
     * that don't already have its image (resumable). Passes run **concurrently and independently**:
     * the clicked button toggles to "Cancel …" and owns its own cancel flag, so CPU and GPU CWT (and
     * FFT) can run at the same time — the GPU offloads its FFTs while the cores work the others.
     * Reports device + clips/s so CPU and GPU can be raced on real data.
     */
    private fun onGenerateImages(mode: ImageBatch.Mode, button: JButton) {
        // Already running on this button → cancel that pass.
        imagingTokens[button]?.let { it.set(true); showStatus("Cancelling ${mode.button}…"); return }

        if (mode == ImageBatch.Mode.CWT_GPU && !GpuFft.available()) {
            val go = JOptionPane.showConfirmDialog(this,
                "No NVIDIA GPU / cuFFT available — this pass will run on the CPU instead.\nProceed?",
                "GPU wavelets", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
            if (go != JOptionPane.OK_OPTION) return
        }
        // Defaults to the ALLDATA output folder used by Build ALLDATA (same pref key).
        val folder = pickDirectory("Select the ALLDATA folder (holds the .wav files)",
            "allDataOut", "C:\\AndroidStudio\\ALLDATA") ?: return

        val token = java.util.concurrent.atomic.AtomicBoolean(false)
        imagingTokens[button] = token
        val tp = TaskProgress(mode.button)
        SwingUtilities.invokeLater {
            button.text = "Cancel ${mode.button}"
            analysisResultsArea.append("\n${mode.label} over ${folder.absolutePath}\n")
            analysisResultsArea.caretPosition = analysisResultsArea.document.length
        }
        thread {
            logLine("${mode.button}: started over ${folder.name}")
            var nextLog = 0
            val summary = ImageBatch.run(folder, mode, token) { p ->
                tp.update(p.done, p.total, p.message)
                if (p.done >= nextLog || p.done == p.total) {
                    logLine("${mode.button}: ${p.message}"); nextLog = p.done + (p.total / 10).coerceAtLeast(50)
                }
            }
            val cps = if (summary.elapsedS > 0) summary.rendered / summary.elapsedS else 0.0
            val report = buildString {
                append(if (summary.cancelled) "=== image pass CANCELLED ===\n" else "=== image pass complete ===\n")
                append("${mode.label}  ·  device: ${summary.device}\n")
                append(String.format(
                    "%d rendered · %d skipped (already imaged) · %d failed · %.1fs · %.1f clips/s%n",
                    summary.rendered, summary.skipped, summary.failed, summary.elapsedS, cps))
            }
            SwingUtilities.invokeLater {
                analysisResultsArea.append(report)
                analysisResultsArea.caretPosition = analysisResultsArea.document.length
                button.text = mode.button
                statusLabel.text = if (summary.cancelled)
                    "Image pass cancelled — ${summary.rendered} rendered"
                else
                    "${mode.button}: ${summary.rendered} in ${"%.1f".format(summary.elapsedS)}s · " +
                    "${"%.1f".format(cps)} clips/s · ${summary.device}"
            }
            tp.finish()
            imagingTokens.remove(button)
        }
    }

    /**
     * ISOLATE COUGHS — trim every cough WAV in the chosen folders down to the detected cough span
     * (cutting before/after), overwriting each file under its original name and deleting its
     * .png/.jpg (recomputed from the shorter clip). Folders (ALLDATA + extras/USB) are picked at
     * runtime and remembered; non-cough clips are skipped; clips with no detected cough are left
     * untouched.
     */
    private fun onIsolateCoughs() {
        if (isolating) { isolatingToken.set(true); showStatus("Cancelling ISOLATE…"); return }

        val allData = pickDirectory("ISOLATE: ALLDATA folder (Cancel to skip it)",
            "allDataOut", "C:\\AndroidStudio\\ALLDATA")
        val extras = pickDirectory("ISOLATE: extras / USB recordings folder (Cancel to skip it)",
            "usbImport", File(System.getProperty("user.home"), "FFTT04M_usb_import").absolutePath)
        val folders = listOfNotNull(allData, extras)
        if (folders.isEmpty()) { showStatus("ISOLATE cancelled — no folder chosen"); return }

        val confirm = JOptionPane.showConfirmDialog(this,
            "Trim every cough WAV in:\n  ${folders.joinToString("\n  ") { it.absolutePath }}\n\n" +
            "down to the detected cough (cutting before/after), OVERWRITING each file in place and\n" +
            "deleting its .png/.jpg. Non-cough clips are skipped; this app does not keep the originals.\n\nProceed?",
            "ISOLATE COUGHS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
        if (confirm != JOptionPane.OK_OPTION) return

        isolating = true
        isolatingToken.set(false)
        val tp = TaskProgress("ISOLATE COUGHS")
        SwingUtilities.invokeLater {
            isolateButton.text = "Cancel ISOLATE"
            analysisResultsArea.append("\nISOLATE COUGHS over:\n  ${folders.joinToString("\n  ") { it.absolutePath }}\n")
            analysisResultsArea.caretPosition = analysisResultsArea.document.length
        }
        thread {
            logLine("ISOLATE COUGHS: started over ${folders.size} folder(s)")
            var nextLog = 0
            val s = CoughIsolator.run(folders, isolatingToken) { p ->
                tp.update(p.done, p.total, p.message)
                if (p.done >= nextLog || p.done == p.total) {
                    logLine("ISOLATE: ${p.message}"); nextLog = p.done + (p.total / 10).coerceAtLeast(50)
                }
            }
            val report = buildString {
                append(if (s.cancelled) "=== ISOLATE COUGHS cancelled ===\n" else "=== ISOLATE COUGHS complete ===\n")
                append(String.format(
                    "%d trimmed · %d non-cough skipped · %d no-cough-detected · %d failed · %d images deleted · %.1fs%n",
                    s.trimmed, s.skippedNonCough, s.noDetect, s.failed, s.imagesDeleted, s.elapsedS))
            }
            SwingUtilities.invokeLater {
                analysisResultsArea.append(report)
                analysisResultsArea.caretPosition = analysisResultsArea.document.length
                isolateButton.text = "ISOLATE COUGHS"
                statusLabel.text = if (s.cancelled)
                    "ISOLATE cancelled — ${s.trimmed} trimmed"
                else
                    "ISOLATE: ${s.trimmed} trimmed, ${s.noDetect} no-detect, ${s.imagesDeleted} images removed in ${"%.1f".format(s.elapsedS)}s"
            }
            tp.finish()
            isolating = false
        }
    }

    /**
     * Cloud meta-analysis: build the labeled cloud pool from ALLDATA, then measure each of the user's
     * own (extras/USB) recordings against it by k-NN — nearest sound cloud + qualifier votes — and
     * tag each with its CoughClassifier probability. Writes a report + CSV + 2D PCA map.
     */
    private fun onCloudMatch() {
        if (isAnalyzing || buildingAllData) { showStatus("Busy…"); return }
        val allData = pickDirectory("Cloud Match: ALLDATA folder (the cloud reference)",
            "allDataOut", "C:\\AndroidStudio\\ALLDATA") ?: return
        val extras = pickDirectory("Cloud Match: YOUR extras / USB recordings folder",
            "usbImport", File(System.getProperty("user.home"), "FFTT04M_usb_import").absolutePath) ?: return
        val maxPool = (JOptionPane.showInputDialog(this,
            "Cloud pool size (recordings sampled from ALLDATA; more = slower, richer):", "6000")
            ?: return).trim().toIntOrNull()?.coerceIn(200, 61184) ?: 6000
        JOptionPane.showInputDialog(this,
            "Cough detector threshold 0–1 (blank = model default ${"%.2f".format(CoughClassifier.threshold())}):", "")
            ?.takeIf { it.isNotBlank() }?.trim()?.toDoubleOrNull()?.let { CoughClassifier.thresholdOverride = it.coerceIn(0.0, 1.0) }

        isAnalyzing = true
        val token = java.util.concurrent.atomic.AtomicBoolean(false)
        val tp = TaskProgress("Cloud Match")
        SwingUtilities.invokeLater {
            analysisResultsArea.text = "Cloud meta-analysis\n ALLDATA: ${allData.absolutePath}\n extras: ${extras.absolutePath}\n\n"
        }
        thread {
            val startNs = System.nanoTime()
            val model = CloudAnalysis.buildModel(allData, maxPool, token) { p -> tp.update(p.done, p.total, p.msg) }
            val recs = DatasetLoader.loadFolder(extras, "extras")
            val results = ArrayList<Triple<String, Double, CloudAnalysis.Match>>()
            val extraVecs = ArrayList<Pair<String, DoubleArray>>()
            for ((i, r) in recs.withIndex()) {
                val pcm = AudioDecoder.decode(r.audioFile) ?: continue
                if (pcm.isEmpty()) continue
                val v = CloudAnalysis.vectorFor(pcm)
                if (!v.all { it.isFinite() }) continue
                results.add(Triple(r.id, CoughClassifier.coughProb(pcm, 44100), CloudAnalysis.match(model, v)))
                extraVecs.add(r.id to v)
                tp.update(i + 1, recs.size, "Matching extras: ${i + 1}/${recs.size}")
            }
            val thr = CoughClassifier.threshold()
            val sb = StringBuilder()
            sb.append("=== CLOUD MATCH: ${results.size} extras vs ${model.pool.size}-recording pool ===\n")
            sb.append("cough threshold ${"%.2f".format(thr)} · ${model.labelCounts.count { it.value >= 20 }} clouds\n\n")
            for ((id, cp, m) in results) {
                sb.append("• ${id.take(48)}  coughProb=${"%.2f".format(cp)} ${if (cp >= thr) "[COUGH]" else "[not-cough]"}\n")
                sb.append("    nearest sound: ${m.sounds.take(2).joinToString { it.first }}\n")
                if (m.quals.isNotEmpty()) sb.append("    qualifiers: ${m.quals.take(4).joinToString { it.first }}\n")
            }
            val csv = File(extras, "cloud_match.csv")
            runCatching {
                csv.writeText(buildString {
                    append("id,cough_prob,is_cough,nearest_sound,top_qualifiers,nearest_neighbor,nn_distance\n")
                    for ((id, cp, m) in results) append(
                        "\"$id\",${"%.3f".format(cp)},${cp >= thr},\"${m.sounds.firstOrNull()?.first ?: ""}\"," +
                        "\"${m.quals.take(4).joinToString(";") { it.first }}\",\"${m.neighbors.firstOrNull()?.first ?: ""}\"," +
                        "${"%.3f".format(m.neighbors.firstOrNull()?.second ?: 0.0)}\n")
                })
            }
            val png = File(extras, "cloud_pca.png")
            runCatching { if (extraVecs.isNotEmpty()) CloudAnalysis.pcaScatter(model, extraVecs, png) }
            val elapsed = (System.nanoTime() - startNs) / 1e9
            sb.append("\nCSV: ${csv.path}\n2D map: ${png.path}\n${"%.1f".format(elapsed)}s\n")
            val report = sb.toString()
            SwingUtilities.invokeLater {
                analysisResultsArea.append(report); analysisResultsArea.caretPosition = analysisResultsArea.document.length
                statusLabel.text = "Cloud Match: ${results.size} extras vs ${model.pool.size} pool in ${"%.1f".format(elapsed)}s"
            }
            tp.finish(); isAnalyzing = false
        }
    }

    /** Max rows shown in the list; the full [recordings] set is still analyzed (ALLDATA is ~61k). */
    private val MAX_LIST_DISPLAY = 2000

    private fun updateRecordingsList() {
        SwingUtilities.invokeLater {
            val model = recordingsList.model as DefaultListModel<String>
            model.clear()
            val shown = recordings.take(MAX_LIST_DISPLAY)
            shown.forEach { rec -> model.addElement("${rec.id}: ${rec.label() ?: "unknown"}") }
            if (recordings.size > shown.size)
                model.addElement("… and ${recordings.size - shown.size} more (all will be analyzed)")
        }
    }

    /**
     * Analyze a chosen source: the currently loaded list, the **ALLDATA** output folder, the
     * **USB import** folder, or both folders combined. Folder choices are loaded (recursively) into
     * [recordings] before the Tier-1 engine fans out across cores.
     */
    private fun analyzeAll() {
        if (isAnalyzing) { showStatus("Busy analyzing…"); return }
        if (buildingAllData) { showStatus("Busy…"); return }

        val options = buildList {
            if (recordings.isNotEmpty()) add("Loaded recordings (${recordings.size})")
            add("ALLDATA folder")
            add("USB import folder")
            add("ALLDATA + USB import (combined)")
        }
        val choice = JOptionPane.showInputDialog(this, "Analyze which source?", "Analyze All",
            JOptionPane.QUESTION_MESSAGE, null, options.toTypedArray(), options.first()) as String?
            ?: return

        // Resolve folder pickers here on the EDT; remember choices via the same prefs the other
        // features use (allDataOut / usbImport) so they default to where you built / imported.
        val wantAllData = choice.startsWith("ALLDATA")
        val wantUsb = choice.contains("USB")
        val allDataDir = if (wantAllData) pickDirectory("Select the ALLDATA folder to analyze",
            "allDataOut", "C:\\AndroidStudio\\ALLDATA") ?: return else null
        val usbDir = if (wantUsb) pickDirectory("Select the USB import folder to analyze",
            "usbImport", File(System.getProperty("user.home"), "FFTT04M_usb_import").absolutePath)
            ?: return else null

        isAnalyzing = true
        thread {
            if (wantAllData || wantUsb) {
                showStatus("Scanning folder(s) for WAVs…")
                val loaded = mutableListOf<AudioRecording>()
                allDataDir?.let { loaded.addAll(DatasetLoader.loadFolder(it, "ALLDATA")) }
                usbDir?.let { loaded.addAll(DatasetLoader.loadFolder(it, "USB")) }
                recordings.clear()
                recordings.addAll(loaded)
                selectedDataset = null
                updateRecordingsList()
            }
            if (recordings.isEmpty()) {
                showStatus("No recordings found to analyze")
                isAnalyzing = false
                return@thread
            }
            runAnalysis()
            isAnalyzing = false
        }
    }

    /** Run the Tier-1 engine over the current [recordings] (assumes a populated list). */
    private fun runAnalysis() {
        run {
            SwingUtilities.invokeLater { analysisResultsArea.text = "" }
            val tp = TaskProgress("Analyze")
            val n = recordings.size
            val startNs = System.nanoTime()
            showStatus("Analyzing $n recordings on ${engine.workers} cores (full Tier-1 DSP)...")

            // Fan the full Tier-1 cough engine across every CPU core.
            val results = engine.analyzeAll(recordings) { doneCount, total ->
                tp.update(doneCount, total, "Analyzed $doneCount/$total")
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
            tp.finish()
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
    private val dirPrefs = java.util.prefs.Preferences.userRoot().node("FFTT04D/dirs")

    /**
     * Directory chooser that remembers the last choice per [prefKey] across runs.
     * Starts at the remembered dir, else [fallback] (the old fixed path) if it exists, else home.
     * A typed-in directory that doesn't exist yet is created. Returns null on cancel.
     */
    private fun pickDirectory(title: String, prefKey: String, fallback: String? = null): File? {
        val start = dirPrefs.get(prefKey, null)?.let { File(it) }?.takeIf { it.isDirectory }
            ?: fallback?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))
        val chooser = JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            selectedFile = start   // OK without browsing reuses the remembered/default dir
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null
        val dir = chooser.selectedFile ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) { showStatus("Cannot use folder: $dir"); return null }
        dirPrefs.put(prefKey, dir.absolutePath)
        return dir
    }

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

    /** Build-progress phases worth logging verbatim to the results log (vs. the high-frequency
     *  per-clip conversion ticks, which only update the progress bar). */
    private val NOTABLE_PHASES = setOf("scan", "found", "warn", "done", "error", "csv")

    /** Append a timestamped line to the analysis-results log (autoscroll, size-capped). */
    private fun logLine(msg: String) = SwingUtilities.invokeLater {
        analysisResultsArea.append("[${java.time.LocalTime.now().withNano(0)}] $msg\n")
        val len = analysisResultsArea.document.length
        if (len > 400_000) analysisResultsArea.replaceRange("", 0, len - 300_000)
        analysisResultsArea.caretPosition = analysisResultsArea.document.length
    }

    /**
     * One stacked progress row (label + bar) for a single running task. Multiple can coexist, so
     * concurrent passes (e.g. CPU + GPU CWT) each show their own bar instead of overwriting one.
     * Removes itself from the stack when the task finishes.
     */
    inner class TaskProgress(title: String) {
        private val bar = JProgressBar(0, 100).apply { isStringPainted = true; string = title }
        private val row = JPanel(BorderLayout(8, 0)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            add(JLabel(title).apply { preferredSize = Dimension(150, 0) }, BorderLayout.WEST)
            add(bar, BorderLayout.CENTER)
        }
        init { SwingUtilities.invokeLater { progressStack.add(row); progressStack.revalidate(); progressStack.repaint() } }

        fun update(done: Int, total: Int, msg: String?) = SwingUtilities.invokeLater {
            if (total > 0) { bar.isIndeterminate = false; bar.value = (done * 100 / total).coerceIn(0, 100) }
            else bar.isIndeterminate = true
            if (msg != null) bar.string = msg
        }

        fun finish() = SwingUtilities.invokeLater {
            progressStack.remove(row); progressStack.revalidate(); progressStack.repaint()
        }
    }

    private fun createButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            addActionListener { action() }
        }
    }
}

enum class Dataset(val displayName: String, val structureHint: String) {
    COUGH_DATASET_1("Cough Dataset 1", "public_dataset\\*.webm|ogg|wav with <uuid>.json sidecars"),
    ESC_50("ESC-50", "audio\\*.wav plus meta\\esc50.csv"),
    COSWARA("Coswara", "YYYYMMDD date folders with <date>.csv and tar.gz parts"),
}
