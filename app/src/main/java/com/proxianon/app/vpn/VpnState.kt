package com.proxianon.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credenciales SSH para el modo VPN (copia de lo que hay en el formulario). */
data class SshCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

/** Estado del modo VPN visible en la UI. */
sealed interface VpnUiState {
    data object Off : VpnUiState
    data object Starting : VpnUiState
    data object On : VpnUiState
    data class Reconnecting(val attempt: Int) : VpnUiState
    data class Error(val message: String) : VpnUiState
}

/** Configuracion de la sesion VPN en curso (la lee TunnelVpnService al arrancar). */
object VpnSession {
    var request: SshCredentials? = null
}

/** Estado observable del modo VPN compartido entre el servicio y la UI. */
object VpnState {

    private val _flow = MutableStateFlow<VpnUiState>(VpnUiState.Off)
    val flow: StateFlow<VpnUiState> = _flow.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun set(state: VpnUiState) {
        _flow.value = state
    }

    fun log(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }

    fun reset() {
        _flow.value = VpnUiState.Off
        _log.value = emptyList()
    }
}