package com.proxianon.app.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Perfil SSH guardado (la contrasena viaja cifrada en reposo). */
data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
)

/**
 * CRUD cifrado de perfiles SSH. La lista completa se guarda como JSON dentro de
 * una clave de EncryptedSharedPreferences (AES256-GCM, clave maestra en Keystore).
 */
object SshProfileStore {

    private const val PREFS = "proxianon_profiles"
    private const val KEY_LIST = "profiles_json"
    private const val KEY_ACTIVE = "active_profile_id"

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
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.also { prefs = it }
        }

    fun list(context: Context): List<SshProfile> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> parse(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String): SshProfile? = list(context).firstOrNull { it.id == id }

    fun activeId(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun activeProfile(context: Context): SshProfile? = activeId(context)?.let { get(context, it) }

    /** Alta o actualizacion (upsert por [SshProfile.id]). */
    fun save(context: Context, profile: SshProfile) {
        val list = list(context).toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persist(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = list(context).filterNot { it.id == id }
        persist(context, list)
        if (activeId(context) == id) setActive(context, null)
    }

    fun setActive(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    private fun persist(context: Context, list: List<SshProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun toJson(p: SshProfile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("host", p.host)
        put("port", p.port)
        put("username", p.username)
        put("password", p.password)
    }

    private fun parse(o: JSONObject) = SshProfile(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name"),
        host = o.optString("host"),
        port = o.optInt("port", 22),
        username = o.optString("username"),
        password = o.optString("password"),
    )
}