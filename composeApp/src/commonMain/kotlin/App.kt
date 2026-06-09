package com.example.FFTT04M

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
fun App() {
    var isRecording by remember { mutableStateOf(false) }
    var colorScheme by remember { mutableStateOf(0) }
    var canvasWidth by remember { mutableStateOf(0) }
    var canvasHeight by remember { mutableStateOf(0) }

    val fftSize = 2048
    val stepSize = 1024
    val sampleRate = 44100f

    var renderer by remember { mutableStateOf<SpectrogramRenderer?>(null) }
    var latestPixels by remember { mutableStateOf<IntArray?>(null) }
    var writeOffset by remember { mutableStateOf(0) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val hannWindow = remember { 
        FloatArray(fftSize) { i ->
            (0.5f * (1 - cos(2 * PI * i.toDouble() / (fftSize - 1)))).toFloat()
        }
    }

    val audioCapture = remember {
        createAudioCapture(sampleRate.toInt(), stepSize) { audioData ->
            val curRenderer = renderer ?: return@createAudioCapture
            
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            for (i in 0 until fftSize) {
                if (i < audioData.size) real[i] = audioData[i] * hannWindow[i]
            }

            FFTUtils.compute(real, imag)
            
            val magnitudes = FloatArray(fftSize / 2)
            for (i in 0 until fftSize / 2) {
                val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
                magnitudes[i] = ((20 * log10(mag + 1e-9f) + 80) / 80f).coerceIn(0f, 1f)
            }

            latestPixels = curRenderer.addColumn(magnitudes, ColorMaps.lut(colorScheme))
            writeOffset = curRenderer.getWriteOffset()
            refreshTrigger++
        }
    }

    LaunchedEffect(canvasWidth, canvasHeight) {
        if (canvasWidth > 0 && canvasHeight > 0) {
            renderer = SpectrogramRenderer(canvasWidth, canvasHeight, fftSize, sampleRate)
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FFTT04M - Desktop") },
                    actions = {
                        Button(onClick = { 
                            isRecording = !isRecording 
                            if (isRecording) audioCapture.start() else audioCapture.stop()
                        }) {
                            Text(if (isRecording) "STOP" else "START")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { 
                            canvasWidth = it.width
                            canvasHeight = it.height
                        }
                ) {
                    key(refreshTrigger) {
                        SpectrogramCanvas(latestPixels, writeOffset, canvasWidth, canvasHeight)
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ColorMaps.names.forEachIndexed { index, name ->
                        TextButton(onClick = { colorScheme = index }) {
                            Text(name, color = if (colorScheme == index) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
                        }
                    }
                }
            }
        }
    }
}

expect fun createAudioCapture(
    sampleRate: Int, 
    bufferSize: Int, 
    onBufferReady: (FloatArray) -> Unit
): AudioCaptureInterface

interface AudioCaptureInterface {
    fun start()
    fun stop()
}
