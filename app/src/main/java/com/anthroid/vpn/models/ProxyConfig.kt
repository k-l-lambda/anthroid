package com.anthroid.vpn.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents the overall proxy configuration including:
 * - List of proxy servers
 * - Currently active proxy server ID
 * - Global app list for proxy routing
 */
data class ProxyConfig(
    val servers: List<ProxyServer>,
    val activeServerId: String?,
    val globalAppList: List<String>
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                servers.forEach { put(it.toJson()) }
            })
            put("activeServerId", activeServerId ?: JSONObject.NULL)
            put("globalAppList", JSONArray().apply {
                globalAppList.forEach { put(it) }
            })
        }
    }

    fun getActiveServer(): ProxyServer? {
        return servers.find { it.id == activeServerId && it.enabled }
    }

    fun getEnabledServers(): List<ProxyServer> {
        return servers.filter { it.enabled }
    }

    companion object {
        fun fromJson(json: JSONObject): ProxyConfig {
            val serversArray = json.optJSONArray("servers") ?: JSONArray()
            val servers = mutableListOf<ProxyServer>()
            for (i in 0 until serversArray.length()) {
                servers.add(ProxyServer.fromJson(serversArray.getJSONObject(i)))
            }

            val activeServerId = if (json.isNull("activeServerId")) {
                null
            } else {
                json.optString("activeServerId", "").takeIf { it.isNotEmpty() }
            }

            val appListArray = json.optJSONArray("globalAppList") ?: JSONArray()
            val appList = mutableListOf<String>()
            for (i in 0 until appListArray.length()) {
                appList.add(appListArray.getString(i))
            }

            return ProxyConfig(
                servers = servers,
                activeServerId = activeServerId,
                globalAppList = appList
            )
        }

        fun empty(): ProxyConfig {
            return ProxyConfig(
                servers = emptyList(),
                activeServerId = null,
                globalAppList = emptyList()
            )
        }
    }
}
