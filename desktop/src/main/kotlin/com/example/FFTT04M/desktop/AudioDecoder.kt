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

    /** OGG/Vorbis via ffmpeg. */
    private fun decodeOgg(file: File): FloatArray? = decodeViaFfmpeg(file)

    /** WebM/Opus via ffmpeg. */
    private fun decodeWebm(file: File): FloatArray? = decodeViaFfmpeg(file)

    /** True if ffmpeg is available (resolved on PATH or in the winget install dir). */
    fun ffmpegAvailable(): Boolean = ffmpegExe() != null

    // Resolved lazily and cached. null => not found.
    @Volatile private var ffmpegResolved = false
    @Volatile private var ffmpegCached: String? = null

    /** Locate ffmpeg: PATH first, then the winget (Gyan.FFmpeg) package dir. Cached after first call. */
    private fun ffmpegExe(): String? {
        if (ffmpegResolved) return ffmpegCached
        synchronized(this) {
            if (ffmpegResolved) return ffmpegCached
            ffmpegCached = resolveFfmpeg()
            ffmpegResolved = true
            return ffmpegCached
        }
    }

    private fun resolveFfmpeg(): String? {
        // 1) On PATH (works after a shell restart picks up winget's PATH edit).
        if (canRun("ffmpeg")) return "ffmpeg"
        // 2) winget shim.
        val local = System.getenv("LOCALAPPDATA")
        if (local != null) {
            val shim = File(local, "Microsoft\\WinGet\\Links\\ffmpeg.exe")
            if (shim.isFile && canRun(shim.absolutePath)) return shim.absolutePath
            // 3) winget package dir: ...\Packages\Gyan.FFmpeg*\**\bin\ffmpeg.exe
            val pkgs = File(local, "Microsoft\\WinGet\\Packages")
            pkgs.listFiles { f -> f.isDirectory && f.name.startsWith("Gyan.FFmpeg", true) }?.forEach { dir ->
                dir.walkTopDown().firstOrNull { it.isFile && it.name.equals("ffmpeg.exe", true) }
                    ?.let { return it.absolutePath }
            }
        }
        return null
    }

    private fun canRun(exe: String): Boolean = try {
        val p = ProcessBuilder(exe, "-version").redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor(); p.exitValue() == 0
    } catch (e: Exception) { false }

    /** Decode any ffmpeg-supported file to mono 44.1 kHz float PCM. */
    private fun decodeViaFfmpeg(file: File): FloatArray? {
        val ff = ffmpegExe() ?: run {
            System.err.println("ffmpeg not found — cannot decode ${file.name}")
            return null
        }
        return try {
            val process = ProcessBuilder(
                ff, "-i", file.absolutePath,
                "-f", "s16le", "-acodec", "pcm_s16le",
                "-ar", "44100", "-ac", "1", "-"
            ).start()
            // Drain stderr on a thread so a full pipe can't deadlock the decode.
            val errDrain = Thread { try { process.errorStream.readBytes() } catch (_: Exception) {} }
            errDrain.isDaemon = true; errDrain.start()
            val output = process.inputStream.readBytes()
            process.waitFor()
            if (process.exitValue() != 0) return null
            val samples = output.size / 2
            FloatArray(samples) { i ->
                bytesToShort(output.sliceArray(i * 2 until i * 2 + 2), false).toFloat() / 32768f
            }
        } catch (e: Exception) {
            System.err.println("Error decoding ${file.name} via ffmpeg: ${e.message}")
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
