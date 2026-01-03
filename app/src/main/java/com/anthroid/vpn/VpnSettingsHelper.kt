package com.anthroid.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.preference.PreferenceManager

/**
 * Helper class to manage VPN proxy settings and service lifecycle.
 * The VPN proxy is controlled by Claude agent tools (set_app_proxy, stop_app_proxy).
 * Settings UI is used to grant VPN permission and configure default proxy settings.
 */
object VpnSettingsHelper {
    private const val TAG = "VpnSettingsHelper"

    const val PREF_VPN_PERMISSION_GRANTED = "vpn_permission_granted"
    const val PREF_PROXY_HOST = "vpn_proxy_host"
    const val PREF_PROXY_PORT = "vpn_proxy_port"
    const val PREF_PROXY_TYPE = "vpn_proxy_type"

    /**
     * Check if VPN permission has been granted.
     */
    fun isVpnPermissionGranted(context: Context): Boolean {
        return ProxyVpnService.prepare(context) == null
    }

    /**
     * Get proxy host from preferences (default settings).
     */
    fun getProxyHost(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_PROXY_HOST, "localhost") ?: "localhost"
    }

    /**
     * Get proxy port from preferences (default settings).
     */
    fun getProxyPort(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val portStr = prefs.getString(PREF_PROXY_PORT, "1091") ?: "1091"
        return portStr.toIntOrNull() ?: 1091
    }

    /**
     * Get proxy type from preferences (default settings).
     */
    fun getProxyType(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_PROXY_TYPE, ProxyVpnService.PROXY_TYPE_SOCKS5)
            ?: ProxyVpnService.PROXY_TYPE_SOCKS5
    }

    /**
     * Start VPN service for specified apps.
     * Returns false if VPN permission is required.
     */
    fun startVpn(context: Context, targetApps: List<String>): Boolean {
        // Check VPN permission
        val prepareIntent = ProxyVpnService.prepare(context)
        if (prepareIntent != null) {
            Log.w(TAG, "VPN permission required")
            return false
        }

        if (targetApps.isEmpty()) {
            Log.w(TAG, "No target apps specified")
            return false
        }

        // Start VPN service
        val intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_START
            putExtra(ProxyVpnService.EXTRA_PROXY_HOST, getProxyHost(context))
            putExtra(ProxyVpnService.EXTRA_PROXY_PORT, getProxyPort(context))
            putExtra(ProxyVpnService.EXTRA_PROXY_TYPE, getProxyType(context))
            putStringArrayListExtra(ProxyVpnService.EXTRA_TARGET_APPS, ArrayList(targetApps))
        }
        context.startService(intent)

        Log.i(TAG, "VPN service started for apps: ${targetApps.joinToString(", ")}")
        return true
    }

    /**
     * Stop VPN service.
     */
    fun stopVpn(context: Context) {
        val intent = Intent(context, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_STOP
        }
        context.startService(intent)
        Log.i(TAG, "VPN service stopped")
    }

    /**
     * Request VPN permission using activity result launcher.
     */
    fun requestVpnPermission(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        val prepareIntent = ProxyVpnService.prepare(activity)
        if (prepareIntent != null) {
            launcher.launch(prepareIntent)
        }
    }

    /**
     * Get current VPN status info.
     */
    fun getStatusInfo(): String {
        return ProxyVpnService.getProxyInfo()
    }

    /**
     * Check if VPN is currently running.
     */
    fun isRunning(): Boolean {
        return ProxyVpnService.isRunning()
    }

    /**
     * Get list of apps currently using proxy.
     */
    fun getTargetApps(): List<String> {
        return ProxyVpnService.getTargetApps()
    }
}
