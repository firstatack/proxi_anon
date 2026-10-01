package com.proxianon.app.ssh

import com.proxianon.app.vpn.KnownHostsStore
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.DynamicPortForwardingTracker
import org.apache.sshd.common.SshException
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.net.SshdSocketAddress
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/**
 * Envuelve Apache MINA SSHD: conecta por usuario/contrasena y expone un proxy
 * SOCKS5 local mediante "dynamic port forwarding" (equivalente a `ssh -D`).
 *
 * Las llamadas son bloqueantes: invocalas desde Dispatchers.IO.
 */
class SshTunnel {

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var socks: DynamicPortForwardingTracker? = null

    /** Cuando la host key no coincide con la guardada (posible MITM). */
    private var trustIssue: String? = null

    val isConnected: Boolean
        get() = session?.isOpen == true

    /**
     * Conecta por SSH y levanta el SOCKS5 local.
     *
     * @return puerto TCP local donde quedo escuchando el SOCKS5.
     */
    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String,
        connectTimeoutMs: Long = 20_000,
        authTimeoutMs: Long = 20_000,
    ): Int {
        disconnect()

        val c = SshClient.setUpDefaultClient().apply {
            // TOFU anti-MITM: la primera vez guardamos la huella del servidor;
            // despues la exigimos igual.
            serverKeyVerifier = ServerKeyVerifier { _, _, key -> verifyServerFingerprint(host, port, key) }
        }
        c.start()
        client = c

        try {
            val s = c.connect(username, host, port)
                .verify(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .session
            s.addPasswordIdentity(password)
            s.auth().verify(authTimeoutMs, TimeUnit.MILLISECONDS)
            session = s

            // Puerto 0 -> el sistema operativo elige uno libre.
            val tracker = s.createDynamicPortForwardingTracker(SshdSocketAddress("127.0.0.1", 0))
            socks = tracker
            return tracker.boundAddress.port
        } catch (e: Exception) {
            // Si fallo por la host key, damos un mensaje claro en vez del generico.
            trustIssue?.let { throw SshException(it, e) }
            throw e
        }
    }

    /** Comprueba/guarda la host key (TOFU). Devuelve false si cambio la huella. */
    private fun verifyServerFingerprint(host: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = KeyUtils.getFingerPrint(key)
        val known = KnownHostsStore.fingerprintOf("$host:$port")
        return when {
            known == null -> {
                KnownHostsStore.save("$host:$port", fingerprint)
                trustIssue = null
                true
            }
            known == fingerprint -> {
                trustIssue = null
                true
            }
            else -> {
                trustIssue = "posible MITM: la huella SSH de $host:$port cambio"
                false
            }
        }
    }

    fun disconnect() {
        runCatching { socks?.close() }
        runCatching { session?.close(true) }
        runCatching { client?.stop() }
        socks = null
        session = null
        client = null
    }

    /** GET a [url] a traves del SOCKS5 local; sirve para verificar la IP de salida. */
    fun httpGetThroughSocks(url: String, socksPort: Int, timeoutMs: Int = 20_000): String {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ProxiAnon/0.2")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
