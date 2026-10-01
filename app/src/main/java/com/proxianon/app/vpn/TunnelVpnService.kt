package com.proxianon.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.proxianon.app.R
import com.proxianon.app.ssh.SshTunnel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modo VPN (Fase 3): enruta TODO el trafico del telefono por el tunel SSH, con
 * robustez (Fase 4): reconexion automatica con backoff, persistencia cifrada de
 * la sesion (START_STICKY reanuda solo) y reaccion a cambios de red.
 *
 * Pipeline:
 *   [apps] TCP -> ruta 0.0.0.0/0 -> interfaz TUN -> la app lee los paquetes
 *          -> tun2socks (gvisor, via socketpair "fd://") -> SOCKS5 local
 *          -> SshTunnel -> VPS -> Internet
 *   [apps] DNS (UDP:53) -> la app lo intercepta en el TUN -> DnsForwarder
 *          (DNS over TCP via SOCKS) -> 1.1.1.1
 *
 * Mientras se reconecta el TUN sigue vivo (los paquetes se descartan, no escapan
 * en claro) -> kill-switch implicito. La propia app se excluye del VPN.
 */
class TunnelVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ssh: SshTunnel? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var tun2socks: Tun2SocksProcess? = null
    private var dns: DnsForwarder? = null
    private var creds: SshCredentials? = null

    private var vpnActive = false
    private var reconnectAttempt = 0
    private var monitorJob: Job? = null
    private var statsJob: Job? = null
    private var notifyJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun step(what: String) {
        VpnState.log("vpn: $what")
    }

    // ---------------------------------------------------------------- ciclo

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                if (!vpnActive) {
                    logPreviousGoError() // si el arranque anterior murio en Go, muestra el por que
                    // Arranque manual (Action "Conectar VPN") o reinicio del sistema
                    // (START_STICKY, intent null): si estaba activo, reanudamos solo.
                    val wasActive = ProfileStore.vpnWasActive(this)
                    creds = VpnSession.request
                        ?: if (wasActive) ProfileStore.loadLastSession(this) else null
                    if (creds != null) {
                        VpnSession.request = null
                        vpnActive = true
                        VpnState.set(VpnUiState.Starting)
                        VpnState.log("VPN: arrancando ...")
                        startForeground(NOTIFICATION_ID, buildNotification("Conectando..."))
                        registerNetworkCallback()
                        scope.launch { run() }
                    } else {
                        stopSelf()
                    }
                }
            }
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

    private suspend fun run() {
        if (!vpnActive) return
        step("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        try {
            TrafficMeter.startSession()
            if (tunFd == null) establishInterface()
            step("TUN lista")
            ensureLinkedAndStack()
            step("stack listo")
            reconnectAttempt = 0
            VpnState.set(VpnUiState.On)
            ProfileStore.setVpnWasActive(this, true)
            creds?.let { ProfileStore.saveLastSession(this, it) }
            VpnState.log("VPN: ACTIVO. Todo el trafico sale por ${creds?.host ?: ""}")
            notifyActive()
            startMonitor()
            startStatsLoops()
        } catch (t: Throwable) {
            onLinkFailure(t)
        }
    }

    /** Crea (una sola vez) la interfaz TUN. */
    private suspend fun establishInterface() {
        if (VpnService.prepare(this) != null) {
            throw IllegalStateException("permiso de VPN no concedido")
        }
        if (creds == null) throw IllegalStateException("sin credenciales")

        val builder = Builder() // clase inner de VpnService: usa el receiver implicito.
        builder.addAddress("10.8.0.2", 24)
        builder.addRoute("0.0.0.0", 0)
        builder.addDnsServer("1.1.1.1")
        builder.setMtu(MTU)
        builder.setSession("ProxiAnon")
        runCatching { builder.addDisallowedApplication(packageName) }

        val fd = builder.establish()
        if (fd == null) throw IllegalStateException("establish() devolvio null")
        tunFd = fd
        VpnState.log("VPN: interfaz TUN creada (fd=${fd.fd})")
    }

    /** Re-arma el camino SSH -> SOCKS -> tun2socks + DNS (se puede repetir). */
    private suspend fun ensureLinkedAndStack() {
        shutdownStack() // limpia lo anterior manteniendo el TUN
        val c = creds ?: throw IllegalStateException("sin credenciales")
        step("conectando SSH a ${c.host}:${c.port}")
        val socksPort = withContext(Dispatchers.IO) {
            val t = SshTunnel()
            ssh = t
            t.connect(c.host, c.port, c.username, c.password)
        }
        step("SSH OK socks=$socksPort")
        val fd = tunFd ?: throw IllegalStateException("sin interfaz TUN")
        val dnsForwarder = DnsForwarder(socksPort = socksPort)
        dns = dnsForwarder
        step("antes de tun2socks.start")
        tun2socks = Tun2SocksProcess().also {
            it.start(fd, socksPort, MTU, dnsForwarder) { line -> VpnState.log(line) }
        }
        step("tun2socks.start retorno OK")
    }

    // ---------------------------------------------------------- reconexion

    /** Caida del enlace: programa reintentos con backoff (no corta el VPN). */
    private fun onLinkFailure(t: Throwable) {
        val msg = t.message ?: t::class.java.simpleName
        VpnState.log("VPN: $msg")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!vpnActive || reconnectJob != null) return
        monitorJob?.cancel()
        VpnState.set(VpnUiState.Reconnecting(1))
        reconnectJob = scope.launch {
            var attempt = 0
            while (vpnActive && isActive) {
                attempt++
                reconnectAttempt = attempt
                val waitMs = backoff(attempt)
                VpnState.set(VpnUiState.Reconnecting(attempt))
                VpnState.log("VPN: reconectando (intento $attempt) en ${waitMs / 1000}s ...")
                delay(waitMs)
                try {
                    ensureLinkedAndStack()
                    reconnectAttempt = 0
                    VpnState.set(VpnUiState.On)
                    ProfileStore.setVpnWasActive(this@TunnelVpnService, true)
                    notifyActive()
                    reconnectJob = null
                    startMonitor()
                    return@launch
                } catch (t: Throwable) {
                    VpnState.log("VPN: reintento fallo: ${t.message ?: t::class.java.simpleName}")
                }
            }
            reconnectJob = null
        }
    }

    /** Vigila que el SSH y tun2socks sigan vivos. */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (vpnActive && isActive) {
                delay(MONITOR_INTERVAL_MS)
                val alive = ssh?.isConnected == true && tun2socks?.isRunning == true
                if (!alive) {
                    VpnState.log("VPN: enlace caido, reconectando ...")
                    scheduleReconnect()
                    return@launch
                }
            }
        }
    }

    /** Publica stats para la UI (1s) y refresca la notificacion cada 5s. */
    private fun startStatsLoops() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (vpnActive && isActive) {
                TrafficMeter.publish()
                delay(STATS_PUBLISH_MS)
            }
        }
        notifyJob?.cancel()
        notifyJob = scope.launch {
            while (vpnActive && isActive) {
                delay(NOTIFY_REFRESH_MS)
                val s = TrafficMeter.flow.value
                val text = "Activo · ↓ ${fmtBytes(s.rxBytes)}  ↑ ${fmtBytes(s.txBytes)} · ${creds?.host ?: "-"}"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB")
        var v = b.toDouble()
        var u = -1
        while (v >= 1024 && u < units.lastIndex) {
            v /= 1024
            u++
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, if (u < 0) "B" else units[u])
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (vpnActive) {
                    VpnState.log("VPN: red perdida, reconectando ...")
                    scheduleReconnect()
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    /** Vuelca en el log el error de Go del arranque anterior (si murio con exit(1)). */
    private fun logPreviousGoError() {
        val f = File(filesDir, GO_ERROR_FILE)
        if (f.exists() && f.length() > 0L) {
            runCatching {
                f.readText().trim().lines().takeLast(20).forEach { VpnState.log("tun2socks(prev): $it") }
            }
            runCatching { f.delete() }
        }
    }

    // ------------------------------------------------------------- teardown

    /** Para el stack SOCKS manteniendo la interfaz TUN (para reconectar). */
    private fun shutdownStack() {
        monitorJob?.cancel()
        monitorJob = null
        runCatching { tun2socks?.stop() }
        tun2socks = null
        dns = null
        runCatching { ssh?.disconnect() }
        ssh = null
    }

    private fun stopVpn() {
        step("stopVpn")
        vpnActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        monitorJob?.cancel()
        monitorJob = null
        statsJob?.cancel()
        statsJob = null
        notifyJob?.cancel()
        notifyJob = null
        TrafficMeter.reset()
        ProfileStore.setVpnWasActive(this, false)
        shutdownStack()
        runCatching { tunFd?.close() }
        tunFd = null
        networkCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        VpnState.set(VpnUiState.Off)
        VpnState.log("VPN: desconectado.")
    }

    private fun notifyActive() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("Activo - salida por ${creds?.host ?: "-"}"))
    }

    // ---------------------------------------------------------- notificacion

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

    private fun backoff(attempt: Int): Long {
        val steps = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000, 60_000)
        return steps[minOf(attempt - 1, steps.size - 1)]
    }

    companion object {
        const val ACTION_STOP = "com.proxianon.app.vpn.STOP"
        private const val CHANNEL_ID = "proxianon_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        private const val MONITOR_INTERVAL_MS = 3_000L
        private const val STATS_PUBLISH_MS = 1_000L
        private const val NOTIFY_REFRESH_MS = 5_000L
        private const val GO_ERROR_FILE = "tun2socks_err.log"
    }
}