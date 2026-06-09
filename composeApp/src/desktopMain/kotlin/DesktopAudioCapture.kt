package com.example.FFTT04M

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

class DesktopAudioCapture(
    private val sampleRate: Float = 44100f,
    private val bufferSizeSamples: Int = 2048,
    private val onBufferReady: (FloatArray) -> Unit
) : AudioCaptureInterface {
    private val recording = AtomicBoolean(false)
    private var thread: Thread? = null

    override fun start() {
        if (recording.get()) return
        recording.set(true)

        thread = Thread {
            val format = AudioFormat(sampleRate, 16, 1, true, false)
            val line = try {
                val info = DataLine.Info(TargetDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) {
                    val mixers = AudioSystem.getMixerInfo()
                    var foundLine: TargetDataLine? = null
                    for (mixerInfo in mixers) {
                        val mixer = AudioSystem.getMixer(mixerInfo)
                        val targetLines = mixer.targetLineInfo
                        for (lineInfo in targetLines) {
                            if (lineInfo is DataLine.Info && TargetDataLine::class.java.isAssignableFrom(lineInfo.lineClass)) {
                                try {
                                    val l = mixer.getLine(lineInfo) as TargetDataLine
                                    l.open()
                                    foundLine = l
                                    break
                                } catch (_: Exception) {}
                            }
                        }
                        if (foundLine != null) break
                    }
                    foundLine ?: throw Exception("No supported audio lines found")
                } else {
                    val l = AudioSystem.getLine(info) as TargetDataLine
                    l.open(format)
                    l
                }
            } catch (e: Exception) {
                println("DesktopAudioCapture Error: ${e.message}")
                recording.set(false)
                return@Thread
            }

            line.start()

            val byteBuffer = ByteArray(bufferSizeSamples * 2)
            val floatBuffer = FloatArray(bufferSizeSamples)

            try {
                while (recording.get()) {
                    val read = line.read(byteBuffer, 0, byteBuffer.size)
                    if (read > 0) {
                        val bb = ByteBuffer.wrap(byteBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                        val samplesRead = read / 2
                        for (i in 0 until samplesRead) {
                            floatBuffer[i] = bb.short / 32768f
                        }
                        onBufferReady(floatBuffer.copyOf(samplesRead))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                line.stop()
                line.close()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        recording.set(false)
        thread?.join(500)
        thread = null
    }
}
