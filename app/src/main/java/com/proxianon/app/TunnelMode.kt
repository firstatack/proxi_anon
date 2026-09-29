package com.proxianon.app

/** Modos de tunel disponibles. */
enum class TunnelMode(val label: String, val description: String) {
    TCP("TCP", "Solo TCP. Rapido de montar, sin UDP ni DNS nativo."),
    TCP_UDP("TCP + UDP", "Recomendado. Tunel completo con DNS y UDP via udpgw.")
}
