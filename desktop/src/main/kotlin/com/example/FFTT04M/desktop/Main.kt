package com.example.FFTT04M.desktop

import javax.swing.*
import java.awt.*
import java.io.File

fun main() {
    SwingUtilities.invokeLater {
        AnalyzerWindow().isVisible = true
    }
}

class AnalyzerWindow : JFrame("Cough Analysis Desktop") {
    private var selectedDataset: Dataset? = null
    private var datasetPath = ""
    private var isAnalyzing = false

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        size = Dimension(800, 600)
        setLocationRelativeTo(null)

        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Title
        val titleLabel = JLabel("Cough Analysis Desktop")
        titleLabel.font = Font("Dialog", Font.BOLD, 24)
        panel.add(titleLabel, BorderLayout.NORTH)

        // Central panel
        val centerPanel = JPanel(BoxLayout(JPanelBoxLayout(BoxLayout.Y_AXIS), BoxLayout.Y_AXIS))
        centerPanel.border = BorderFactory.createEmptyBorder(10, 0, 10, 0)

        // Dataset selection buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(createButton("Load Cough Dataset 1") {
            datasetPath = "H:\\cough dataset 1"
            selectedDataset = Dataset.COUGH_DATASET_1
            updateUI()
        })
        buttonPanel.add(createButton("Load ESC-50") {
            datasetPath = "H:\\ESC-50-master"
            selectedDataset = Dataset.ESC_50
            updateUI()
        })
        buttonPanel.add(createButton("Load Coswara") {
            datasetPath = "H:\\Coswara-Data-master"
            selectedDataset = Dataset.COSWARA
            updateUI()
        })
        centerPanel.add(buttonPanel)

        // Status/details panel
        val detailsPanel = JPanel()
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        centerPanel.add(detailsPanel)

        // Analysis controls
        val analysisPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val startButton = createButton("Start Analysis") {
            isAnalyzing = true
            // TODO: Start analysis in background thread
        }
        val cancelButton = createButton("Cancel") {
            isAnalyzing = false
        }
        analysisPanel.add(startButton)
        analysisPanel.add(cancelButton)
        centerPanel.add(analysisPanel)

        // Progress bar
        val progressBar = JProgressBar(0, 100)
        progressBar.isStringPainted = true
        centerPanel.add(progressBar)

        panel.add(centerPanel, BorderLayout.CENTER)

        // Status bar
        val statusBar = JLabel("Ready")
        statusBar.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        panel.add(statusBar, BorderLayout.SOUTH)

        contentPane = panel
    }

    private fun createButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            addActionListener { action() }
        }
    }

    private fun updateUI() {
        // TODO: Update UI based on selected dataset
    }
}

class JPanelBoxLayout(axis: Int) : JPanel() {
    init {
        layout = BoxLayout(this, axis)
    }
}

enum class Dataset(val displayName: String) {
    COUGH_DATASET_1("Cough Dataset 1"),
    ESC_50("ESC-50"),
    COSWARA("Coswara"),
}
