package com.proxianon.app.vpn

import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import kotlin.concurrent.thread
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * tun2socks (xjasonlyu, gvisor) cargado **como libreria nativa** en el proceso.
 *
 * Android 10+ bloquea por SELinux ejecutar binarios en /data/user/0 (error 13),
 * asi que tun2socks se compila como `libtun2socks.so` (jniLibs) y se carga con
 * System.loadLibrary (camino sancionado: dlopen de libs nativas).
 *
 * La libreria crea un socketpair SOCK_DGRAM: un extremo queda en Go como
 * "device" (fd://N) y el otro vuelve a Java. La app lee/escribe el TUN y: los
 * paquetes UDP:53 los responde [DnsForwarder] (DNS over TCP via SOCKS), el resto
 * se bombea al socketpair (-> stack gvisor -> SOCKS5 -> SSH -> VPS).
 */
class Tun2SocksProcess {

    private var running = false
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var appFdPfd: ParcelFileDescriptor? = null
    private var dnsWorkers: ExecutorService? = null

    val isRunning: Boolean
        get() = running

    init {
        Log.d(TAG, "STEP: System.loadLibrary(tun2socks) ...")
        System.loadLibrary("tun2socks")
        Log.d(TAG, "STEP: loadLibrary OK")
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
        step("openPair JNI ...")
        val appFd = tun2socksOpenPair()
        step("openPair rc=$appFd")
        if (appFd < 0) throw IllegalStateException("tun2socks: no se pudo crear el socketpair (rc=$appFd)")
        step("fromFd ...")
        val appPfd = ParcelFileDescriptor.fromFd(appFd)
        appFdPfd = appPfd
        step("start JNI ...")
        val rc = tun2socksStart(socksPort, mtu)
        step("start rc=$rc")
        if (rc != 0) {
            appPfd.close()
            appFdPfd = null
            throw IllegalStateException("tun2socks: start fallo (rc=$rc)")
        }
        running = true
        dnsWorkers = Executors.newFixedThreadPool(DNS_WORKERS)

        val input = FileInputStream(tunPfd.fileDescriptor)
        val output = FileOutputStream(tunPfd.fileDescriptor)
        tunIn = input
        tunOut = output

        onLog("tun2socks: in-process fd=$appFd proxy=socks5://127.0.0.1:$socksPort")

        // TUN -> tun2socks, interceptando DNS en el camino.
        thread(name = "vpn-tun-read", isDaemon = true) {
            val buf = ByteArray(65536)
            while (running) {
                try {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    TrafficMeter.addTx(n.toLong()) // salida del telefono
                    val packet = buf.copyOf(n)
                    if (DnsForwarder.isDnsQuery(packet)) {
                        dnsWorkers?.execute {
                            try {
                                val response = dns.resolvePacket(packet) ?: return@execute
                                synchronized(output) {
                                    output.write(response)
                                    TrafficMeter.addRx(response.size.toLong()) // respuesta DNS -> telefono
                                }
                            } catch (_: Exception) {
                                // Query perdida; el cliente reintentara.
                            }
                        }
                    } else {
                        try {
                            Os.write(appPfd.fileDescriptor, packet, 0, packet.size)
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
                    val n = Os.read(appPfd.fileDescriptor, buf, 0, buf.size)
                    if (n <= 0) continue
                    TrafficMeter.addRx(n.toLong()) // entrada al telefono
                    synchronized(output) { output.write(buf, 0, n) }
                } catch (e: Exception) {
                    if (running) onLog("tun2socks: proxy->TUN: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { tunIn?.close() }
        runCatching { tunOut?.close() }
        tunIn = null
        tunOut = null
        runCatching { appFdPfd?.close() }
        appFdPfd = null
        dnsWorkers?.shutdownNow()
        dnsWorkers = null
        runCatching { tun2socksStop() }
    }

    // ------------------------------------------------------------- JNI (libtun2socks.so)

    private external fun tun2socksOpenPair(): Int
    private external fun tun2socksStart(socksPort: Int, mtu: Int): Int
    private external fun tun2socksStop()

    private fun step(what: String) {
        Log.d(TAG, "STEP: $what")
    }

    private companion object {
        const val DNS_WORKERS = 8
        const val TAG = "PVPN"
    }
}