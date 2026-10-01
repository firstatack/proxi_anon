package com.proxianon.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Snapshot de trafico de la sesion VPN en curso. */
data class TrafficStats(
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val sessionStartedAtMs: Long = 0L,
) {
    val totalBytes: Long get() = txBytes + rxBytes
    val uptimeSeconds: Long
        get() = if (sessionStartedAtMs > 0) (System.currentTimeMillis() - sessionStartedAtMs) / 1000 else 0
}

/**
 * Contadores de trafico del tunel (TX = salida del telefono, RX = entrada).
 * Los incrementa el pump de Tun2SocksProcess; un tick publica snapshots al StateFlow.
 */
object TrafficMeter {

    private val txCounter = AtomicLong(0)
    private val rxCounter = AtomicLong(0)
    private val startedAt = AtomicLong(0)

    private val _flow = MutableStateFlow(TrafficStats())
    val flow: StateFlow<TrafficStats> = _flow.asStateFlow()

    fun addTx(n: Long) {
        if (n > 0) txCounter.addAndGet(n)
    }

    fun addRx(n: Long) {
        if (n > 0) rxCounter.addAndGet(n)
    }

    fun startSession() {
        startedAt.set(System.currentTimeMillis())
    }

    /** Publica un snapshot (lo llama el tick del servicio ~1s). */
    fun publish() {
        _flow.value = TrafficStats(txCounter.get(), rxCounter.get(), startedAt.get())
    }

    fun reset() {
        txCounter.set(0)
        rxCounter.set(0)
        startedAt.set(0)
        _flow.value = TrafficStats()
    }
}