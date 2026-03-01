package com.anthroid.gateway

import android.content.Context
import android.content.SharedPreferences

class DeviceAuthStore(context: Context) {
  private val prefs: SharedPreferences =
    context.getSharedPreferences("gateway_auth", Context.MODE_PRIVATE)

  fun loadToken(deviceId: String, role: String): String? {
    val key = tokenKey(deviceId, role)
    return prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
  }

  fun saveToken(deviceId: String, role: String, token: String) {
    val key = tokenKey(deviceId, role)
    prefs.edit().putString(key, token.trim()).apply()
  }

  fun clearToken(deviceId: String, role: String) {
    val key = tokenKey(deviceId, role)
    prefs.edit().remove(key).apply()
  }

  private fun tokenKey(deviceId: String, role: String): String {
    val normalizedDevice = deviceId.trim().lowercase()
    val normalizedRole = role.trim().lowercase()
    return "gateway.deviceToken.$normalizedDevice.$normalizedRole"
  }
}
