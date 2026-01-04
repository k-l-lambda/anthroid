package com.anthroid.vpn.models

import org.json.JSONObject

/**
 * Represents a proxy server configuration.
 */
data class ProxyServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: ProxyType,
    val username: String = "",
    val password: String = "",
    val enabled: Boolean = true
) {
    enum class ProxyType {
        SOCKS5,
        HTTP;

        companion object {
            fun fromString(value: String): ProxyType {
                return when (value.uppercase()) {
                    "HTTP" -> HTTP
                    else -> SOCKS5
                }
            }
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("host", host)
            put("port", port)
            put("type", type.name)
            put("username", username)
            put("password", password)
            put("enabled", enabled)
        }
    }

    fun getDisplayInfo(): String {
        val authInfo = if (username.isNotEmpty()) " (auth)" else ""
        return "$host:$port (${type.name})$authInfo"
    }

    companion object {
        fun fromJson(json: JSONObject): ProxyServer {
            return ProxyServer(
                id = json.getString("id"),
                name = json.getString("name"),
                host = json.getString("host"),
                port = json.getInt("port"),
                type = ProxyType.fromString(json.optString("type", "SOCKS5")),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                enabled = json.optBoolean("enabled", true)
            )
        }
    }
}
