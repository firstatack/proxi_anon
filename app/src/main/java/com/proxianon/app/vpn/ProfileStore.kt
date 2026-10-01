package com.proxianon.app.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Almacen cifrado del ultimo perfil SSH usado, para poder reanudar el VPN
 * automaticamente tras reinicio del proceso (START_STICKY) sin pedir datos.
 *
 * Las contraseñas se cifran con EncryptedSharedPreferences (AES256-GCM) y la
 * clave maestra vive en el Android Keystore (no exportable).
 */
object ProfileStore {

    private const val PREFS_NAME = "proxianon_session"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"
    private const val KEY_USER = "last_user"
    private const val KEY_PASS = "last_pass"
    private const val KEY_WAS_ACTIVE = "vpn_was_active"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.also { prefs = it }
        }

    fun saveLastSession(context: Context, c: SshCredentials) {
        prefs(context).edit()
            .putString(KEY_HOST, c.host)
            .putInt(KEY_PORT, c.port)
            .putString(KEY_USER, c.username)
            .putString(KEY_PASS, c.password)
            .apply()
    }

    fun loadLastSession(context: Context): SshCredentials? {
        val p = prefs(context)
        val host = p.getString(KEY_HOST, null) ?: return null
        val user = p.getString(KEY_USER, null) ?: return null
        val pass = p.getString(KEY_PASS, "") ?: ""
        return SshCredentials(host = host, port = p.getInt(KEY_PORT, 22), username = user, password = pass)
    }

    fun setVpnWasActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAS_ACTIVE, active).apply()
    }

    fun vpnWasActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAS_ACTIVE, false)
}