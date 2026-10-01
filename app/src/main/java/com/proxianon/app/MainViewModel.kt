package com.proxianon.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.proxianon.app.ssh.SshTunnel
import com.proxianon.app.ssh.TunnelStatus
import com.proxianon.app.vpn.ProfileStore
import com.proxianon.app.vpn.SshCredentials
import com.proxianon.app.vpn.SshProfile
import com.proxianon.app.vpn.SshProfileStore
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application
    private val tunnel = SshTunnel()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Estado del modo VPN (lo gestiona TunnelVpnService). */
    val vpnState: StateFlow<VpnUiState> = VpnState.flow
    val vpnLog: StateFlow<List<String>> = VpnState.log

    // --------------------------------------------------------------- perfiles

    private val _profiles = MutableStateFlow(SshProfileStore.list(ctx))
    val profiles: StateFlow<List<SshProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(SshProfileStore.activeId(ctx))
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    init {
        // El perfil activo rellena el formulario al abrir la app.
        SshProfileStore.activeProfile(ctx)?.let { fillForm(it) }
    }

    fun saveProfile(profile: SshProfile) {
        SshProfileStore.save(ctx, profile)
        _profiles.value = SshProfileStore.list(ctx)
    }

    fun deleteProfile(id: String) {
        SshProfileStore.delete(ctx, id)
        _profiles.value = SshProfileStore.list(ctx)
        _activeProfileId.value = SshProfileStore.activeId(ctx)
    }

    /** Marca el perfil como activo y vuelca sus datos en el formulario. */
    fun activateProfile(id: String) {
        val p = SshProfileStore.get(ctx, id) ?: return
        SshProfileStore.setActive(ctx, id)
        _activeProfileId.value = id
        fillForm(p)
    }

    private fun fillForm(p: SshProfile) {
        _state.update {
            it.copy(
                host = p.host,
                port = p.port.toString(),
                username = p.username,
                password = p.password,
            )
        }
        // Mantiene el auto-resume del VPN con estas credenciales.
        ProfileStore.saveLastSession(ctx, SshCredentials(p.host, p.port, p.username, p.password))
    }

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
