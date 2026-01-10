package com.anthroid.claude

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import android.util.Log

/**
 * Manages quick send candidates - frequently used short messages.
 * Tracks usage frequency and provides top candidates for quick access.
 */
class QuickSendManager(context: Context) {

    companion object {
        private const val TAG = "QuickSendManager"
        private const val PREFS_NAME = "quick_send_stats"
        private const val KEY_STATS = "message_stats"
        private const val MAX_MESSAGE_LENGTH = 16
        private const val MIN_COUNT_THRESHOLD = 5
        private const val MAX_CANDIDATES = 5
        private const val VOICE_INPUT_PREFIX = "🎤 "
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val stats: MutableMap<String, Int> = mutableMapOf()

    init {
        loadStats()
    }

    /**
     * Load stats from SharedPreferences.
     */
    private fun loadStats() {
        try {
            val json = prefs.getString(KEY_STATS, null) ?: return
            val obj = JSONObject(json)
            obj.keys().forEach { key ->
                stats[key] = obj.getInt(key)
            }
            Log.d(TAG, "Loaded ${stats.size} message stats")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load stats", e)
        }
    }

    /**
     * Save stats to SharedPreferences.
     */
    private fun saveStats() {
        try {
            val obj = JSONObject()
            stats.forEach { (key, value) ->
                obj.put(key, value)
            }
            prefs.edit().putString(KEY_STATS, obj.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stats", e)
        }
    }

    /**
     * Track a message that was sent.
     * Only tracks messages with length < 16 characters.
     * Strips voice input prefix before tracking.
     */
    fun trackMessage(message: String) {
        // Strip voice input prefix if present
        var cleanMessage = message.trim()
        if (cleanMessage.startsWith(VOICE_INPUT_PREFIX)) {
            cleanMessage = cleanMessage.removePrefix(VOICE_INPUT_PREFIX).trim()
        }

        // Only track short messages
        if (cleanMessage.length >= MAX_MESSAGE_LENGTH || cleanMessage.isEmpty()) {
            return
        }

        val currentCount = stats[cleanMessage] ?: 0
        stats[cleanMessage] = currentCount + 1
        Log.d(TAG, "Tracked message: '$cleanMessage' (count: ${currentCount + 1})")
        saveStats()
    }

    /**
     * Get top candidates for quick send.
     * Returns messages with count > 5, sorted by frequency (highest first).
     * Maximum 5 candidates returned.
     */
    fun getCandidates(): List<QuickSendCandidate> {
        return stats
            .filter { it.value >= MIN_COUNT_THRESHOLD }
            .map { QuickSendCandidate(it.key, it.value) }
            .sortedByDescending { it.count }
            .take(MAX_CANDIDATES)
    }

    /**
     * Clear all tracked stats.
     */
    fun clearStats() {
        stats.clear()
        prefs.edit().remove(KEY_STATS).apply()
        Log.d(TAG, "Stats cleared")
    }
}

/**
 * Represents a quick send candidate.
 */
data class QuickSendCandidate(
    val text: String,
    val count: Int
)
