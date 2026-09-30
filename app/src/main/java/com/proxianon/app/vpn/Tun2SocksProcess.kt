package com.proxianon.app.vpn

import android.content.Context
import android.os.Build
import kotlin.concurrent.thread
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lanza el binario [tun2socks](https://github.com/xjasonlyu/tun2socks) (Go/gvisor)
 * compilado en CI y empaquetado en assets (Fase 3).
 *
 * Lee/escribe directamente el fd de la interfaz TUN del [android.net.VpnService] y
 * convierte los paquetes IP en conexiones TCP hacia el SOCKS5 local del SshTunnel.
 */
class Tun2SocksProcess(private val context: Context) {

    private var process: Process? = null
    private var logThread: Thread? = null

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
     * @param tunFd numero de file descriptor de la interfaz TUN (ParcelFileDescriptor.fd).
     * @param socksPort puerto del SOCKS5 local (el que abre SshTunnel).
     */
    fun start(tunFd: Int, socksPort: Int, mtu: Int = 1500, onLog: (String) -> Unit = {}) {
        stop()
        val exe = extractBinary()

        val cmd = listOf(
            exe.absolutePath,
            "-device", "fd://$tunFd",
            "-proxy", "socks5://127.0.0.1:$socksPort",
            "-mtu", mtu.toString(),
            "-loglevel", "info",
        )

        val p = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        process = p

        // Volcar stdout del binario al log compartido (para depuracion).
        logThread = thread(name = "tun2socks-log", isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) onLog("tun2socks: $line")
            }
        }
    }

    fun stop() {
        logThread?.interrupt()
        logThread = null
        val p = process ?: return
        process = null
        runCatching { p.destroy() }
        runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        if (p.isAlive) runCatching { p.destroyForcibly() }
    }
}