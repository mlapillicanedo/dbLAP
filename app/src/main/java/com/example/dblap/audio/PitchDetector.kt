package com.example.dblap.audio

import kotlin.math.abs

data class PitchResult(
    val frequencyHz: Float,
    val confidence: Float,
)

/**
 * Detector de frecuencia fundamental basado en YIN (de Cheveigne y Kawahara).
 *
 * Es mas robusto que tomar simplemente el pico de la FFT: evita errores de
 * octava cuando la senal tiene armonicos fuertes y la interpolacion parabolica
 * del periodo da una resolucion mucho mejor que el ancho de bin.
 *
 * Los arreglos de trabajo se reservan una sola vez en el constructor.
 */
class PitchDetector(
    private val sampleRate: Int,
    private val minFrequencyHz: Float = 50f,
    private val maxFrequencyHz: Float = 3_000f,
    private val threshold: Float = 0.15f,
) {

    private val tauMin = (sampleRate / maxFrequencyHz).toInt().coerceAtLeast(2)
    private val tauMax = (sampleRate / minFrequencyHz).toInt()
    private val difference = FloatArray(tauMax + 2)
    private val normalized = FloatArray(tauMax + 2)

    /**
     * Analiza las primeras [count] muestras de [samples] (mono, rango -1..1).
     * Devuelve frecuencia 0 si no se detecta un tono con suficiente confianza.
     */
    fun detect(samples: FloatArray, count: Int): PitchResult {
        if (count < 2 * tauMax) return PitchResult(0f, 0f)

        val window = count - tauMax
        for (tau in 1..tauMax) {
            var sum = 0f
            var i = 0
            while (i < window) {
                val delta = samples[i] - samples[i + tau]
                sum += delta * delta
                i++
            }
            difference[tau] = sum
        }

        // Diferencia normalizada por la media acumulada (YIN).
        normalized[0] = 1f
        var cumulative = 0f
        for (tau in 1..tauMax) {
            cumulative += difference[tau]
            normalized[tau] = if (cumulative <= 0f) 1f else difference[tau] * tau / cumulative
        }

        // Primer minimo local que cae por debajo del umbral.
        var bestTau = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (normalized[tau] < threshold) {
                while (tau + 1 <= tauMax && normalized[tau + 1] < normalized[tau]) {
                    tau++
                }
                bestTau = tau
                break
            }
            tau++
        }

        if (bestTau < 0) {
            var minimum = Float.MAX_VALUE
            for (candidate in tauMin..tauMax) {
                if (normalized[candidate] < minimum) {
                    minimum = normalized[candidate]
                    bestTau = candidate
                }
            }
            if (minimum > 0.6f) return PitchResult(0f, 0f)
        }

        val confidence = (1f - normalized[bestTau]).coerceIn(0f, 1f)

        // Interpolacion parabolica alrededor del minimo: da fracciones de muestra.
        var refinedLag = bestTau.toFloat()
        if (bestTau > 1 && bestTau < tauMax) {
            val left = normalized[bestTau - 1]
            val center = normalized[bestTau]
            val right = normalized[bestTau + 1]
            val denominator = 2f * (2f * center - left - right)
            if (abs(denominator) > 1e-6f) {
                val shift = (right - left) / denominator
                if (abs(shift) <= 1f) refinedLag += shift
            }
        }

        val frequency = sampleRate / refinedLag
        if (frequency < minFrequencyHz * 0.9f || frequency > maxFrequencyHz * 1.1f) {
            return PitchResult(0f, 0f)
        }
        return PitchResult(frequency, confidence)
    }
}
