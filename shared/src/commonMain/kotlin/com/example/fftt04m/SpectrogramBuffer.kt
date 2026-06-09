package com.example.FFTT04M

/**
 * Manages the history of FFT magnitudes for a spectrogram.
 * Uses a circular buffer to store columns.
 */
class SpectrogramBuffer(
    private var maxHistory: Int,
    private val bins: Int
) {
    private var history = Array(maxHistory) { FloatArray(bins) }
    private var writeIndex = 0
    private var totalAdded = 0

    fun addColumn(magnitudes: FloatArray) {
        val target = history[writeIndex]
        magnitudes.copyInto(target)
        writeIndex = (writeIndex + 1) % maxHistory
        totalAdded++
    }

    fun getColumn(index: Int): FloatArray {
        val safeIndex = index.coerceIn(0, maxHistory - 1)
        val circularIndex = (writeIndex - 1 - safeIndex + maxHistory * 10) % maxHistory
        return history[circularIndex]
    }

    fun clear() {
        writeIndex = 0
        totalAdded = 0
        for (col in history) col.fill(0f)
    }

    fun resize(newMaxHistory: Int) {
        if (newMaxHistory == maxHistory) return
        val newHistory = Array(newMaxHistory) { FloatArray(bins) }
        val toCopy = minOf(maxHistory, newMaxHistory)
        for (i in 0 until toCopy) {
            val oldIdx = (writeIndex - 1 - i + maxHistory * 10) % maxHistory
            val newIdx = (toCopy - 1 - i)
            history[oldIdx].copyInto(newHistory[newIdx])
        }
        history = newHistory
        maxHistory = newMaxHistory
        writeIndex = toCopy % maxHistory
        totalAdded = toCopy
    }

    fun currentHistorySize(): Int = minOf(totalAdded, maxHistory)
}
