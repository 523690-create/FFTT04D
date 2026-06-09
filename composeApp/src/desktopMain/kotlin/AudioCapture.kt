package com.example.FFTT04M

actual fun createAudioCapture(
    sampleRate: Int, 
    bufferSize: Int, 
    onBufferReady: (FloatArray) -> Unit
): AudioCaptureInterface = DesktopAudioCapture(sampleRate.toFloat(), bufferSize, onBufferReady)
