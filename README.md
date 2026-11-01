# Analizador FFT en vivo

App Android (Kotlin + Jetpack Compose) que captura el micrófono, calcula la FFT
en tiempo real y muestra la frecuencia fundamental detectada.

## Qué hace

- Captura mono PCM 16 bits a **44.1 kHz** con `AudioRecord`.
  - Si el dispositivo no soporta 44.1 kHz, prueba 48 kHz y 22.05 kHz, e informa
    el sample rate real en pantalla.
- Guarda las muestras en un **buffer circular** de 4 × 4096 muestras.
- Calcula una **FFT radix-2 de 4096 puntos** cada 2048 muestras (~21 fps) con
  ventana de Hann.
- Detecta la fundamental con **YIN** (diferencia normalizada + interpolación
  parabólica) entre 50 Hz y 3 kHz, y la muestra con nombre de nota y desvío en
  cents.
- Dibuja el espectro en escala logarítmica (40 Hz – 16 kHz) con grilla de dBFS,
  relleno degradado y marcador de la fundamental.

## Estructura

```
app/src/main/java/com/example/dblap/
├── MainActivity.kt              # Ciclo de vida y liberación del micrófono
├── audio/
│   ├── Fft.kt                   # FFT radix-2 in-place
│   ├── RingBuffer.kt            # Buffer circular thread-safe
│   ├── PitchDetector.kt         # YIN + interpolación parabólica
│   └── SpectrumEngine.kt        # AudioRecord, ventana, análisis y frames
└── ui/
    └── SpectrumScreen.kt        # Pantalla Compose + Canvas del espectro
```

## Ajustes rápidos

- Frecuencia de muestreo: `DEFAULT_SAMPLE_RATE` en `SpectrumEngine.kt`.
- Tamaño de FFT y hop: parámetros `fftSize` / `hopSize` de `SpectrumEngine`.
- Rango de la fundamental: `minFrequencyHz` / `maxFrequencyHz`.
- Rango visible del eje X: `spectrumMaxFrequency()` en `SpectrumScreen.kt`.

## Compilar

```bash
./gradlew :app:assembleDebug
```

Tests de DSP (FFT, YIN y buffer circular):

```bash
./gradlew :app:testDebugUnitTest
```
