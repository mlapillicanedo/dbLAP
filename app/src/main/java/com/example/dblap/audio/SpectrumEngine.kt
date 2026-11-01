package com.example.dblap.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val DEFAULT_SAMPLE_RATE = 44_100
const val MIN_SPECTRUM_DB = -100f
const val MAX_SPECTRUM_DB = 0f

/** Un frame de analisis listo para dibujar. */
data class SpectrumFrame(
    /** Magnitud en dBFS de cada bin, de 0 a Nyquist (el bin 0 queda vacio). */
    val magnitudesDb: FloatArray = FloatArray(0),
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val fftSize: Int = 0,
    val fundamentalHz: Float = 0f,
    val confidence: Float = 0f,
    val rmsDb: Float = MIN_SPECTRUM_DB,
    val sequence: Long = 0L,
) {
    val binWidthHz: Float
        get() = if (fftSize > 0) sampleRate.toFloat() / fftSize else 0f

    val windowMs: Float
        get() = if (sampleRate > 0) fftSize * 1000f / sampleRate else 0f
}

sealed interface StartResult {
    data class Started(val sampleRate: Int) : StartResult
    data class Failure(val message: String) : StartResult
}

/**
 * Captura el microfono, mantiene un buffer circular y publica periodicamente
 * un [SpectrumFrame] con la FFT y la frecuencia fundamental.
 *
 * - Muestreo pedido: 44.1 kHz (si el dispositivo no lo soporta se prueba 48 kHz
 *   y 22.05 kHz; el sample rate real se informa en cada frame).
 * - FFT de 4096 puntos con ventana de Hann y hop de 2048 muestras (~21 fps).
 * - Fundamental por YIN con interpolacion parabolica (50 Hz a 3 kHz).
 */
class SpectrumEngine(
    val fftSize: Int = 4096,
    val hopSize: Int = 2048,
    private val minFrequencyHz: Float = 50f,
    private val maxFrequencyHz: Float = 3_000f,
) {

    private val ring = RingBuffer(fftSize * 4)
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
    }
    private val fft = Fft(fftSize)
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val snapshot = FloatArray(fftSize)
    private val smoothedDb = FloatArray(fftSize / 2) { MIN_SPECTRUM_DB }

    private val _frame = MutableStateFlow(SpectrumFrame())
    val frame: StateFlow<SpectrumFrame> = _frame.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var capturing = false

    @Volatile
    private var activeSampleRate = DEFAULT_SAMPLE_RATE

    private var pitchDetector: PitchDetector? = null
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var sequence = 0L

    @Synchronized
    fun start(context: Context): StartResult {
        if (capturing) return StartResult.Started(activeSampleRate)

        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            return StartResult.Failure("Falta el permiso de micrófono")
        }

        val opened = openAudioRecord()
            ?: return StartResult.Failure(
                "No se pudo abrir el micrófono con 44.1 kHz, 48 kHz ni 22.05 kHz"
            )
        val record = opened.first
        val sampleRate = opened.second

        try {
            record.startRecording()
        } catch (error: Exception) {
            record.release()
            return StartResult.Failure("Error al iniciar la grabación: ${error.message}")
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return StartResult.Failure("El micrófono no pudo iniciar la captura")
        }

        activeSampleRate = sampleRate
        pitchDetector = PitchDetector(
            sampleRate = sampleRate,
            minFrequencyHz = minFrequencyHz,
            maxFrequencyHz = maxFrequencyHz,
        )
        ring.clear()
        smoothedDb.fill(MIN_SPECTRUM_DB)
        capturing = true
        _running.value = true
        _error.value = null
        audioRecord = record
        Log.i(TAG, "Captura iniciada a $sampleRate Hz")

        captureThread = Thread({ captureLoop(record, sampleRate) }, "fft-capture").apply {
            isDaemon = true
            start()
        }
        return StartResult.Started(sampleRate)
    }

    @Synchronized
    fun stop() {
        if (!capturing) return
        Log.i(TAG, "Deteniendo captura")
        capturing = false
        // Corta el read() bloqueante del hilo de captura; el release se hace
        // en el finally del propio hilo.
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        captureThread?.let { thread ->
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        captureThread = null
        audioRecord = null
        _running.value = false
    }

    private fun openAudioRecord(): Pair<AudioRecord, Int>? {
        val rates = intArrayOf(DEFAULT_SAMPLE_RATE, 48_000, 22_050)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )

        for (rate in rates) {
            val minBytes = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) continue

            for (source in sources) {
                var record: AudioRecord? = null
                try {
                    record = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(max(minBytes * 2, fftSize * 4))
                        .build()
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        return record to rate
                    }
                } catch (_: Exception) {
                    // Fuente o sample rate no soportados: probar la siguiente.
                }
                record?.release()
            }
        }
        return null
    }

    private fun captureLoop(record: AudioRecord, sampleRate: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val shortBuffer = ShortArray(2048)
        val floatBuffer = FloatArray(shortBuffer.size)
        var samplesSinceHop = 0

        try {
            while (capturing) {
                val read = record.read(shortBuffer, 0, shortBuffer.size)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read devolvió $read")
                    if (capturing) {
                        _error.value = "Se perdió la entrada de audio (código $read)"
                    }
                    break
                }
                if (read == 0) {
                    Thread.sleep(2)
                    continue
                }

                for (i in 0 until read) {
                    floatBuffer[i] = shortBuffer[i] / 32768f
                }
                ring.write(floatBuffer, read)
                samplesSinceHop += read
                if (samplesSinceHop >= hopSize) {
                    samplesSinceHop = 0
                    analyze(sampleRate)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Captura interrumpida", error)
            if (capturing) {
                _error.value = "Error de captura: ${error.message ?: error.javaClass.simpleName}"
            }
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
            capturing = false
            _running.value = false
        }
    }

    private fun analyze(sampleRate: Int) {
        if (!ring.readLatest(snapshot)) return

        var energy = 0.0
        for (sample in snapshot) {
            energy += sample.toDouble() * sample
        }
        val rms = sqrt(energy / fftSize).toFloat()
        val rmsDb = if (rms > 0f) (20.0 * log10(rms.toDouble())).toFloat() else MIN_SPECTRUM_DB

        for (i in 0 until fftSize) {
            real[i] = snapshot[i] * window[i]
            imaginary[i] = 0f
        }
        fft.transform(real, imaginary)

        val bins = fftSize / 2
        // Hann tiene ganancia coherente 0.5, por eso el factor 4/N.
        val amplitudeScale = 4f / fftSize
        for (bin in 1 until bins) {
            val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            val db = 20f * log10(max(magnitude * amplitudeScale, 1e-5f))
            val released = max(smoothedDb[bin] - 3f, MIN_SPECTRUM_DB)
            smoothedDb[bin] = max(db, released)
        }

        val pitch = if (rmsDb > -65f) {
            pitchDetector?.detect(snapshot, fftSize) ?: PitchResult(0f, 0f)
        } else {
            PitchResult(0f, 0f)
        }

        _frame.value = SpectrumFrame(
            magnitudesDb = smoothedDb.copyOf(),
            sampleRate = sampleRate,
            fftSize = fftSize,
            fundamentalHz = if (pitch.confidence >= 0.5f) pitch.frequencyHz else 0f,
            confidence = pitch.confidence,
            rmsDb = rmsDb,
            sequence = ++sequence,
        )
    }

    private companion object {
        const val TAG = "SpectrumEngine"
    }
}
