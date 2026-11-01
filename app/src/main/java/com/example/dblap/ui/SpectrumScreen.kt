package com.example.dblap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.dblap.audio.MAX_SPECTRUM_DB
import com.example.dblap.audio.MIN_SPECTRUM_DB
import com.example.dblap.audio.SpectrumEngine
import com.example.dblap.audio.SpectrumFrame
import com.example.dblap.audio.StartResult
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private val PanelBackground = Color(0xFF0B1017)
private val PanelGrid = Color(0xFF1C2533)
private val PanelLabel = Color(0xFF8B94A7)
private val CurveColor = Color(0xFF4ADE80)
private val MarkerColor = Color(0xFFFBBF24)

private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun SpectrumScreen(engine: SpectrumEngine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frame by engine.frame.collectAsState()
    val running by engine.running.collectAsState()
    val engineError by engine.error.collectAsState()

    var hasPermission by remember { mutableStateOf(hasRecordPermission(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(engineError) {
        if (engineError != null) {
            errorMessage = engineError
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.stop() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            errorMessage = null
            when (val result = engine.start(context)) {
                is StartResult.Started -> Unit
                is StartResult.Failure -> errorMessage = result.message
            }
        } else {
            errorMessage = "Permiso de micrófono denegado"
        }
    }

    val startEngine: () -> Unit = {
        errorMessage = null
        val granted = hasRecordPermission(context)
        hasPermission = granted
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            when (val result = engine.start(context)) {
                is StartResult.Started -> Unit
                is StartResult.Failure -> errorMessage = result.message
            }
        }
    }

    val openSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            errorMessage = "No se pudieron abrir los ajustes"
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                running = running,
                sampleRate = frame.sampleRate,
                onToggle = { if (running) engine.stop() else startEngine() },
            )

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            FundamentalCard(frame = frame)

            if (!hasPermission) {
                PermissionCard(
                    modifier = Modifier.weight(1f),
                    onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenSettings = openSettings,
                )
            } else {
                SpectrumPanel(
                    frame = frame,
                    running = running,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = statsLine(frame, engine.fftSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(running: Boolean, sampleRate: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Analizador FFT",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Micrófono en vivo · ${formatRate(sampleRate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (running) {
            FilledTonalButton(onClick = onToggle) { Text("Detener") }
        } else {
            Button(onClick = onToggle) { Text("Iniciar") }
        }
    }
}

@Composable
private fun FundamentalCard(frame: SpectrumFrame) {
    val hasPitch = frame.fundamentalHz > 0f
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "FRECUENCIA FUNDAMENTAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (hasPitch) {
                            String.format(Locale.getDefault(), "%.1f", frame.fundamentalHz)
                        } else {
                            "—"
                        },
                        fontSize = 46.sp,
                        fontWeight = if (hasPitch) FontWeight.Bold else FontWeight.Light,
                        color = if (hasPitch) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        lineHeight = 52.sp,
                    )
                    Text(
                        text = " Hz",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    text = noteDescription(frame),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasPitch) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "NIVEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = String.format(Locale.getDefault(), "%.0f dBFS", frame.rmsDb),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "CONFIANZA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = "${((if (hasPitch) frame.confidence else 0f) * 100f).roundToInt()} %",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Acceso al micrófono",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "La app necesita escuchar el micrófono para calcular la FFT y la " +
                        "frecuencia fundamental en tiempo real. El audio no se guarda ni se envía.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRequest) { Text("Permitir micrófono") }
                TextButton(onClick = onOpenSettings) { Text("Abrir ajustes de la app") }
            }
        }
    }
}

@Composable
private fun SpectrumPanel(
    frame: SpectrumFrame,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(PanelBackground)
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "ESPECTRO",
                style = MaterialTheme.typography.labelSmall,
                color = PanelLabel,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "40 Hz – ${formatMaxFrequency(spectrumMaxFrequency(frame.sampleRate))} · escala log",
                style = MaterialTheme.typography.labelSmall,
                color = PanelLabel.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSpectrum(frame, paint)
            }
            if (frame.magnitudesDb.size < 4) {
                Text(
                    text = if (running) {
                        "Esperando audio…"
                    } else {
                        "El espectro aparecerá al iniciar"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelLabel,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpectrum(
    frame: SpectrumFrame,
    paint: android.graphics.Paint,
) {
    val width = size.width
    val height = size.height
    val leftPad = 30.dp.toPx()
    val bottomPad = 16.dp.toPx()
    val plotWidth = (width - leftPad).coerceAtLeast(1f)
    val plotHeight = (height - bottomPad).coerceAtLeast(1f)

    val fMin = 40f
    val fMax = spectrumMaxFrequency(frame.sampleRate)
    val logSpan = ln(fMax / fMin)

    fun xForFrequency(frequency: Float): Float =
        leftPad + plotWidth * (ln(frequency / fMin) / logSpan)

    fun yForDb(db: Float): Float {
        val normalized = ((db - MIN_SPECTRUM_DB) / (MAX_SPECTRUM_DB - MIN_SPECTRUM_DB))
            .coerceIn(0f, 1f)
        return plotHeight - normalized * plotHeight
    }

    paint.textSize = 9.sp.toPx()
    paint.typeface = android.graphics.Typeface.MONOSPACE

    // Rejilla horizontal (dB).
    paint.textAlign = android.graphics.Paint.Align.RIGHT
    paint.color = PanelLabel.toArgb()
    for (db in 0 downTo -100 step 20) {
        val y = yForDb(db.toFloat())
        drawLine(PanelGrid, Offset(leftPad, y), Offset(width, y), strokeWidth = 1f)
        if (db < 0) {
            drawContext.canvas.nativeCanvas.drawText("$db", leftPad - 5f, y - 3f, paint)
        }
    }

    // Rejilla vertical (frecuencia).
    paint.textAlign = android.graphics.Paint.Align.CENTER
    val ticks = intArrayOf(50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000)
    for (tick in ticks) {
        if (tick < fMin || tick > fMax) continue
        val x = xForFrequency(tick.toFloat())
        drawLine(PanelGrid, Offset(x, 0f), Offset(x, plotHeight), strokeWidth = 1f)
        val label = if (tick >= 1_000) "${tick / 1_000}k" else tick.toString()
        drawContext.canvas.nativeCanvas.drawText(
            label,
            x,
            height - 3f,
            paint,
        )
    }

    // Curva del espectro.
    val magnitudes = frame.magnitudesDb
    if (magnitudes.size > 4 && frame.fftSize > 0) {
        val binWidth = frame.sampleRate.toFloat() / frame.fftSize
        val line = Path()
        val fill = Path()
        fill.moveTo(leftPad, plotHeight)
        var started = false
        val step = 2f
        var x = 0f
        while (x <= plotWidth) {
            val fa = fMin * exp(logSpan * (x / plotWidth))
            val fb = fMin * exp(logSpan * ((x + step) / plotWidth))
            val firstBin = (fa / binWidth).toInt().coerceIn(1, magnitudes.size - 1)
            val lastBin = (fb / binWidth).toInt().coerceIn(firstBin, magnitudes.size - 1)
            var peak = MIN_SPECTRUM_DB
            for (bin in firstBin..lastBin) {
                if (magnitudes[bin] > peak) peak = magnitudes[bin]
            }
            val y = yForDb(peak)
            val px = leftPad + x
            if (!started) {
                line.moveTo(px, y)
                started = true
            } else {
                line.lineTo(px, y)
            }
            fill.lineTo(px, y)
            x += step
        }
        fill.lineTo(leftPad + plotWidth, plotHeight)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(CurveColor.copy(alpha = 0.30f), Color.Transparent),
                startY = 0f,
                endY = plotHeight,
            ),
        )
        drawPath(
            path = line,
            color = CurveColor,
            style = Stroke(width = 2f, cap = StrokeCap.Round),
        )
    }

    // Marcador de la frecuencia fundamental.
    val fundamental = frame.fundamentalHz
    if (fundamental in fMin..fMax && magnitudes.size > 4) {
        val x = xForFrequency(fundamental)
        drawLine(
            color = MarkerColor.copy(alpha = 0.9f),
            start = Offset(x, 0f),
            end = Offset(x, plotHeight),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
        )
        paint.textAlign = android.graphics.Paint.Align.LEFT
        paint.color = MarkerColor.toArgb()
        val text = String.format(Locale.getDefault(), "%.1f Hz", fundamental)
        val textX = (x + 5f).coerceAtMost(width - paint.measureText(text) - 2f)
        drawContext.canvas.nativeCanvas.drawText(text, textX, paint.textSize + 4f, paint)
    }
}

private fun noteDescription(frame: SpectrumFrame): String {
    if (frame.fundamentalHz <= 0f) return "Sin tono detectado"
    val midi = 69f + 12f * log2(frame.fundamentalHz / 440f)
    val nearest = midi.roundToInt()
    val cents = ((midi - nearest) * 100f).roundToInt()
    val name = NOTE_NAMES[((nearest % 12) + 12) % 12]
    val octave = nearest / 12 - 1
    val sign = if (cents >= 0) "+" else ""
    return "$name$octave · $sign$cents cents"
}

private fun statsLine(frame: SpectrumFrame, fallbackFftSize: Int): String {
    val fftSize = if (frame.fftSize > 0) frame.fftSize else fallbackFftSize
    val rate = frame.sampleRate
    val binWidth = rate.toFloat() / fftSize
    val windowMs = fftSize * 1000f / rate
    return String.format(
        Locale.getDefault(),
        "%s · FFT %d · Δf %.1f Hz · ventana %.0f ms",
        formatRate(rate),
        fftSize,
        binWidth,
        windowMs,
    )
}

private fun formatRate(rate: Int): String =
    String.format(Locale.getDefault(), "%.1f kHz", rate / 1000f)

private fun spectrumMaxFrequency(sampleRate: Int): Float =
    minOf(16_000f, sampleRate / 2f).coerceAtLeast(2_000f)

private fun formatMaxFrequency(hz: Float): String =
    if (hz >= 1_000f) {
        String.format(Locale.getDefault(), "%.0f kHz", hz / 1000f)
    } else {
        "${hz.roundToInt()} Hz"
    }
