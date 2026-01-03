package com.anthroid.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anthroid.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "ProxyVpnService"
        private const val CHANNEL_ID = "anthroid_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.anthroid.vpn.START"
        const val ACTION_STOP = "com.anthroid.vpn.STOP"

        const val EXTRA_PROXY_HOST = "proxy_host"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_PROXY_TYPE = "proxy_type"
        const val EXTRA_TARGET_APPS = "target_apps"

        const val PROXY_TYPE_SOCKS5 = "SOCKS5"
        const val PROXY_TYPE_HTTP = "HTTP"

        @Volatile
        private var instance: ProxyVpnService? = null

        @Volatile
        private var currentTargetApps: List<String> = emptyList()

        @Volatile
        private var currentProxyHost: String = ""

        @Volatile
        private var currentProxyPort: Int = 0

        @Volatile
        private var currentProxyType: String = ""

        fun isRunning(): Boolean = instance != null
        fun getTargetApps(): List<String> = currentTargetApps
        fun getProxyInfo(): String {
            return if (isRunning()) {
                "$currentProxyType proxy at $currentProxyHost:$currentProxyPort for ${currentTargetApps.joinToString(", ")}"
            } else {
                "VPN not running"
            }
        }
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var proxyHost: String = "localhost"
    private var proxyPort: Int = 1091
    private var proxyType: String = PROXY_TYPE_SOCKS5
    private var targetApps: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "ProxyVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST) ?: "localhost"
                proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 1091)
                proxyType = intent.getStringExtra(EXTRA_PROXY_TYPE) ?: PROXY_TYPE_SOCKS5
                targetApps = intent.getStringArrayListExtra(EXTRA_TARGET_APPS) ?: arrayListOf()
                currentProxyHost = proxyHost
                currentProxyPort = proxyPort
                currentProxyType = proxyType
                currentTargetApps = targetApps
                Log.i(TAG, "Starting VPN: $proxyType proxy at $proxyHost:$proxyPort")
                startVpn()
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping VPN")
                stopVpn()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        instance = null
        currentTargetApps = emptyList()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        if (isRunning.get()) {
            stopVpn()
        }
        if (targetApps.isEmpty()) {
            Log.e(TAG, "No target apps specified")
            stopSelf()
            return
        }
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)

            vpnInterface = establishVpn()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            val configFile = createTProxyConfig()
            if (configFile == null) {
                Log.e(TAG, "Failed to create config")
                stopSelf()
                return
            }

            val fd = vpnInterface!!.fd
            Log.i(TAG, "Starting tun2socks fd=$fd")
            hev.sockstun.TProxyService.TProxyStartService(configFile.absolutePath, fd)
            isRunning.set(true)
            Log.i(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (isRunning.get()) {
            try {
                hev.sockstun.TProxyService.TProxyStopService()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping", e)
            }
        }
        isRunning.set(false)
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Anthroid VPN")
                .setBlocking(false)
                .setMtu(8500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")

            var addedApps = 0
            for (pkg in targetApps) {
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    builder.addAllowedApplication(pkg)
                    addedApps++
                    Log.i(TAG, "Added: $pkg")
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Not found: $pkg")
                }
            }

            if (addedApps == 0) {
                Log.e(TAG, "No valid apps")
                return null
            }
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed establish", e)
            null
        }
    }

    private fun createTProxyConfig(): File? {
        return try {
            val configFile = File(cacheDir, "tproxy.conf")
            val config = """
misc:
  task-stack-size: 81920

tunnel:
  mtu: 8500
  ipv4: 10.0.0.2

socks5:
  port: $proxyPort
  address: '$proxyHost'
  udp: 'udp'
""".trimIndent()
            FileOutputStream(configFile).use { it.write(config.toByteArray()) }
            Log.i(TAG, "Config created")
            configFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed create config", e)
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Anthroid VPN", NotificationManager.IMPORTANCE_LOW)
        channel.description = "VPN proxy"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ProxyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val appNames = targetApps.mapNotNull { try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it } }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Proxy Active")
            .setContentText("${appNames.joinToString(", ")} -> $proxyType $proxyHost:$proxyPort")
            .setSmallIcon(R.drawable.ic_foreground)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }
}
