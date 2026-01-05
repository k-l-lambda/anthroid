package com.anthroid.claude

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages backup and restore of user data:
 * - Claude conversations (JSONL files)
 * - Claude config (SharedPreferences)
 * - Proxy config (JSON file)
 * - App preferences (SharedPreferences)
 */
class BackupManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_VERSION = 1
        private const val CLAUDE_HOME = "/data/data/com.anthroid/files/home/.claude"
        private const val PROJECTS_DIR = "$CLAUDE_HOME/projects/-data-data-com-anthroid-files"
    }

    data class BackupInfo(
        val conversationCount: Int,
        val totalSizeBytes: Long,
        val timestamp: Long,
        val version: Int
    )

    /**
     * Create a backup ZIP file containing all user data.
     * @param outputFile The file to write the backup to
     * @return BackupInfo with statistics about the backup
     */
    suspend fun createBackup(outputFile: File): BackupInfo = withContext(Dispatchers.IO) {
        var conversationCount = 0
        var totalSize = 0L

        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            // 1. Write metadata
            val metadata = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("timestamp", System.currentTimeMillis())
                put("app_version", getAppVersion())
            }
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write(metadata.toString(2).toByteArray())
            zip.closeEntry()

            // 2. Backup Claude config SharedPreferences
            val claudeConfig = backupClaudeConfig()
            zip.putNextEntry(ZipEntry("claude_config.json"))
            zip.write(claudeConfig.toString(2).toByteArray())
            zip.closeEntry()

            // 3. Backup default preferences (ASR model, etc.)
            val defaultPrefs = backupDefaultPreferences()
            zip.putNextEntry(ZipEntry("default_preferences.json"))
            zip.write(defaultPrefs.toString(2).toByteArray())
            zip.closeEntry()

            // 4. Backup proxy config if exists
            val proxyConfigFile = File(context.filesDir, "proxy_config.json")
            if (proxyConfigFile.exists()) {
                zip.putNextEntry(ZipEntry("proxy_config.json"))
                zip.write(proxyConfigFile.readBytes())
                zip.closeEntry()
            }

            // 5. Backup conversation files
            val projectsDir = File(PROJECTS_DIR)
            if (projectsDir.exists()) {
                val jsonlFiles = projectsDir.listFiles { file ->
                    file.extension == "jsonl"
                } ?: emptyArray()

                for (file in jsonlFiles) {
                    if (file.length() > 0) {
                        zip.putNextEntry(ZipEntry("conversations/${file.name}"))
                        zip.write(file.readBytes())
                        zip.closeEntry()
                        conversationCount++
                        totalSize += file.length()
                    }
                }
                Log.i(TAG, "Backed up $conversationCount conversations")
            }
        }

        Log.i(TAG, "Backup created: ${outputFile.absolutePath}, size=${outputFile.length()}")
        BackupInfo(
            conversationCount = conversationCount,
            totalSizeBytes = outputFile.length(),
            timestamp = System.currentTimeMillis(),
            version = BACKUP_VERSION
        )
    }

    /**
     * Restore data from a backup ZIP file.
     * @param inputFile The backup file to restore from
     * @return Number of items restored
     */
    suspend fun restoreBackup(inputFile: File): Int = withContext(Dispatchers.IO) {
        var restoredCount = 0

        ZipInputStream(inputFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry

            while (entry != null) {
                when {
                    entry.name == "metadata.json" -> {
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        val metadata = JSONObject(content)
                        val version = metadata.optInt("version", 0)
                        Log.i(TAG, "Restoring backup version $version")
                    }

                    entry.name == "claude_config.json" -> {
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        restoreClaudeConfig(JSONObject(content))
                        restoredCount++
                    }

                    entry.name == "default_preferences.json" -> {
                        val content = zip.readBytes().toString(Charsets.UTF_8)
                        restoreDefaultPreferences(JSONObject(content))
                        restoredCount++
                    }

                    entry.name == "proxy_config.json" -> {
                        val content = zip.readBytes()
                        val proxyConfigFile = File(context.filesDir, "proxy_config.json")
                        proxyConfigFile.writeBytes(content)
                        restoredCount++
                    }

                    entry.name.startsWith("conversations/") -> {
                        val fileName = entry.name.removePrefix("conversations/")
                        val projectsDir = File(PROJECTS_DIR)
                        projectsDir.mkdirs()
                        val destFile = File(projectsDir, fileName)
                        destFile.writeBytes(zip.readBytes())
                        restoredCount++
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        Log.i(TAG, "Restored $restoredCount items from backup")
        restoredCount
    }

    /**
     * Get backup info from a file without fully extracting it.
     */
    suspend fun getBackupInfo(inputFile: File): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            var version = 0
            var timestamp = 0L
            var conversationCount = 0

            ZipInputStream(inputFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "metadata.json" -> {
                            val content = zip.readBytes().toString(Charsets.UTF_8)
                            val metadata = JSONObject(content)
                            version = metadata.optInt("version", 0)
                            timestamp = metadata.optLong("timestamp", 0)
                        }
                        entry.name.startsWith("conversations/") -> {
                            conversationCount++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            BackupInfo(
                conversationCount = conversationCount,
                totalSizeBytes = inputFile.length(),
                timestamp = timestamp,
                version = version
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read backup info", e)
            null
        }
    }

    /**
     * Generate a backup filename with timestamp.
     */
    fun generateBackupFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "anthroid_backup_${dateFormat.format(Date())}.zip"
    }

    private fun backupClaudeConfig(): JSONObject {
        val prefs = context.getSharedPreferences("claude_config", Context.MODE_PRIVATE)
        return JSONObject().apply {
            // Don't backup API key for security, user should re-enter
            put("base_url", prefs.getString("base_url", ""))
            put("model", prefs.getString("model", ""))
        }
    }

    private fun backupDefaultPreferences(): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return JSONObject().apply {
            put("asr_model", prefs.getString("asr_model", "none"))
            // Add other relevant preferences here
        }
    }

    private fun restoreClaudeConfig(json: JSONObject) {
        val prefs = context.getSharedPreferences("claude_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            json.optString("base_url", "").let { if (it.isNotEmpty()) putString("base_url", it) }
            json.optString("model", "").let { if (it.isNotEmpty()) putString("model", it) }
            apply()
        }
    }

    private fun restoreDefaultPreferences(json: JSONObject) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().apply {
            json.optString("asr_model", "").let { if (it.isNotEmpty()) putString("asr_model", it) }
            apply()
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
