package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SshConnectionInfo(
    val id: String = kotlin.random.Random.nextLong().toString(36),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
    }

    companion object {
        fun fromJson(obj: JSONObject): SshConnectionInfo = SshConnectionInfo(
            id = obj.optString("id", kotlin.random.Random.nextLong().toString(36)),
            name = obj.optString("name", ""),
            host = obj.optString("host", ""),
            port = obj.optInt("port", 22),
            username = obj.optString("username", ""),
            password = obj.optString("password", ""),
        )
    }
}

object SshConnectionStore {
    private const val PREFS_NAME = "ssh_connections"
    private const val KEY_CONNECTIONS = "connections_json"

    fun getAll(context: Context): List<SshConnectionInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> SshConnectionInfo.fromJson(arr.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, connections: List<SshConnectionInfo>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray().apply {
            connections.forEach { put(it.toJson()) }
        }
        prefs.edit().putString(KEY_CONNECTIONS, arr.toString()).apply()
    }

    fun add(context: Context, info: SshConnectionInfo) {
        val list = getAll(context).toMutableList()
        list.add(0, info)
        save(context, list)
    }

    fun update(context: Context, info: SshConnectionInfo) {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == info.id }
        if (idx >= 0) {
            list[idx] = info
            save(context, list)
        }
    }

    fun delete(context: Context, id: String) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.id == id }
        save(context, list)
    }
}
