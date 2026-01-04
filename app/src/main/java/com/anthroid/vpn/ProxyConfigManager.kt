package com.anthroid.vpn

import android.content.Context
import android.util.Log
import com.anthroid.vpn.models.ProxyConfig
import com.anthroid.vpn.models.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages proxy server configurations stored as a JSON file.
 * Storage location: {app_files_dir}/proxy_config.json
 */
class ProxyConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "ProxyConfigManager"
        private const val CONFIG_FILE = "proxy_config.json"

        @Volatile
        private var instance: ProxyConfigManager? = null

        fun getInstance(context: Context): ProxyConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ProxyConfigManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val configFile: File
        get() = File(context.filesDir, CONFIG_FILE)

    // Cached config
    @Volatile
    private var cachedConfig: ProxyConfig? = null

    /**
     * Load configuration from JSON file.
     */
    suspend fun loadConfig(): ProxyConfig = withContext(Dispatchers.IO) {
        cachedConfig?.let { return@withContext it }

        if (!configFile.exists()) {
            Log.i(TAG, "Config file does not exist, returning empty config")
            return@withContext ProxyConfig.empty().also { cachedConfig = it }
        }

        try {
            val content = configFile.readText()
            if (content.isBlank()) {
                return@withContext ProxyConfig.empty().also { cachedConfig = it }
            }
            val json = JSONObject(content)
            ProxyConfig.fromJson(json).also {
                cachedConfig = it
                Log.i(TAG, "Loaded config with ${it.servers.size} servers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            ProxyConfig.empty().also { cachedConfig = it }
        }
    }

    /**
     * Save configuration to JSON file.
     */
    suspend fun saveConfig(config: ProxyConfig) = withContext(Dispatchers.IO) {
        try {
            configFile.writeText(config.toJson().toString(2))
            cachedConfig = config
            Log.i(TAG, "Saved config with ${config.servers.size} servers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }

    /**
     * Add a new proxy server.
     */
    suspend fun addServer(server: ProxyServer): ProxyConfig {
        val config = loadConfig()
        val newConfig = config.copy(
            servers = config.servers + server
        )
        saveConfig(newConfig)
        return newConfig
    }

    /**
     * Update an existing proxy server.
     */
    suspend fun updateServer(server: ProxyServer): ProxyConfig {
        val config = loadConfig()
        val newServers = config.servers.map {
            if (it.id == server.id) server else it
        }
        val newConfig = config.copy(servers = newServers)
        saveConfig(newConfig)
        return newConfig
    }

    /**
     * Delete a proxy server by ID.
     */
    suspend fun deleteServer(serverId: String): ProxyConfig {
        val config = loadConfig()
        val newServers = config.servers.filter { it.id != serverId }
        val newActiveId = if (config.activeServerId == serverId) null else config.activeServerId
        val newConfig = config.copy(
            servers = newServers,
            activeServerId = newActiveId
        )
        saveConfig(newConfig)
        return newConfig
    }

    /**
     * Set the active proxy server.
     */
    suspend fun setActiveServer(serverId: String?): ProxyConfig {
        val config = loadConfig()
        val newConfig = config.copy(activeServerId = serverId)
        saveConfig(newConfig)
        return newConfig
    }

    /**
     * Toggle server enabled state.
     */
    suspend fun toggleServerEnabled(serverId: String): ProxyConfig {
        val config = loadConfig()
        val newServers = config.servers.map {
            if (it.id == serverId) it.copy(enabled = !it.enabled) else it
        }
        val newConfig = config.copy(servers = newServers)
        saveConfig(newConfig)
        return newConfig
    }

    /**
     * Update the global app list.
     */
    suspend fun updateGlobalAppList(apps: List<String>): ProxyConfig {
        val config = loadConfig()
        val newConfig = config.copy(globalAppList = apps)
        saveConfig(newConfig)
        return newConfig
    }

    /**
     * Generate a new unique server ID.
     */
    fun generateServerId(): String = UUID.randomUUID().toString()

    /**
     * Clear the cached config (useful for testing or force reload).
     */
    fun clearCache() {
        cachedConfig = null
    }
}
