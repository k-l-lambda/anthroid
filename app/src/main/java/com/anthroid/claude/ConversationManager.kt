package com.anthroid.claude

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages Claude CLI conversation history stored as JSONL files.
 * Storage location: ~/.claude/projects/{project-path}/
 */
class ConversationManager(private val context: Context) {

    companion object {
        private const val TAG = "ConversationManager"
        private const val CLAUDE_HOME = "/data/data/com.anthroid/files/home/.claude"
        private const val PROJECTS_DIR = "$CLAUDE_HOME/projects/-data-data-com-anthroid-files"
    }

    /**
     * Represents a conversation session.
     */
    data class Conversation(
        val sessionId: String,
        val title: String,
        val lastMessage: String,
        val timestamp: Long,
        val messageCount: Int,
        val fileSize: Long
    )

    /**
     * Represents a message in a conversation.
     */
    data class ConversationMessage(
        val uuid: String,
        val type: String,
        val content: String,
        val timestamp: Long,
        val toolName: String? = null,
        val toolInput: String? = null,
        val isError: Boolean = false
    )

    /**
     * Get all conversations sorted by most recent first.
     */
    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversations = mutableListOf<Conversation>()
        val projectsDir = File(PROJECTS_DIR)

        if (!projectsDir.exists()) {
            Log.w(TAG, "Projects directory does not exist: $PROJECTS_DIR")
            return@withContext emptyList()
        }

        val jsonlFiles = projectsDir.listFiles { file ->
            file.extension == "jsonl" && !file.name.startsWith("agent-")
        } ?: return@withContext emptyList()

        Log.i(TAG, "Found ${jsonlFiles.size} conversation files")

        for (file in jsonlFiles) {
            try {
                val conversation = parseConversationFile(file)
                if (conversation != null && conversation.messageCount > 0) {
                    conversations.add(conversation)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ${file.name}", e)
            }
        }

        conversations.sortedByDescending { it.timestamp }
    }

    /**
     * Parse a conversation JSONL file and extract summary info.
     */
    private fun parseConversationFile(file: File): Conversation? {
        if (file.length() == 0L) {
            return null
        }

        val sessionId = file.nameWithoutExtension
        var firstUserMessage: String? = null
        var lastUserMessage: String? = null
        var lastTimestamp: Long = 0
        var messageCount = 0

        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)
                    val type = json.optString("type", "")

                    if (type != "user" && type != "assistant") continue

                    messageCount++

                    val timestampStr = json.optString("timestamp", "")
                    if (timestampStr.isNotEmpty()) {
                        try {
                            val date = parseIsoTimestamp(timestampStr)
                            if (date > lastTimestamp) {
                                lastTimestamp = date
                            }
                        } catch (e: Exception) {
                        }
                    }

                    if (type == "user") {
                        val message = json.optJSONObject("message")
                        val content = extractMessageContent(message)
                        if (content.isNotEmpty()) {
                            if (firstUserMessage == null) {
                                firstUserMessage = content
                            }
                            lastUserMessage = content
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }

        if (messageCount == 0) {
            return null
        }

        val title = firstUserMessage?.take(50) ?: "Empty conversation"
        val lastMsg = lastUserMessage?.take(80) ?: ""

        return Conversation(
            sessionId = sessionId,
            title = title,
            lastMessage = lastMsg,
            timestamp = lastTimestamp,
            messageCount = messageCount,
            fileSize = file.length()
        )
    }

    /**
     * Extract text content from a message JSON object.
     * Skips tool_result content as those are internal tool responses.
     */
    private fun extractMessageContent(message: JSONObject?): String {
        if (message == null) return ""

        val content = message.opt("content")
        return when (content) {
            is String -> content
            is org.json.JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i)
                    if (item != null) {
                        val itemType = item.optString("type", "")
                        // Only extract text content, skip tool_result
                        if (itemType == "text") {
                            sb.append(item.optString("text", ""))
                        }
                        // Skip tool_result - these are internal tool responses
                    }
                }
                sb.toString()
            }
            else -> ""
        }
    }

    /**
     * Parse ISO timestamp string to milliseconds.
     */
    private fun parseIsoTimestamp(timestamp: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(timestamp)?.time ?: 0
        } catch (e: Exception) {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(timestamp)?.time ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    /**
     * Load full conversation messages for a session.
     */
    suspend fun loadConversation(sessionId: String): List<ConversationMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<ConversationMessage>()
        val file = File(PROJECTS_DIR, "$sessionId.jsonl")

        if (!file.exists()) {
            Log.w(TAG, "Conversation file not found: $sessionId")
            return@withContext emptyList()
        }

        // First pass: collect tool_result error status by tool_use_id
        // tool_result is in user messages, tool_use is in assistant messages
        val toolErrorMap = mutableMapOf<String, Boolean>()

        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)
                    val message = json.optJSONObject("message")
                    val contentArray = message?.optJSONArray("content")
                    if (contentArray != null) {
                        for (i in 0 until contentArray.length()) {
                            val item = contentArray.optJSONObject(i)
                            val itemType = item?.optString("type") ?: ""
                            if (itemType == "tool_result") {
                                val toolUseId = item.optString("tool_use_id", "")
                                val isError = item.optBoolean("is_error", false)
                                if (toolUseId.isNotEmpty()) {
                                    toolErrorMap[toolUseId] = isError
                                    Log.d(TAG, "Found tool_result: id=$toolUseId, isError=$isError")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parse errors in first pass
                }
            }
        }

        Log.d(TAG, "Collected ${toolErrorMap.size} tool results")

        // Second pass: build messages, matching tool_use with tool_result
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val json = JSONObject(line)
                    val type = json.optString("type", "")

                    if (type != "user" && type != "assistant") continue

                    val uuid = json.optString("uuid", "")
                    val timestampStr = json.optString("timestamp", "")
                    val timestamp = parseIsoTimestamp(timestampStr)

                    val message = json.optJSONObject("message")
                    val content = extractMessageContent(message)

                    var toolName: String? = null
                    var toolInput: String? = null
                    var toolUseId: String? = null
                    var isError = false

                    if (message != null) {
                        val contentArray = message.optJSONArray("content")
                        if (contentArray != null) {
                            for (i in 0 until contentArray.length()) {
                                val item = contentArray.optJSONObject(i)
                                val itemType = item?.optString("type") ?: ""
                                if (itemType == "tool_use") {
                                    toolName = item.optString("name", "")
                                    toolUseId = item.optString("id", "")
                                    val input = item.optJSONObject("input")
                                    toolInput = input?.toString()
                                    // Look up error status from tool_result
                                    if (toolUseId != null && toolUseId.isNotEmpty()) {
                                        isError = toolErrorMap[toolUseId] ?: false
                                        Log.d(TAG, "Tool $toolName (id=$toolUseId) isError=$isError")
                                    }
                                }
                            }
                        }
                    }

                    if (content.isNotEmpty() || toolName != null) {
                        messages.add(ConversationMessage(
                            uuid = uuid,
                            type = type,
                            content = content,
                            timestamp = timestamp,
                            toolName = toolName,
                            toolInput = toolInput,
                            isError = isError
                        ))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                }
            }
        }

        messages
    }

    /**
     * Delete a conversation.
     */
    suspend fun deleteConversation(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(PROJECTS_DIR, "$sessionId.jsonl")
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted conversation $sessionId: $deleted")
            deleted
        } else {
            false
        }
    }

    /**
     * Get storage statistics.
     */
    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        val projectsDir = File(PROJECTS_DIR)
        if (!projectsDir.exists()) {
            return@withContext StorageStats(0, 0, 0)
        }

        val files = projectsDir.listFiles { file ->
            file.extension == "jsonl" && !file.name.startsWith("agent-")
        } ?: return@withContext StorageStats(0, 0, 0)

        val totalSize = files.sumOf { it.length() }
        val emptyCount = files.count { it.length() == 0L }

        StorageStats(
            conversationCount = files.size,
            totalSizeBytes = totalSize,
            emptyConversations = emptyCount
        )
    }

    data class StorageStats(
        val conversationCount: Int,
        val totalSizeBytes: Long,
        val emptyConversations: Int
    )

    /**
     * Clean up empty conversation files.
     */
    suspend fun cleanupEmptyConversations(): Int = withContext(Dispatchers.IO) {
        val projectsDir = File(PROJECTS_DIR)
        if (!projectsDir.exists()) return@withContext 0

        val emptyFiles = projectsDir.listFiles { file ->
            file.extension == "jsonl" && file.length() == 0L
        } ?: return@withContext 0

        var deletedCount = 0
        for (file in emptyFiles) {
            if (file.delete()) {
                deletedCount++
            }
        }

        Log.i(TAG, "Cleaned up $deletedCount empty conversations")
        deletedCount
    }
}
