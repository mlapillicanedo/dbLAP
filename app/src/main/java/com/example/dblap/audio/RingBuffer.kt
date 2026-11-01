package com.example.dblap.audio

/**
 * Buffer circular de muestras float. El hilo de captura escribe y el hilo de
 * analisis lee siempre la ventana mas reciente, sin bloquear la grabacion.
 */
class RingBuffer(private val capacity: Int) {

    init {
        require(capacity > 0) { "La capacidad debe ser mayor que cero" }
    }

    private val data = FloatArray(capacity)
    private var writeIndex = 0
    private var size = 0

    /** Cantidad de muestras validas almacenadas (hasta [capacity]). */
    @Synchronized
    fun available(): Int = size

    /** Vacia el buffer (por ejemplo al reiniciar la captura). */
    @Synchronized
    fun clear() {
        writeIndex = 0
        size = 0
    }

    /** Escribe [count] muestras de [source] en el buffer. */
    @Synchronized
    fun write(source: FloatArray, count: Int) {
        var remaining = count.coerceAtMost(source.size)
        var offset = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writeIndex)
            System.arraycopy(source, offset, data, writeIndex, chunk)
            writeIndex = (writeIndex + chunk) % capacity
            offset += chunk
            remaining -= chunk
            size = minOf(capacity, size + chunk)
        }
    }

    /**
     * Copia las ultimas `destination.size` muestras en [destination].
     * Devuelve false si todavia no hay suficientes muestras.
     */
    @Synchronized
    fun readLatest(destination: FloatArray): Boolean {
        if (size < destination.size) return false

        var readIndex = (writeIndex - destination.size + capacity) % capacity
        val first = minOf(destination.size, capacity - readIndex)
        System.arraycopy(data, readIndex, destination, 0, first)
        if (first < destination.size) {
            System.arraycopy(data, 0, destination, first, destination.size - first)
        }
        return true
    }
}
