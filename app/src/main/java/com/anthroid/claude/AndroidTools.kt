package com.anthroid.claude

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anthroid.R
import com.anthroid.accessibility.AnthroidAccessibilityService
import com.anthroid.vpn.ProxyConfigManager
import com.anthroid.vpn.ProxyVpnService
import com.anthroid.vpn.models.ProxyServer
import tun.proxy.service.Tun2HttpVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

class AndroidTools(private val context: Context) {
    companion object {
        private const val TAG = "AndroidTools"
        private const val CHANNEL_ID = "claude_agent_v2"
    }

    init { createNotificationChannel() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Claude Agent", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Notifications from Claude Agent"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    suspend fun executeTool(name: String, input: String): String = try {
        Log.i(TAG, "executeTool called: name=$name, input=$input")
        when (name.lowercase()) {
            "open_url" -> openUrl(input)
            "launch_app" -> launchApp(input)
            "send_intent" -> sendIntent(input)
            "list_apps" -> listApps(input)
            "get_app_info" -> getAppInfo(input)
            "show_notification" -> showNotification(input)
            "geocode" -> geocode(input)
            "reverse_geocode" -> reverseGeocode(input)
            "get_location" -> getLocation(input)
            "query_calendar" -> queryCalendar(input)
            "add_calendar_event" -> addCalendarEvent(input)
            "query_media" -> queryMedia(input)
            "read_clipboard" -> readClipboard()
            "write_clipboard" -> writeClipboard(input)
            "set_app_proxy" -> setAppProxy(input)
            "stop_app_proxy" -> stopAppProxy()
            "get_proxy_status" -> getProxyStatus()
            // Screen automation tools
            "get_screen_text" -> getScreenText()
            "get_screen_elements" -> getScreenElements(input)
            "find_element" -> findElement(input)
            "click_element" -> clickElement(input)
            "click_position" -> clickPosition(input)
            "input_text" -> inputText(input)
            "swipe" -> swipe(input)
            "long_press" -> longPress(input)
            "press_back" -> pressBack()
            "press_home" -> pressHome()
            "open_recents" -> openRecents()
            "open_notifications" -> openNotifications()
            "scroll" -> scroll(input)
            "get_accessibility_status" -> getAccessibilityStatus()
            "wait_for_element" -> waitForElement(input)
            "focus_and_input" -> focusAndInput(input)
            "get_current_app" -> getCurrentApp()
            else -> "Unknown tool: $name"
        }
    } catch (e: Exception) {
        Log.e(TAG, "Tool failed: $name", e)
        "Error: " + e.message
    }

    fun isAndroidTool(name: String) = name.lowercase() in listOf(
        "open_url", "launch_app", "send_intent", "list_apps", "get_app_info",
        "show_notification", "geocode", "reverse_geocode", "get_location",
        "query_calendar", "add_calendar_event", "query_media",
        "read_clipboard", "write_clipboard",
        "set_app_proxy", "stop_app_proxy", "get_proxy_status",
        // Screen automation tools
        "get_screen_text", "get_screen_elements", "find_element",
        "click_element", "click_position", "input_text", "swipe",
        "long_press", "press_back", "press_home", "open_recents",
        "open_notifications", "scroll", "get_accessibility_status",
        "wait_for_element", "focus_and_input", "get_current_app"
    )

    private fun openUrl(input: String): String {
        val url = JSONObject(input).optString("url", "")
        if (url.isEmpty()) return "Error: url is required"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened: $url"
    }

    private fun launchApp(input: String): String {
        val pkg = JSONObject(input).optString("package", "")
        if (pkg.isEmpty()) return "Error: package is required"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "Error: App not found"
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Launched: $pkg"
    }

    private fun sendIntent(input: String): String {
        val json = JSONObject(input)
        val action = json.optString("action", "")
        if (action.isEmpty()) return "Error: action is required"
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        json.optString("data", "").takeIf { it.isNotEmpty() }?.let { intent.data = Uri.parse(it) }
        json.optString("type", "").takeIf { it.isNotEmpty() }?.let { intent.type = it }
        context.startActivity(intent)
        return "Intent sent: $action"
    }

    private fun listApps(input: String): String {
        val json = JSONObject(input)
        val filter = json.optString("filter", "user")
        val limit = json.optInt("limit", 50)
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val filtered = when (filter) {
            "system" -> apps.filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            "user" -> apps.filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            else -> apps
        }
        val result = JSONArray()
        filtered.take(limit).forEach { app ->
            result.put(JSONObject().put("package", app.packageName).put("name", pm.getApplicationLabel(app).toString()))
        }
        return result.toString(2)
    }

    private fun getAppInfo(input: String): String {
        val pkg = JSONObject(input).optString("package", "")
        if (pkg.isEmpty()) return "Error: package is required"
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val pkgInfo = pm.getPackageInfo(pkg, 0)
            JSONObject().put("package", pkg).put("name", pm.getApplicationLabel(info).toString())
                .put("versionName", pkgInfo.versionName).put("enabled", info.enabled).toString(2)
        } catch (e: PackageManager.NameNotFoundException) { "Error: Package not found" }
    }

    private fun showNotification(input: String): String {
        Log.i(TAG, "showNotification called with: $input")
        val json = JSONObject(input)
        val title = json.optString("title", "Claude Agent")
        val message = json.optString("message", "")
        if (message.isEmpty()) return "Error: message is required"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "Error: POST_NOTIFICATIONS permission not granted"
        }
        val id = json.optInt("id", System.currentTimeMillis().toInt())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        Log.i(TAG, "Calling NotificationManagerCompat.notify with id=$id, title=$title, message=$message")
        NotificationManagerCompat.from(context).notify(id, notification)
        Log.i(TAG, "Notification posted successfully")
        return "Notification shown: $id"
    }

    private suspend fun geocode(input: String): String = withContext(Dispatchers.IO) {
        val address = JSONObject(input).optString("address", "")
        if (address.isEmpty()) return@withContext "Error: address is required"
        if (!Geocoder.isPresent()) return@withContext "Error: Geocoder not available"
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont -> geocoder.getFromLocationName(address, 5) { cont.resume(it) } }
            } else { @Suppress("DEPRECATION") geocoder.getFromLocationName(address, 5) }
            if (addresses.isNullOrEmpty()) return@withContext "No results for: $address"
            val result = JSONArray()
            addresses.forEach { result.put(JSONObject().put("lat", it.latitude).put("lng", it.longitude).put("name", it.featureName ?: "")) }
            result.toString(2)
        } catch (e: Exception) { "Error: " + e.message }
    }

    private suspend fun reverseGeocode(input: String): String = withContext(Dispatchers.IO) {
        val json = JSONObject(input)
        val lat = json.optDouble("latitude", Double.NaN)
        val lng = json.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return@withContext "Error: latitude and longitude required"
        if (!Geocoder.isPresent()) return@withContext "Error: Geocoder not available"
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont -> geocoder.getFromLocation(lat, lng, 5) { cont.resume(it) } }
            } else { @Suppress("DEPRECATION") geocoder.getFromLocation(lat, lng, 5) }
            if (addresses.isNullOrEmpty()) return@withContext "No address found"
            JSONObject().put("address", addresses[0].getAddressLine(0) ?: "").toString(2)
        } catch (e: Exception) { "Error: " + e.message }
    }

    private suspend fun getLocation(input: String): String = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext "Error: Location permission not granted"
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providerName = JSONObject(input).optString("provider", "network")
        val provider = if (providerName == "gps") LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        if (!lm.isProviderEnabled(provider)) return@withContext "Error: Provider $provider not enabled"
        try {
            val loc = lm.getLastKnownLocation(provider) ?: return@withContext "Error: No location available"
            JSONObject().put("lat", loc.latitude).put("lng", loc.longitude).put("accuracy", loc.accuracy).toString(2)
        } catch (e: SecurityException) { "Error: Permission denied" }
    }

    private fun queryCalendar(input: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Error: READ_CALENDAR permission not granted"
        }
        val json = JSONObject(input)
        val days = json.optInt("days_ahead", 7)
        val limit = json.optInt("limit", 20)
        val now = System.currentTimeMillis()
        val end = now + days * 86400000L
        val result = JSONArray()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
            CalendarContract.Events.DTSTART + " BETWEEN ? AND ?",
            arrayOf(now.toString(), end.toString()),
            CalendarContract.Events.DTSTART + " ASC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                result.put(JSONObject().put("id", cursor.getLong(0)).put("title", cursor.getString(1) ?: "").put("start", cursor.getLong(2)))
                count++
            }
        }
        return if (result.length() > 0) result.toString(2) else "No events in next $days days"
    }

    private fun addCalendarEvent(input: String): String {
        val json = JSONObject(input)
        val title = json.optString("title", "")
        if (title.isEmpty()) return "Error: title is required"
        val start = json.optLong("start_time", 0)
        val end = json.optLong("end_time", 0)
        if (start == 0L || end == 0L) return "Error: start_time and end_time required"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Error: WRITE_CALENDAR permission not granted"
        }
        context.startActivity(Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "Calendar dialog opened: $title"
    }

    private fun queryMedia(input: String): String {
        val json = JSONObject(input)
        val mediaType = json.optString("type", "images")
        val limit = json.optInt("limit", 20)
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (mediaType == "videos") Manifest.permission.READ_MEDIA_VIDEO
            else if (mediaType == "audio") Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_MEDIA_IMAGES
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            return "Error: Media permission not granted"
        }
        val uri = if (mediaType == "videos") MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else if (mediaType == "audio") MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val result = JSONArray()
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                result.put(JSONObject().put("id", cursor.getLong(0)).put("name", cursor.getString(1) ?: "").put("size", cursor.getLong(2)))
                count++
            }
        }
        return if (result.length() > 0) result.toString(2) else "No $mediaType found"
    }

    private fun readClipboard(): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            return if (text.isNullOrEmpty()) "Clipboard is empty" else text
        }
        return "Clipboard is empty"
    }

    private fun writeClipboard(input: String): String {
        val json = JSONObject(input)
        val text = json.optString("text", "")
        if (text.isEmpty()) return "Error: text is required"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Claude", text)
        clipboard.setPrimaryClip(clip)
        return "Text copied to clipboard (${text.length} characters)"
    }

    /**
     * Set up VPN proxy for specified apps.
     * Input: {"apps": ["com.example.app1"], "proxy_host": "10.121.196.2", "proxy_port": 1091, "proxy_type": "HTTP", "proxy_user": "user", "proxy_pass": "pass"}
     *
     * Note: VPN permission must be granted first via Settings > VPN Proxy.
     * If apps is not specified or empty, uses the global app list from Proxy Settings.
     * The proxy server will be saved to settings if not already present.
     * proxy_type: "SOCKS5" or "HTTP" (default SOCKS5)
     * proxy_user/proxy_pass: optional credentials for authenticated proxies
     */
    private suspend fun setAppProxy(input: String): String {
        val json = JSONObject(input)
        val appsArray = json.optJSONArray("apps")

        val configManager = ProxyConfigManager.getInstance(context)
        var config = configManager.loadConfig()

        val apps = ArrayList<String>()
        if (appsArray != null && appsArray.length() > 0) {
            for (i in 0 until appsArray.length()) {
                apps.add(appsArray.getString(i))
            }
        } else {
            // Use global app list from ProxyConfigManager
            if (config.globalAppList.isEmpty()) {
                return "Error: No apps specified and global app list is empty. Configure apps in Settings > VPN Proxy > Manage Proxy Servers."
            }
            apps.addAll(config.globalAppList)
        }

        val proxyHost = json.optString("proxy_host", "localhost")
        val proxyPort = json.optInt("proxy_port", 1091)
        val proxyType = json.optString("proxy_type", "SOCKS5").uppercase()
        val proxyUser = json.optString("proxy_user", "")
        val proxyPass = json.optString("proxy_pass", "")

        // Check VPN permission
        val prepareIntent = ProxyVpnService.prepare(context)
        if (prepareIntent != null) {
            return "Error: VPN permission not granted. User needs to enable VPN in Settings > VPN Proxy first."
        }

        // Save proxy server to config if not already present
        val serverType = if (proxyType == "HTTP") ProxyServer.ProxyType.HTTP else ProxyServer.ProxyType.SOCKS5
        var existingServer = config.servers.find { it.host == proxyHost && it.port == proxyPort && it.type == serverType }

        if (existingServer == null) {
            // Add new server
            val newServer = ProxyServer(
                id = configManager.generateServerId(),
                name = "$proxyHost:$proxyPort",
                host = proxyHost,
                port = proxyPort,
                type = serverType,
                username = proxyUser,
                password = proxyPass,
                enabled = true
            )
            config = configManager.addServer(newServer)
            existingServer = newServer
            Log.i(TAG, "Added new proxy server: ${newServer.name}")
        } else if (proxyUser.isNotEmpty() && existingServer.username != proxyUser) {
            // Update credentials if changed
            val updatedServer = existingServer.copy(username = proxyUser, password = proxyPass)
            config = configManager.updateServer(updatedServer)
            existingServer = updatedServer
        }

        // Set as active server
        configManager.setActiveServer(existingServer.id)

        // Use appropriate service based on proxy type
        if (proxyType == "HTTP") {
            // Use Tun2HttpVpnService for HTTP proxy
            val intent = Intent(context, Tun2HttpVpnService::class.java).apply {
                action = Tun2HttpVpnService.ACTION_START
                putExtra(Tun2HttpVpnService.EXTRA_PROXY_HOST, proxyHost)
                putExtra(Tun2HttpVpnService.EXTRA_PROXY_PORT, proxyPort)
                putExtra(Tun2HttpVpnService.EXTRA_PROXY_USER, proxyUser)
                putExtra(Tun2HttpVpnService.EXTRA_PROXY_PASS, proxyPass)
                putStringArrayListExtra(Tun2HttpVpnService.EXTRA_TARGET_APPS, apps)
            }
            context.startService(intent)
        } else {
            // Use ProxyVpnService for SOCKS5 proxy
            val intent = Intent(context, ProxyVpnService::class.java).apply {
                action = ProxyVpnService.ACTION_START
                putExtra(ProxyVpnService.EXTRA_PROXY_HOST, proxyHost)
                putExtra(ProxyVpnService.EXTRA_PROXY_PORT, proxyPort)
                putExtra(ProxyVpnService.EXTRA_PROXY_TYPE, proxyType)
                putStringArrayListExtra(ProxyVpnService.EXTRA_TARGET_APPS, apps)
            }
            context.startService(intent)
        }

        val authInfo = if (proxyUser.isNotEmpty()) " (with auth)" else ""
        return "VPN proxy started: ${apps.joinToString(", ")} -> $proxyType $proxyHost:$proxyPort$authInfo"
    }

    /**
     * Stop VPN proxy service.
     */
    private fun stopAppProxy(): String {
        // Stop both services (whichever is running)
        if (ProxyVpnService.isRunning()) {
            val intent = Intent(context, ProxyVpnService::class.java).apply {
                action = ProxyVpnService.ACTION_STOP
            }
            context.startService(intent)
            return "SOCKS5 VPN proxy stopped"
        }
        if (Tun2HttpVpnService.isRunning()) {
            val intent = Intent(context, Tun2HttpVpnService::class.java).apply {
                action = Tun2HttpVpnService.ACTION_STOP
            }
            context.startService(intent)
            return "HTTP VPN proxy stopped"
        }
        return "VPN proxy is not running"
    }

    /**
     * Get current VPN proxy status.
     */
    private fun getProxyStatus(): String {
        return if (ProxyVpnService.isRunning()) {
            val apps = ProxyVpnService.getTargetApps()
            JSONObject()
                .put("running", true)
                .put("type", "SOCKS5")
                .put("info", ProxyVpnService.getProxyInfo())
                .put("target_apps", JSONArray(apps))
                .toString(2)
        } else if (Tun2HttpVpnService.isRunning()) {
            val apps = Tun2HttpVpnService.getTargetApps()
            JSONObject()
                .put("running", true)
                .put("type", "HTTP")
                .put("info", Tun2HttpVpnService.getProxyInfo())
                .put("target_apps", JSONArray(apps))
                .toString(2)
        } else {
            JSONObject()
                .put("running", false)
                .put("info", "VPN proxy not running")
                .toString(2)
        }
    }

    // ==================== Screen Automation Tools ====================

    /**
     * Get all visible text on screen.
     * Input: {} (no parameters)
     * Requires: Accessibility service enabled
     */
    private fun getScreenText(): String {
        return AnthroidAccessibilityService.getScreenText()
    }

    /**
     * Get screen elements as structured JSON.
     * Input: {"include_invisible": false}
     * Returns element tree with text, description, bounds, clickable state
     */
    private fun getScreenElements(input: String): String {
        val json = JSONObject(input)
        val includeInvisible = json.optBoolean("include_invisible", false)
        return AnthroidAccessibilityService.getScreenElements(includeInvisible)
    }

    /**
     * Find element by text content.
     * Input: {"text": "Search", "exact_match": false}
     */
    private fun findElement(input: String): String {
        val json = JSONObject(input)
        val text = json.optString("text", "")
        if (text.isEmpty()) return "Error: text is required"
        val exactMatch = json.optBoolean("exact_match", false)
        return AnthroidAccessibilityService.findElementByText(text, exactMatch)
    }

    /**
     * Click element by text.
     * Input: {"text": "Button text"}
     */
    private fun clickElement(input: String): String {
        val json = JSONObject(input)
        val text = json.optString("text", "")
        if (text.isEmpty()) return "Error: text is required"
        return AnthroidAccessibilityService.clickByText(text)
    }

    /**
     * Click at specific x,y coordinates.
     * Input: {"x": 500, "y": 800}
     */
    private fun clickPosition(input: String): String {
        val json = JSONObject(input)
        val x = json.optDouble("x", -1.0).toFloat()
        val y = json.optDouble("y", -1.0).toFloat()
        if (x < 0 || y < 0) return "Error: x and y coordinates required"
        return AnthroidAccessibilityService.clickAt(x, y)
    }

    /**
     * Type text into focused input field.
     * Input: {"text": "Hello world"}
     */
    private fun inputText(input: String): String {
        val json = JSONObject(input)
        val text = json.optString("text", "")
        if (text.isEmpty()) return "Error: text is required"
        return AnthroidAccessibilityService.inputText(text)
    }

    /**
     * Perform swipe gesture.
     * Input: {"start_x": 500, "start_y": 1500, "end_x": 500, "end_y": 500, "duration_ms": 300}
     */
    private fun swipe(input: String): String {
        val json = JSONObject(input)
        val startX = json.optDouble("start_x", -1.0).toFloat()
        val startY = json.optDouble("start_y", -1.0).toFloat()
        val endX = json.optDouble("end_x", -1.0).toFloat()
        val endY = json.optDouble("end_y", -1.0).toFloat()
        val durationMs = json.optLong("duration_ms", 300)
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            return "Error: start_x, start_y, end_x, end_y required"
        }
        return AnthroidAccessibilityService.swipe(startX, startY, endX, endY, durationMs)
    }

    /**
     * Long press at coordinates.
     * Input: {"x": 500, "y": 800, "duration_ms": 1000}
     */
    private fun longPress(input: String): String {
        val json = JSONObject(input)
        val x = json.optDouble("x", -1.0).toFloat()
        val y = json.optDouble("y", -1.0).toFloat()
        val durationMs = json.optLong("duration_ms", 1000)
        if (x < 0 || y < 0) return "Error: x and y coordinates required"
        return AnthroidAccessibilityService.longPressAt(x, y, durationMs)
    }

    /**
     * Press system back button.
     */
    private fun pressBack(): String {
        return AnthroidAccessibilityService.pressBack()
    }

    /**
     * Press system home button.
     */
    private fun pressHome(): String {
        return AnthroidAccessibilityService.pressHome()
    }

    /**
     * Open recent apps / overview.
     */
    private fun openRecents(): String {
        return AnthroidAccessibilityService.openRecents()
    }

    /**
     * Open notifications panel.
     */
    private fun openNotifications(): String {
        return AnthroidAccessibilityService.openNotifications()
    }

    /**
     * Scroll in a direction.
     * Input: {"direction": "up|down|forward|backward"}
     */
    private fun scroll(input: String): String {
        val json = JSONObject(input)
        val direction = json.optString("direction", "down")
        return AnthroidAccessibilityService.scroll(direction)
    }

    /**
     * Get accessibility service status.
     */
    private fun getAccessibilityStatus(): String {
        val isRunning = AnthroidAccessibilityService.isRunning()
        return JSONObject()
            .put("enabled", isRunning)
            .put("message", if (isRunning) "Accessibility service is enabled" else "Accessibility service not enabled. Enable in Settings > Accessibility > Anthroid Screen Automation")
            .toString(2)
    }

    /**
     * Wait for element with text to appear on screen.
     * Input: {"text": "Search", "timeout_ms": 5000, "poll_interval_ms": 500}
     */
    private suspend fun waitForElement(input: String): String {
        val json = JSONObject(input)
        val text = json.optString("text", "")
        if (text.isEmpty()) return """{"found": false, "error": "text is required"}"""
        val timeoutMs = json.optLong("timeout_ms", 5000)
        val pollIntervalMs = json.optLong("poll_interval_ms", 500)
        return AnthroidAccessibilityService.waitForElement(text, timeoutMs, pollIntervalMs)
    }

    /**
     * Click element by text and then input text.
     * Input: {"target": "Search", "text": "Hello world"}
     */
    private fun focusAndInput(input: String): String {
        val json = JSONObject(input)
        val target = json.optString("target", "")
        val text = json.optString("text", "")
        if (target.isEmpty()) return "Error: target is required"
        if (text.isEmpty()) return "Error: text is required"
        return AnthroidAccessibilityService.focusAndInput(target, text)
    }

    /**
     * Get current foreground app package name.
     */
    private fun getCurrentApp(): String {
        return AnthroidAccessibilityService.getCurrentApp()
    }
}
