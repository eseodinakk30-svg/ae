package com.autoedit.core

/**
 * Минимальный radix-2 FFT (in-place, Кули-Тьюки).
 * Используется для спектрального потока при поиске онсетов.
 */
class Fft(private val n: Int) {

    private val cos = FloatArray(n / 2)
    private val sin = FloatArray(n / 2)
    private val reverse = IntArray(n)

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
        for (i in 0 until n / 2) {
            val angle = -2.0 * Math.PI * i / n
            cos[i] = Math.cos(angle).toFloat()
            sin[i] = Math.sin(angle).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) {
            reverse[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /** re/im длиной n; результат — на месте. */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val wr = cos[k]
                    val wi = sin[k]
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
