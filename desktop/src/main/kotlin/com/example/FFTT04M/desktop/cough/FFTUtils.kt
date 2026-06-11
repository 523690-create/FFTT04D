package com.example.FFTT04M.desktop.cough

import kotlin.math.*

object FFTUtils {
    fun compute(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n == 0) return
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) { j -= m; m = m shr 1 }
            j += m
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            for (i in 0 until n step length) {
                var wR = 1f; var wI = 0f
                for (k in 0 until length / 2) {
                    val idx1 = i + k
                    val idx2 = i + k + length / 2
                    val vR = real[idx2] * wR - imag[idx2] * wI
                    val vI = real[idx2] * wI + imag[idx2] * wR
                    real[idx2] = real[idx1] - vR
                    imag[idx2] = imag[idx1] - vI
                    real[idx1] += vR
                    imag[idx1] += vI
                    val nwr = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nwr
                }
            }
            length *= 2
        }
    }

    fun inverse(real: FloatArray, imag: FloatArray) {
        val n = real.size
        if (n == 0) return
        for (i in 0 until n) imag[i] = -imag[i]
        compute(real, imag)
        for (i in 0 until n) {
            real[i] /= n.toFloat()
            imag[i] /= -n.toFloat()
        }
    }

    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
