package com.example.FFTT04M.desktop

import java.io.File
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Decodes audio files (WAV, OGG, WebM) to normalized PCM float arrays at 44.1 kHz.
 */
object AudioDecoder {

    /**
     * Decode an audio file to a mono float array at 44.1 kHz.
     * Returns null if decoding fails.
     */
    fun decode(file: File): FloatArray? {
        return when (file.extension.lowercase()) {
            "wav" -> decodeWav(file)
            "ogg" -> decodeOgg(file)
            "webm" -> decodeWebm(file)
            else -> null
        }
    }

    /**
     * Decode WAV file using javax.sound.sampled (built-in).
     */
    private fun decodeWav(file: File): FloatArray? {
        return try {
            val audioInputStream = AudioSystem.getAudioInputStream(file)
            val format = audioInputStream.format
            val targetSampleRate = 44100

            // Read all frames
            val frameLength = audioInputStream.frameLength
            val frameSize = format.frameSize
            val buffer = ByteArray((frameLength * frameSize).toInt())
            audioInputStream.read(buffer)
            audioInputStream.close()

            // Convert bytes to float array (assuming 16-bit PCM)
            val floatArray = FloatArray(frameLength.toInt())
            val bytesPerSample = format.sampleSizeInBits / 8
            val isBigEndian = format.isBigEndian

            for (i in 0 until frameLength.toInt()) {
                val sampleBytes = buffer.sliceArray((i * frameSize) until ((i + 1) * frameSize))
                val sample = when (bytesPerSample) {
                    2 -> bytesToShort(sampleBytes, isBigEndian).toFloat() / 32768f
                    1 -> sampleBytes[0].toFloat() / 128f
                    else -> 0f
                }
                floatArray[i] = sample
            }

            // Resample if necessary
            if (format.sampleRate != 44100f) {
                resample(floatArray, format.sampleRate, 44100f)
            } else {
                floatArray
            }
        } catch (e: Exception) {
            System.err.println("Error decoding WAV: ${e.message}")
            null
        }
    }

    /**
     * Decode OGG/Vorbis file (placeholder — requires jorbis integration).
     */
    private fun decodeOgg(file: File): FloatArray? {
        return try {
            // TODO: Integrate jorbis library for OGG decoding
            // For now, return null as a placeholder
            System.err.println("OGG decoding not yet implemented")
            null
        } catch (e: Exception) {
            System.err.println("Error decoding OGG: ${e.message}")
            null
        }
    }

    /**
     * Decode WebM file (placeholder — requires external tool or library).
     */
    private fun decodeWebm(file: File): FloatArray? {
        return try {
            // WebM decoding requires ffmpeg or a library like webm-jnicodecs
            // For now, try to invoke ffmpeg if available
            val processBuilder = ProcessBuilder(
                "ffmpeg",
                "-i", file.absolutePath,
                "-f", "s16le",      // signed 16-bit PCM
                "-acodec", "pcm_s16le",
                "-ar", "44100",      // 44.1 kHz
                "-ac", "1",          // mono
                "-"                  // stdout
            )
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD)

            val process = processBuilder.start()
            val output = process.inputStream.readBytes()
            process.waitFor()

            if (process.exitValue() == 0) {
                // Convert bytes to float
                val samples = output.size / 2
                val floatArray = FloatArray(samples)
                for (i in 0 until samples) {
                    val sample = bytesToShort(
                        output.sliceArray(i * 2 until i * 2 + 2),
                        false // little-endian
                    ).toFloat() / 32768f
                    floatArray[i] = sample
                }
                floatArray
            } else {
                null
            }
        } catch (e: Exception) {
            System.err.println("Error decoding WebM (ffmpeg not available?): ${e.message}")
            null
        }
    }

    /**
     * Convert two bytes to a signed short (16-bit PCM sample).
     */
    private fun bytesToShort(bytes: ByteArray, isBigEndian: Boolean): Short {
        return if (isBigEndian) {
            ((bytes[0].toInt() and 0xFF) shl 8 or (bytes[1].toInt() and 0xFF)).toShort()
        } else {
            ((bytes[1].toInt() and 0xFF) shl 8 or (bytes[0].toInt() and 0xFF)).toShort()
        }
    }

    /**
     * Resample audio from sourceRate to targetRate using linear interpolation.
     */
    private fun resample(samples: FloatArray, sourceRate: Float, targetRate: Float): FloatArray {
        if (sourceRate == targetRate) return samples

        val ratio = sourceRate / targetRate
        val newLength = (samples.size / ratio).toInt()
        val resampled = FloatArray(newLength)

        for (i in 0 until newLength) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = pos - idx

            resampled[i] = when {
                idx >= samples.size - 1 -> samples[samples.size - 1]
                idx < 0 -> samples[0]
                else -> {
                    val s1 = samples[idx]
                    val s2 = samples[idx + 1]
                    s1 * (1 - frac) + s2 * frac
                }
            }
        }

        return resampled
    }
}
