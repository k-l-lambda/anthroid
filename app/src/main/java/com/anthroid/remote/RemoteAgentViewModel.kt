package com.anthroid.remote

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthroid.claude.Message
import com.anthroid.claude.MessageRole
import com.anthroid.gateway.GatewayForegroundService
import com.anthroid.gateway.GatewayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * ViewModel for Remote Agent View.
 *
 * Manages state for two modes:
 * - OpenClaw: subscribes to gateway events filtered by sessionKey, sends via chat.inject
 * - SSH+tmux: periodic tmux capture-pane, sends via tmux send-keys
 */
class RemoteAgentViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RemoteAgentVM"
        private const val TMUX_SYNC_INTERVAL_MS = 3000L
    }

    // Dedicated scope for teardown work that must outlive viewModelScope
    private val teardownScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private var disconnected = false

    // Shared state
    private val _connectionStatus = MutableStateFlow("connecting...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    // OpenClaw mode: structured messages
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // SSH+tmux mode: raw terminal content
    private val _terminalContent = MutableStateFlow("")
    val terminalContent: StateFlow<String> = _terminalContent.asStateFlow()

    // Sync status indicators (tmux mode)
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private var mode: RemoteSessionInfo.Source? = null
    private var targetSessionKey: String? = null
    private var tmuxHostname: String? = null
    private var tmuxSessionName: String? = null
    private var tmuxOriginalSize: Pair<Int, Int>? = null
    private var tmuxSyncJob: Job? = null
    private var connectionWatchJob: Job? = null

    private val sshClient = SshTmuxClient()

    // Track injected messages to suppress the gateway echo.
    // chat.inject stores the message as assistant content and the gateway
    // immediately broadcasts it back as a final chat event. We skip the
    // first matching echo received within ECHO_SUPPRESS_MS of injection.
    private val pendingEchoes = mutableMapOf<String, Long>()
    private val ECHO_SUPPRESS_MS = 5_000L

    // Dedup: same message can arrive via both `agent.lifecycle.end` AND `chat.final`
    // events on the same connection. Track recently delivered (role, content) pairs
    // and discard within DEDUP_WINDOW_MS.
    private val recentDelivered = mutableMapOf<String, Long>()
    private val DEDUP_WINDOW_MS = 8_000L

    /** Evict stale entries from both maps to prevent unbounded growth. */
    private fun evictStaleMaps(now: Long) {
        pendingEchoes.entries.removeIf { now - it.value > ECHO_SUPPRESS_MS }
        recentDelivered.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
    }

    // ── OpenClaw Mode ──────────────────────────────────────────────

    fun connectToOpenClawSession(sessionKey: String) {
        mode = RemoteSessionInfo.Source.OPENCLAW
        targetSessionKey = sessionKey

        val service = GatewayForegroundService.instance
        if (service == null) {
            _connectionStatus.value = "error: gateway service unavailable"
            return
        }

        var historyLoaded = false

        // Watch for manager (re)creation — re-register callback on every new instance
        connectionWatchJob?.cancel()
        connectionWatchJob = viewModelScope.launch {
            service.gatewayManagerFlow.filterNotNull().collectLatest { manager ->
                Log.i(TAG, "Manager (re)attached for session $sessionKey")
                try {
                    // Collect remote session events from the SharedFlow
                    val eventJob = viewModelScope.launch {
                        manager.remoteSessionEventFlow.collect { evt ->
                            val eventSessionKey = evt.sessionKey
                            val role = evt.role
                            val content = evt.content
                            if (eventSessionKey == sessionKey) {
                                val now = System.currentTimeMillis()
                                evictStaleMaps(now)
                                val sentAt = pendingEchoes[content]
                                val isEcho = sentAt != null && (now - sentAt) < ECHO_SUPPRESS_MS
                                val dedupKey = "$role:$content"
                                val lastSeen = recentDelivered[dedupKey]
                                val isDuplicate = lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS
                                when {
                                    isEcho -> {
                                        pendingEchoes.remove(content)
                                        Log.d(TAG, "Suppressed echo: '${content.take(30)}'")
                                    }
                                    isDuplicate -> {
                                        Log.d(TAG, "Suppressed duplicate: '${content.take(30)}'")
                                    }
                                    else -> {
                                        recentDelivered[dedupKey] = now
                                        when (role) {
                                            "streaming" -> appendStreamingDelta(content)
                                            "user" -> appendMessage(MessageRole.USER, content)
                                            else -> {
                                                // Final assistant message: replace any in-progress streaming message
                                                finalizeStreamingMessage(content)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Load history once on first connection
                    if (!historyLoaded && manager.isConnected.value) {
                        historyLoaded = true
                        loadHistory(manager, sessionKey)
                    }

                    // Watch connection state of this manager instance
                    try {
                        manager.isConnected.collectLatest { connected ->
                            _connectionStatus.value = if (connected) "connected" else "reconnecting..."
                            // Load history after first reconnect if not yet loaded
                            if (connected && !historyLoaded) {
                                historyLoaded = true
                                loadHistory(manager, sessionKey)
                            }
                        }
                    } finally {
                        eventJob.cancel()
                        Log.d(TAG, "Cancelled event collection for superseded manager")
                    }
                } catch (e: CancellationException) {
                    throw e  // propagate cancellation normally
                } catch (e: Exception) {
                    Log.w(TAG, "Error in manager reattach block: ${e.message}")
                }
            }
        }

        Log.i(TAG, "Watching manager flow for session: $sessionKey")
    }

    private fun loadHistory(manager: com.anthroid.gateway.GatewayManager, sessionKey: String) {
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    manager.loadSessionHistory(sessionKey, limit = 40)
                }
                if (items.isNotEmpty() && _messages.value.isEmpty()) {
                    val messages = items.map { (role, text) ->
                        Message(
                            role = if (role == "user") MessageRole.USER else MessageRole.ASSISTANT,
                            content = text,
                        )
                    }
                    _messages.value = messages
                    Log.i(TAG, "Loaded ${messages.size} history messages for $sessionKey")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load history: ${e.message}")
            }
        }
    }

    // ── SSH+tmux Mode ──────────────────────────────────────────────

    fun connectToTmuxSession(hostname: String, sessionName: String, columns: Int = 0) {
        mode = RemoteSessionInfo.Source.SSH_TMUX
        tmuxHostname = hostname
        tmuxSessionName = sessionName

        _connectionStatus.value = "connecting..."

        // Start periodic sync
        tmuxSyncJob = viewModelScope.launch {
            // Save original size, then resize to match Anthroid terminal width
            if (columns > 0) {
                try {
                    tmuxOriginalSize = sshClient.getWindowSize(hostname, sessionName)
                    Log.i(TAG, "Saved original tmux size: ${tmuxOriginalSize}")
                    sshClient.resizeWindow(hostname, sessionName, columns)
                } catch (e: Exception) {
                    Log.w(TAG, "tmux resize failed (non-fatal): ${e.message}")
                }
            }
            var firstSync = true
            while (isActive) {
                try {
                    _isSyncing.value = true
                    val content = sshClient.capturePaneContent(hostname, sessionName)
                    _isSyncing.value = false
                    _terminalContent.value = content
                    if (firstSync) {
                        _connectionStatus.value = "connected"
                        firstSync = false
                    }
                } catch (e: Exception) {
                    _isSyncing.value = false
                    Log.w(TAG, "tmux sync failed: ${e.message}")
                    _connectionStatus.value = "error: ${e.message?.take(50)}"
                }
                delay(TMUX_SYNC_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Started tmux sync: $hostname:$sessionName")
    }

    // ── Message Sending ────────────────────────────────────────────

    fun sendMessage(text: String) {
        Log.i(TAG, "sendMessage: mode=$mode, text='${text.take(30)}'")
        when (mode) {
            RemoteSessionInfo.Source.OPENCLAW -> sendOpenClawMessage(text)
            RemoteSessionInfo.Source.SSH_TMUX -> sendTmuxMessage(text)
            null -> Log.w(TAG, "sendMessage: no mode set")
        }
    }

    private fun sendOpenClawMessage(text: String) {
        val sessionKey = targetSessionKey ?: return

        // Add to local message list immediately
        appendMessage(MessageRole.USER, text)
        // Register echo suppression before the inject so the gateway's
        // immediate broadcast is caught even if it arrives before inject returns
        pendingEchoes[text] = System.currentTimeMillis()

        viewModelScope.launch {
            try {
                val manager = GatewayForegroundService.instance?.gatewayManager
                manager?.sendChatMessage(sessionKey, text)
                Log.d(TAG, "Sent user message to $sessionKey: ${text.take(50)}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject message: ${e.message}")
                pendingEchoes.remove(text)
                appendMessage(MessageRole.SYSTEM, "Failed to send: ${e.message}")
            }
        }
    }

    private fun sendTmuxMessage(text: String) {
        val hostname = tmuxHostname
        val sessionName = tmuxSessionName
        if (hostname == null || sessionName == null) {
            Log.w(TAG, "sendTmuxMessage: hostname=$hostname sessionName=$sessionName — aborting")
            return
        }

        Log.i(TAG, "sendTmuxMessage: sending to $hostname:$sessionName")
        viewModelScope.launch {
            try {
                _isSending.value = true
                sshClient.sendKeys(hostname, sessionName, text)
                _isSending.value = false
                Log.d(TAG, "Sent keys to $hostname:$sessionName: ${text.take(50)}")
            } catch (e: Exception) {
                _isSending.value = false
                Log.w(TAG, "Failed to send keys: ${e.message}")
                _connectionStatus.value = "error: send failed"
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun appendMessage(role: MessageRole, content: String) {
        val message = Message(role = role, content = content)
        _messages.value = _messages.value + message
    }

    /** Append a streaming delta to the last ASSISTANT message (or create one if none exists). */
    private fun appendStreamingDelta(delta: String) {
        val msgs = _messages.value
        val last = msgs.lastOrNull()
        if (last != null && last.role == MessageRole.ASSISTANT && last.isStreaming) {
            // Append delta to in-progress streaming message
            _messages.value = msgs.dropLast(1) + last.copy(content = last.content + delta)
        } else {
            // Create new streaming message
            _messages.value = msgs + Message(
                role = MessageRole.ASSISTANT,
                content = delta,
                isStreaming = true,
            )
        }
    }

    /** Replace in-progress streaming message with clean final content, or append if none. */
    private fun finalizeStreamingMessage(content: String) {
        val msgs = _messages.value
        val last = msgs.lastOrNull()
        if (last != null && last.role == MessageRole.ASSISTANT && last.isStreaming) {
            // Replace streaming placeholder with final clean message
            _messages.value = msgs.dropLast(1) + last.copy(content = content, isStreaming = false)
        } else {
            appendMessage(MessageRole.ASSISTANT, content)
        }
    }

    fun disconnect() {
        if (disconnected) return
        disconnected = true

        val hostname = tmuxHostname
        val sessionName = tmuxSessionName
        val savedSize = tmuxOriginalSize
        val wasTmux = hostname != null && sessionName != null && mode == RemoteSessionInfo.Source.SSH_TMUX
        val syncJob = tmuxSyncJob
        val watchJob = connectionWatchJob
        tmuxSyncJob = null
        connectionWatchJob = null

        _connectionStatus.value = "disconnected"

        // teardownScope outlives viewModelScope — safe to use in onCleared
        teardownScope.launch {
            syncJob?.cancelAndJoin()
            watchJob?.cancelAndJoin()
            if (wasTmux) {
                try {
                    Log.i(TAG, "Restoring tmux size for $hostname:$sessionName (saved=$savedSize)")
                    sshClient.resizeWindow(hostname!!, sessionName!!, -1, savedSize)
                    Log.i(TAG, "tmux size restored")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore tmux auto-size: ${e.message}")
                }
            }
            Log.i(TAG, "Disconnected")
        }
    }

    override fun onCleared() {
        disconnect()
        // Don't cancel teardownScope here — let the resize command finish.
        // The scope uses SupervisorJob so it won't leak; the SSH command
        // has a 10s timeout and will complete on its own.
        super.onCleared()
    }
}
