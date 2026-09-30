package com.proxianon.app.vpn

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Forwarder DNS (Fase 3, TCP): intercepta los paquetes UDP:53 que las apps mandan
 * al DNS del VPN y los resuelve por DNS over TCP a 1.1.1.1 **a traves del SOCKS5
 * local** (la resolucion sale por el VPS, sin fugas de DNS).
 *
 * Recibe el paquete IP completo (leido del TUN por Tun2SocksProcess), vuelca el
 * payload DNS, resuelve y devuelve un paquete IP de respuesta ya construido
 * (cambiando src/dst y recomputando el checksum IP).
 */
class DnsForwarder(
    private val socksPort: Int,
    private val upstreamHost: String = "1.1.1.1",
    private val upstreamPort: Int = 53,
) {

    // ------------------------------------------------------------- deteccion

    companion object {
        private const val IP_PROTO_UDP = 17
        private const val DNS_PORT = 53
        private const val QUERY_TIMEOUT_MS = 6_000
        private const val IP_HEADER_LEN = 20
        private const val UDP_HEADER_LEN = 8

        /** true si el paquete IP es UDP hacia el puerto DNS (53). */
        fun isDnsQuery(packet: ByteArray): Boolean {
            if (packet.size < 28) return false
            if ((packet[0].toInt() and 0xF0) != 0x40) return false // solo IPv4 en Fase 3
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (packet[9].toInt() != IP_PROTO_UDP) return false
            val udpOffset = ihl
            if (udpOffset + 8 > packet.size) return false
            val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
            return dstPort == DNS_PORT
        }
    }

    // ------------------------------------------------------------- resolucion

    /** Resuelve [packet] (IP+UDP+DNS) y devuelve el paquete de respuesta IP, o null si falla. */
    fun resolvePacket(packet: ByteArray): ByteArray? {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val udpOffset = ihl
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
        val payloadLen = udpLen - 8
        if (payloadLen <= 0 || udpOffset + 8 + payloadLen > packet.size) return null

        val query = packet.copyOfRange(udpOffset + 8, udpOffset + 8 + payloadLen)
        val responsePayload = resolveOverTcp(query) ?: return null
        return craftResponse(packet, srcPort, responsePayload)
    }

    /** DNS sobre TCP (RFC 1035: prefijo de 2 bytes con la longitud) a traves del SOCKS5. */
    private fun resolveOverTcp(query: ByteArray): ByteArray? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
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

    // ------------------------------------------------------------- crafting.

    /** Arma la respuesta IPv4: src/dst invertidos, puertos invertidos, checksum IP. */
    private fun craftResponse(request: ByteArray, requestSrcPort: Int, dnsPayload: ByteArray): ByteArray {
        val totalLen = IP_HEADER_LEN + UDP_HEADER_LEN + dnsPayload.size
        val out = ByteArray(totalLen)

        // Cabecera IP de 20 bytes.
        out[0] = 0x45 // IPv4 + IHL=5
        out[1] = 0
        out[2] = ((totalLen shr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = request[4] // id (eco)
        out[5] = request[5]
        out[6] = 0
        out[7] = 0
        out[8] = 64 // TTL
        out[9] = IP_PROTO_UDP.toByte()
        // out[10..11] = checksum (se calcula abajo)
        // src = dst del request
        out[12] = request[16]; out[13] = request[17]; out[14] = request[18]; out[15] = request[19]
        // dst = src del request
        out[16] = request[12]; out[17] = request[13]; out[18] = request[14]; out[19] = request[15]

        // Cabecera UDP.
        val udp = IP_HEADER_LEN
        out[udp] = 0 // src port = 53 (0x0035)
        out[udp + 1] = DNS_PORT.toByte()
        out[udp + 2] = ((requestSrcPort shr 8) and 0xFF).toByte()
        out[udp + 3] = (requestSrcPort and 0xFF).toByte()
        val udpLen = UDP_HEADER_LEN + dnsPayload.size
        out[udp + 4] = ((udpLen shr 8) and 0xFF).toByte()
        out[udp + 5] = (udpLen and 0xFF).toByte()
        out[udp + 6] = 0 // checksum UDP = 0 (opcional en IPv4)
        out[udp + 7] = 0
        // Payload DNS.
        System.arraycopy(dnsPayload, 0, out, udp + UDP_HEADER_LEN, dnsPayload.size)

        // Checksum de cabecera IP.
        val checksum = ipChecksum(out, 0, IP_HEADER_LEN)
        out[10] = ((checksum shr 8) and 0xFF).toByte()
        out[11] = (checksum and 0xFF).toByte()
        return out
    }

    /** Checksum IP (RFC 791) sobre [len] bytes desde [offset]. */
    private fun ipChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + len) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}