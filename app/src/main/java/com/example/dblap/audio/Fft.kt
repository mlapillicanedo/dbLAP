package com.example.dblap.audio

/**
 * Transformada rapida de Fourier (radix-2, Cooley-Tukey) in-place.
 *
 * Trabaja sobre dos arreglos de [size] muestras (parte real e imaginaria).
 * Las tablas de twiddles y de bit-reversal se calculan una sola vez, por lo
 * que la instancia se puede reutilizar en cada frame sin generar basura.
 */
class Fft(val size: Int) {

    init {
        require(size > 1 && (size and (size - 1)) == 0) {
            "El tamano de la FFT debe ser una potencia de 2 (recibido: $size)"
        }
    }

    private val cosTable = FloatArray(size / 2) { i ->
        kotlin.math.cos(2.0 * Math.PI * i / size).toFloat()
    }
    private val sinTable = FloatArray(size / 2) { i ->
        kotlin.math.sin(2.0 * Math.PI * i / size).toFloat()
    }
    private val bitReversed = IntArray(size) { i ->
        val bits = Integer.numberOfTrailingZeros(size)
        Integer.reverse(i) ushr (32 - bits)
    }

    /**
     * Calcula la FFT de la senal contenida en [real]/[imag].
     * Al terminar, [real] y [imag] contienen el espectro complejo.
     */
    fun transform(real: FloatArray, imag: FloatArray) {
        require(real.size >= size && imag.size >= size) {
            "Los arreglos deben tener al menos $size elementos"
        }

        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var length = 2
        while (length <= size) {
            val half = length / 2
            val tableStep = size / length
            var start = 0
            while (start < size) {
                var k = 0
                while (k < half) {
                    val twiddle = k * tableStep
                    val wr = cosTable[twiddle]
                    val wi = -sinTable[twiddle]
                    val even = start + k
                    val odd = even + half
                    val oddRe = real[odd] * wr - imag[odd] * wi
                    val oddIm = real[odd] * wi + imag[odd] * wr
                    real[odd] = real[even] - oddRe
                    imag[odd] = imag[even] - oddIm
                    real[even] += oddRe
                    imag[even] += oddIm
                    k++
                }
                start += length
            }
            length = length shl 1
        }
    }
}
