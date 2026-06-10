package com.example.FFTT04M.desktop

import javax.swing.*
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
        val titleLabel = JLabel("Cough Analysis Desktop")
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
        val exportButton = createButton("Export Results") {
            showStatus("Export functionality coming soon")
        }
        analysisPanel.add(startButton)
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
            analysisResultsArea.text = ""
            progressBar.value = 0
            showStatus("Analyzing ${recordings.size} recordings...")

            val results = StringBuilder()
            results.append("=== ANALYSIS RESULTS ===\n\n")
            var analyzed = 0
            var skipped = 0

            recordings.forEachIndexed { idx, rec ->
                val percent = ((idx + 1) * 100) / recordings.size
                progressBar.value = percent

                // Skip unsupported formats (need ffmpeg for WebM/OGG)
                val ext = rec.audioFile.extension.lowercase()
                if (ext !in listOf("wav")) {
                    skipped++
                    showStatus("Skipping ${idx + 1}/${recordings.size} (${ext.uppercase()} not supported)...")
                    return@forEachIndexed
                }

                showStatus("Analyzing ${idx + 1}/${recordings.size}...")

                // Try to decode and analyze
                val pcm = AudioDecoder.decode(rec.audioFile)
                if (pcm != null) {
                    analyzed++
                    val duration = pcm.size.toDouble() / 44100
                    results.append("${rec.id}:\n")
                    results.append("  Duration: ${String.format("%.2f", duration)}s\n")
                    results.append("  RMS Level: ${String.format("%.4f", calculateRMS(pcm))}\n")
                    results.append("  Peak: ${String.format("%.4f", pcm.maxOrNull() ?: 0f)}\n")
                    rec.label()?.let { results.append("  Label: $it\n") }
                    results.append("\n")
                }
                SwingUtilities.invokeLater {
                    analysisResultsArea.text = results.toString()
                }
            }

            results.insert(0, "Supported formats: WAV only (WebM/OGG require ffmpeg)\n\n")
            results.append("\n=== SUMMARY ===\n")
            results.append("Analyzed: $analyzed | Skipped: $skipped\n")
            SwingUtilities.invokeLater {
                analysisResultsArea.text = results.toString()
            }

            showStatus("Analysis complete: $analyzed analyzed, $skipped skipped (unsupported format)")
            isAnalyzing = false
            progressBar.value = 100
        }
    }

    private fun calculateRMS(pcm: FloatArray): Float {
        var sum = 0.0
        for (sample in pcm) sum += sample * sample
        return kotlin.math.sqrt(sum / pcm.size).toFloat()
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
