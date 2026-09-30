package com.proxianon.app.vpn

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proxy DNS local (Fase 3, modo TCP).
 *
 * El tun2socks solo traduce TCP, asi que el DNS (UDP:53) no puede atravesar el tunel.
 * Solucion estandar: el VpnService anuncia 127.0.0.1 como servidor DNS y este proxy
 * (escuchando en 127.0.0.1:53) reenvia cada consulta por TCP a 1.1.1.1:53 **a traves
 * del SOCKS5 local** -> la resolucion sale por el VPS (sin fugas de DNS).
 */
class DnsProxy(
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int,
    private val upstreamHost: String = "1.1.1.1",
    private val upstreamPort: Int = 53,
) {
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private val buffer = ByteArray(MAX_DNS_PAYLOAD)
    private lateinit var workers: ExecutorService

    fun start(onLog: (String) -> Unit = {}) {
        stop()
        running.set(true)
        workers = Executors.newFixedThreadPool(N_WORKERS)
        socket = DatagramSocket(
            InetSocketAddress(InetAddress.getLoopbackAddress(), 53)
        ).also { ds ->
            threadOf("dns-proxy") {
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        ds.receive(packet)
                        // Copia del query + remitente (el buffer se reutiliza).
                        val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        val sender = InetSocketAddress(packet.address, packet.port)
                        workers.execute { handle(query, sender, ds) }
                    } catch (e: Exception) {
                        if (running.get()) onLog("dns: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handle(query: ByteArray, sender: InetSocketAddress, ds: DatagramSocket) {
        try {
            val response = resolveOverTcp(query) ?: return
            runCatching { ds.send(DatagramPacket(response, response.size, sender)) }
        } catch (_: Exception) {
            // Query perdida: el cliente reintentara.
        }
    }

    /** DNS sobre TCP (RFC 1035: prefijo de 2 bytes con la longitud) a traves del SOCKS5. */
    private fun resolveOverTcp(query: ByteArray): ByteArray? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
        Socket(proxy).use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(upstreamHost, upstreamPort), QUERY_TIMEOUT_MS)
            s.soTimeout = QUERY_TIMEOUT_MS
            val out = DataOutputStream(s.getOutputStream())
            val inStream = DataInputStream(s.getInputStream())

            out.writeShort(query.size)
            out.write(query)
            out.flush()

            val responseLen = inStream.readUnsignedShort()
            val response = ByteArray(responseLen)
            inStream.readFully(response)
            return response
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        if (::workers.isInitialized) {
            workers.shutdownNow()
        }
    }

    private fun threadOf(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }

    private companion object {
        const val MAX_DNS_PAYLOAD = 4096
        const val N_WORKERS = 8
        const val QUERY_TIMEOUT_MS = 6_000
    }
}