package com.proxianon.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.proxianon.app.R
import com.proxianon.app.ssh.SshTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modo VPN (Fase 3): enruta TODO el trafico del telefono por el tunel SSH.
 *
 * Pipeline:
 *   [apps] TCP -> ruta 0.0.0.0/0 -> interfaz TUN -> fd -> tun2socks (gvisor)
 *          -> SOCKS5 local (127.0.0.1) -> SshTunnel -> VPS -> Internet
 *   [apps] DNS (UDP:53) -> 127.0.0.1 -> DnsProxy (TCP via SOCKS) -> 1.1.1.1
 *
 * La propia app se excluye del VPN (addDisallowedApplication): el socket SSH y el
 * del DNS proxy salen directo y no hay bucle en el TUN (no hace falta protect()
 * sobre el canal NIO2 de MINA).
 */
class TunnelVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ssh: SshTunnel? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var tun2socks: Tun2SocksProcess? = null
    private var dns: DnsProxy? = null
    private var running = false

    // ---------------------------------------------------------------- ciclo

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
        if (!running) {
            VpnState.set(VpnUiState.Starting)
            VpnState.log("VPN: arrancando ...")
            scope.launch { runVpn() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        stopVpn()
        super.onDestroy()
    }

    // ------------------------------------------------------------ pipeline

    private suspend fun runVpn() {
        val req = VpnSession.request
        if (req == null || req.host.isBlank() || req.username.isBlank()) {
            fail("VPN: falta la configuracion de la sesion.")
            return
        }
        if (VpnService.prepare(this) != null) {
            fail("VPN: permiso de VPN no concedido.")
            return
        }

        try {
            // 1) Tunel SSH + SOCKS5 local (sesion propia de este servicio).
            VpnState.log("VPN: conectando SSH a ${req.host}:${req.port} ...")
            val socksPort = withContext(Dispatchers.IO) {
                val c = SshTunnel()
                ssh = c
                c.connect(req.host, req.port, req.username, req.password)
            }
            VpnState.log("VPN: SSH OK, SOCKS5 local en 127.0.0.1:$socksPort")

            // 2) Interfaz TUN (captura todo el trafico IPv4 salvo la propia app).
            val builder = VpnService.Builder()
            builder.addAddress("10.8.0.2", 24)
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("127.0.0.1") // nuestro DnsProxy escucha aqui
            builder.setMtu(MTU)
            builder.setSession("ProxiAnon")
            // Evita el bucle: el trafico de esta app (SSH, DNS proxy) sale directo.
            runCatching { builder.addDisallowedApplication(packageName) }

            val fd = builder.establish()
            if (fd == null) throw IllegalStateException("establish() devolvio null")
            tunFd = fd
            // Defensivo: que el fd sobreviva al exec de tun2socks.
            runCatching { Os.fcntlInt(fd.fd, OsConstants.F_SETFD, 0) }
            VpnState.log("VPN: interfaz TUN creada (fd=${fd.fd})")

            // 3) tun2socks: TUN -> SOCKS5; y proxy DNS local.
            VpnState.log("VPN: lanzando tun2socks ...")
            tun2socks = Tun2SocksProcess(this).also {
                it.start(fd.fd, socksPort, MTU) { line -> VpnState.log(line) }
            }
            dns = DnsProxy(socksPort = socksPort).also { proxy ->
                proxy.start { line -> VpnState.log(line) }
            }

            running = true
            VpnState.set(VpnUiState.On)
            VpnState.log("VPN: ACTIVO. Todo el trafico sale por ${req.host}")

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification("Activo - salida por ${req.host}"))
        } catch (t: Throwable) {
            fail("VPN: ${if (t.message.isNullOrBlank()) t::class.java.simpleName else t.message}")
        }
    }

    private fun fail(message: String) {
        VpnState.log(message)
        VpnState.set(VpnUiState.Error(message))
        stopVpn()
    }

    private fun stopVpn() {
        running = false
        runCatching { tun2socks?.stop() }
        runCatching { dns?.stop() }
        runCatching { tunFd?.close() }
        tunFd = null
        tun2socks = null
        dns = null
        runCatching { ssh?.disconnect() }
        ssh = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnState.set(VpnUiState.Off)
        VpnState.log("VPN: desconectado.")
    }

    // ---------------------------------------------------------- notificacion

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ProxiAnon VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ProxiAnon")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(stopIntent)
            .addAction(0, "Desconectar", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.proxianon.app.vpn.STOP"
        private const val CHANNEL_ID = "proxianon_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
    }
}