package com.anthroid.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Last successful tmux discovery, isolated by the exact SSH host alias. */
class RemoteSessionCache(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("remote_tmux_sessions", Context.MODE_PRIVATE)

    fun load(hostname: String): List<RemoteSessionInfo>? {
        val raw = preferences.getString(hostname, null) ?: return null
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RemoteSessionInfo(
                    sessionKey = item.getString("sessionKey"),
                    displayName = item.optString("displayName").takeIf { it.isNotEmpty() },
                    lastActivity = item.getLong("lastActivity"),
                    status = item.getString("status"),
                    source = RemoteSessionInfo.Source.SSH_TMUX,
                    hostname = hostname,
                )
            }
        } catch (_: Exception) {
            null // An old or malformed cache must not prevent live discovery.
        }
    }

    fun save(hostname: String, sessions: List<RemoteSessionInfo>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject().apply {
                put("sessionKey", session.sessionKey)
                put("displayName", session.displayName ?: "")
                put("lastActivity", session.lastActivity)
                put("status", session.status)
            })
        }
        preferences.edit().putString(hostname, array.toString()).apply()
    }
}
