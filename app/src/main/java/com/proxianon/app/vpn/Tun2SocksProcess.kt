package com.proxianon.app.vpn

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlin.concurrent.thread
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lanza el binario [tun2socks](https://github.com/xjasonlyu/tun2socks) (Go/gvisor)
 * compilado en CI y empaquetado en assets (Fase 3, TCP).
 *
 * En vez de pasarle el fd del TUN (asi la app no veria el DNS), la APP lee/escribe
 * la interfaz TUN y tun2socks usa un **socketpair SOCK_DGRAM** como "device"
 * (`-device fd://N`): cada datagrama es un paquete IP.
 *
 * En el camino la app intercepta DNS (UDP:53) y lo resuelve con [DnsForwarder]
 * (DNS over TCP a traves del SOCKS5 -> sale por el VPS). Lo demas va a tun2socks.
 */
class Tun2SocksProcess(private val context: Context) {

    private var process: Process? = null
    private var logThread: Thread? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var appSockFd: FileDescriptor? = null
    private var running = false
    private var dnsWorkers: ExecutorService? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    /** Extrae el binario de assets -> filesDir (los assets no admiten bit de ejecucion). */
    private fun extractBinary(): File {
        val dir = File(context.filesDir, "tun2socks")
        val exe = File(dir, "tun2socks")
        val marker = File(dir, ".abi")

        // Elige el primer ABI soportado por el dispositivo que tenga binario empaquetado.
        val abi = pickAbi()
            ?: throw IllegalStateException(
                "No hay tun2socks para este dispositivo (ABIs: " +
                    Build.SUPPORTED_ABIS.joinToString() +
                    "; empaquetadas: ${bundledAbis().joinToString().ifEmpty { "ninguna" }})"
            )

        if (!exe.exists() || marker.textOrNull() != abi) {
            dir.mkdirs()
            context.assets.open("tun2socks/$abi/tun2socks").use { input ->
                exe.outputStream().use { output -> input.copyTo(output) }
            }
            exe.setExecutable(true, true)
            marker.writeText(abi)
        }
        return exe
    }

    private fun pickAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { abi -> hasBundledBinary(abi) }

    /** ABIs para las que el APK trae binario (en assets). */
    private fun bundledAbis(): List<String> =
        listOf("arm64-v8a", "armeabi-v7a", "x86_64").filter { hasBundledBinary(it) }

    private fun hasBundledBinary(abi: String): Boolean =
        runCatching { context.assets.open("tun2socks/$abi/tun2socks").close() }.isSuccess

    private fun File.textOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    /**
     * Numero de fd (int) de un [FileDescriptor]. No hay API publica para obtenerlo;
     * se lee el campo interno del classloader del sistema (patron estandar en Android).
     */
    @Suppress("PrivateApi")
    private fun FileDescriptor.fdNumber(): Int {
        val cls = java.io.FileDescriptor::class.java
        for (name in listOf("fd", "descriptor")) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.getInt(this)
            } catch (_: Exception) {
                continue
            }
        }
        throw IllegalStateException("No se pudo obtener el fd numerico")
    }

    /**
     * @param tunPfd fd de la interfaz TUN (lo lee/escribe la app).
     * @param socksPort puerto del SOCKS5 local (el que abre SshTunnel).
     * @param dns forwarder DNS (UDP:53 -> DNS over TCP via SOCKS).
     */
    fun start(
        tunPfd: ParcelFileDescriptor,
        socksPort: Int,
        mtu: Int = 1500,
        dns: DnsForwarder,
        onLog: (String) -> Unit = {},
    ) {
        stop()
        val exe = extractBinary()

        // Socketpair AF_UNIX SOCK_DGRAM: cada datagrama es un paquete IP (framing limpio).
        val fd1 = FileDescriptor()
        val fd2 = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, fd1, fd2)
        // Sin FD_CLOEXEC para que el hijo los herede tras exec.
        Os.fcntlInt(fd1, OsConstants.F_SETFD, 0)
        Os.fcntlInt(fd2, OsConstants.F_SETFD, 0)

        val cmd = listOf(
            exe.absolutePath,
            "-device", "fd://${fd1.fdNumber()}",
            "-proxy", "socks5://127.0.0.1:$socksPort",
            "-mtu", mtu.toString(),
            "-loglevel", "info",
        )

        val p = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        process = p
        running = true
        dnsWorkers = Executors.newFixedThreadPool(DNS_WORKERS)

        val input = FileInputStream(tunPfd.fileDescriptor)
        val output = FileOutputStream(tunPfd.fileDescriptor)
        tunIn = input
        tunOut = output
        appSockFd = fd2

        onLog("tun2socks: fd=${fd1.fdNumber()} proxy=socks5://127.0.0.1:$socksPort")

        // TUN -> tun2socks, interceptando DNS en el camino.
        thread(name = "vpn-tun-read", isDaemon = true) {
            val buf = ByteArray(65536)
            while (running) {
                try {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    val packet = buf.copyOf(n)
                    if (DnsForwarder.isDnsQuery(packet)) {
                        dnsWorkers?.execute {
                            try {
                                val response = dns.resolvePacket(packet) ?: return@execute
                                synchronized(output) { output.write(response) }
                            } catch (_: Exception) {
                                // Query perdida; el cliente reintentara.
                            }
                        }
                    } else {
                        try {
                            Os.write(fd2, packet, 0, packet.size)
                        } catch (_: Exception) {
                            onLog("tun2socks: TUN->proxy write fallo")
                        }
                    }
                } catch (e: Exception) {
                    if (running) onLog("tun2socks: TUN read: ${e.message}")
                }
            }
        }

        // tun2socks -> TUN.
        thread(name = "vpn-socks-read", isDaemon = true) {
            val buf = ByteArray(65536)
            while (running) {
                try {
                    val n = Os.read(fd2, buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(output) { output.write(buf, 0, n) }
                } catch (e: Exception) {
                    if (running) onLog("tun2socks: proxy->TUN: ${e.message}")
                }
            }
        }

        // Volcar stdout del binario al log compartido (para depuracion).
        logThread = thread(name = "tun2socks-log", isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) onLog("tun2socks: $line")
            }
        }
    }

    fun stop() {
        running = false
        logThread?.interrupt()
        logThread = null
        runCatching { tunIn?.close() }
        runCatching { tunOut?.close() }
        tunIn = null
        tunOut = null
        appSockFd?.let { runCatching { Os.close(it) } }
        appSockFd = null
        dnsWorkers?.shutdownNow()
        dnsWorkers = null
        val p = process ?: return
        process = null
        runCatching { p.destroy() }
        runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        if (p.isAlive) runCatching { p.destroyForcibly() }
    }

    private companion object {
        const val DNS_WORKERS = 8
    }
}