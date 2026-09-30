package com.proxianon.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proxianon.app.ssh.SshTunnel
import com.proxianon.app.ssh.TunnelStatus
import com.proxianon.app.vpn.SshCredentials
import com.proxianon.app.vpn.VpnSession
import com.proxianon.app.vpn.VpnState
import com.proxianon.app.vpn.VpnUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val mode: TunnelMode = TunnelMode.TCP_UDP,
    val status: TunnelStatus = TunnelStatus.Disconnected,
    val checking: Boolean = false,
    val exitInfo: String? = null,
    val log: List<String> = emptyList(),
)

class MainViewModel : ViewModel() {

    private val tunnel = SshTunnel()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Estado del modo VPN (lo gestiona TunnelVpnService). */
    val vpnState: StateFlow<VpnUiState> = VpnState.flow
    val vpnLog: StateFlow<List<String>> = VpnState.log

    /** Crea la peticion VPN a partir del formulario actual (los datos se copian, no referencias). */
    fun buildVpnRequest(): SshCredentials? {
        val s = _state.value
        if (s.host.isBlank() || s.username.isBlank()) return null
        return SshCredentials(
            host = s.host,
            port = s.port.toIntOrNull() ?: 22,
            username = s.username,
            password = s.password,
        )
    }

    /** Valida y deja la config de la sesion VPN lista para TunnelVpnService. */
    fun prepareVpnStart(): SshCredentials? = buildVpnRequest()?.also { VpnSession.request = it }

    fun onHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun onPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun onUsername(v: String) = _state.update { it.copy(username = v.trim()) }
    fun onPassword(v: String) = _state.update { it.copy(password = v) }
    fun onMode(v: TunnelMode) = _state.update { it.copy(mode = v) }

    private fun log(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(300)) }
    }

    /** Formatea la cadena completa de causas (ExceptionInInitializerError suele tener message null). */
    private fun describe(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message
            parts += if (msg.isNullOrBlank()) cur::class.java.simpleName else "${cur::class.java.simpleName}: $msg"
            cur = cur.cause
        }
        return parts.joinToString(" <- ")
    }

    fun connect() {
        val s = _state.value
        if (s.status is TunnelStatus.Connecting || s.status is TunnelStatus.Connected) return
        if (s.host.isBlank() || s.username.isBlank()) {
            log("Faltan host o usuario.")
            return
        }
        val port = s.port.toIntOrNull() ?: 22
        _state.update { it.copy(status = TunnelStatus.Connecting, exitInfo = null) }
        log("Conectando a ${s.host}:$port como ${s.username} ...")

        viewModelScope.launch {
            try {
                val socksPort = withContext(Dispatchers.IO) {
                    tunnel.connect(s.host, port, s.username, s.password)
                }
                log("Autenticado. SOCKS5 local en 127.0.0.1:$socksPort")
                _state.update { it.copy(status = TunnelStatus.Connected(socksPort)) }
                testExitInternal(socksPort)
            } catch (t: Throwable) {
                val msg = describe(t)
                log("Fallo: $msg")
                _state.update { it.copy(status = TunnelStatus.Error(msg)) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tunnel.disconnect() }
            log("Desconectado.")
            _state.update { it.copy(status = TunnelStatus.Disconnected, exitInfo = null) }
        }
    }

    fun testExit() {
        val st = _state.value.status
        if (st is TunnelStatus.Connected) testExitInternal(st.socksPort)
    }

    private fun testExitInternal(socksPort: Int) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        log("Probando la salida por el tunel ...")
        viewModelScope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    tunnel.httpGetThroughSocks("https://api.ipify.org?format=json", socksPort)
                }
                log("Salida: $body")
                _state.update { it.copy(checking = false, exitInfo = body) }
            } catch (t: Throwable) {
                val msg = describe(t)
                log("Prueba fallida: $msg")
                _state.update { it.copy(checking = false, exitInfo = "Error: $msg") }
            }
        }
    }

    override fun onCleared() {
        runCatching { tunnel.disconnect() }
        super.onCleared()
    }
}
