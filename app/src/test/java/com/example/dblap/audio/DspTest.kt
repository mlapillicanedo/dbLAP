package com.example.dblap.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    private val sampleRate = 44_100

    @Test
    fun fftFindsSinePeak() {
        val size = 4096
        val frequency = 1_000f
        val samples = FloatArray(size) { i ->
            val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (size - 1))
            (sin(2.0 * PI * frequency * i / sampleRate) * window).toFloat()
        }
        val real = samples.copyOf()
        val imaginary = FloatArray(size)

        Fft(size).transform(real, imaginary)

        var peakBin = 0
        var peakPower = 0f
        for (bin in 1 until size / 2) {
            val power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            if (power > peakPower) {
                peakPower = power
                peakBin = bin
            }
        }

        val expectedBin = Math.round(frequency / (sampleRate.toFloat() / size)).toInt()
        assertTrue("peakBin=$peakBin expected=$expectedBin", abs(expectedBin - peakBin) <= 1)

        val normalizedMagnitude = sqrt(peakPower) * 4f / size
        assertEquals(1f, normalizedMagnitude, 0.03f)
    }

    @Test
    fun pitchDetectorTracksPureTones() {
        for (frequency in listOf(82.41f, 196f, 220f, 440f, 880f, 1_500f)) {
            val samples = FloatArray(4096) { i ->
                (0.5 * sin(2.0 * PI * frequency * i / sampleRate)).toFloat()
            }
            val result = PitchDetector(sampleRate).detect(samples, samples.size)
            assertTrue(
                "f=$frequency -> ${result.frequencyHz}",
                abs(result.frequencyHz - frequency) < frequency * 0.01f,
            )
            assertTrue("confianza=${result.confidence}", result.confidence > 0.9f)
        }
    }

    @Test
    fun pitchDetectorAvoidsOctaveErrorsWithHarmonics() {
        val fundamental = 196f
        val samples = FloatArray(4096) { i ->
            val t = 2.0 * PI * fundamental * i / sampleRate
            (0.5 * sin(t) + 0.25 * sin(2 * t) + 0.12 * sin(3 * t)).toFloat()
        }
        val result = PitchDetector(sampleRate).detect(samples, samples.size)
        assertEquals(fundamental, result.frequencyHz, 2f)
    }

    @Test
    fun pitchDetectorIgnoresSilence() {
        val result = PitchDetector(sampleRate).detect(FloatArray(4096), 4096)
        assertEquals(0f, result.frequencyHz, 0f)
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun ringBufferReturnsLatestSamples() {
        val ring = RingBuffer(8)
        ring.write(floatArrayOf(1f, 2f, 3f, 4f, 5f), 5)

        val first = FloatArray(3)
        assertTrue(ring.readLatest(first))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), first, 0f)

        ring.write(floatArrayOf(6f, 7f, 8f, 9f, 10f), 5)

        val second = FloatArray(8)
        assertTrue(ring.readLatest(second))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), second, 0f)
    }
}
