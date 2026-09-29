package com.proxianon.app.ssh

/** Estado del tunel SSH. */
sealed interface TunnelStatus {
    data object Disconnected : TunnelStatus
    data object Connecting : TunnelStatus

    /** Autenticado y con SOCKS5 escuchando en [socksPort]. */
    data class Connected(val socksPort: Int) : TunnelStatus

    data class Error(val message: String) : TunnelStatus
}
