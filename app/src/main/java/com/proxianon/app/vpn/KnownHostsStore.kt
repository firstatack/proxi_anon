package com.proxianon.app.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Almacen de huellas (fingerprints) de host keys SSH por servidor (TOFU).
 * En la primera conexion a un servidor se guarda su huella; despues se compara
 * y si cambia la conexion se rechaza (proteccion MITM).
 */
object KnownHostsStore {

    private const val PREFS = "proxianon_known_hosts"
    private const val KEY_JSON = "hosts"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Llamar una vez desde Application.onCreate (la app ya tiene contexto). */
    fun init(context: Context) {
        if (prefs != null) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun fingerprintOf(hostKey: String): String? {
        val p = prefs ?: return null
        val raw = p.getString(KEY_JSON, null) ?: return null
        return runCatching {
            JSONObject(raw).optString(hostKey, "").ifEmpty { null }
        }.getOrNull()
    }

    fun save(hostKey: String, fingerprint: String) {
        val p = prefs ?: return
        val raw = p.getString(KEY_JSON, null)
        val obj = if (raw == null) JSONObject()
        else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        obj.put(hostKey, fingerprint)
        p.edit().putString(KEY_JSON, obj.toString()).apply()
    }
}