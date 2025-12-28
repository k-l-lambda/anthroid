package com.anthroid.claude

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
        private const val CHANNEL_ID = "claude_agent"
    }

    init { createNotificationChannel() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Claude Agent", NotificationManager.IMPORTANCE_DEFAULT)
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
            else -> "Unknown tool: $name"
        }
    } catch (e: Exception) {
        Log.e(TAG, "Tool failed: $name", e)
        "Error: " + e.message
    }

    fun isAndroidTool(name: String) = name.lowercase() in listOf(
        "open_url", "launch_app", "send_intent", "list_apps", "get_app_info",
        "show_notification", "geocode", "reverse_geocode", "get_location",
        "query_calendar", "add_calendar_event", "query_media"
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
            .setSmallIcon(R.drawable.ic_service_notification).setContentTitle(title).setContentText(message).setAutoCancel(true).build()
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
}
