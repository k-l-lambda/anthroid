package com.anthroid.remote

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthroid.claude.Message
import com.anthroid.claude.MessageRole
import com.anthroid.gateway.GatewayForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Shared state
    private val _connectionStatus = MutableStateFlow("connecting...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    // OpenClaw mode: structured messages
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // SSH+tmux mode: raw terminal content
    private val _terminalContent = MutableStateFlow("")
    val terminalContent: StateFlow<String> = _terminalContent.asStateFlow()

    private var mode: RemoteSessionInfo.Source? = null
    private var targetSessionKey: String? = null
    private var tmuxHostname: String? = null
    private var tmuxSessionName: String? = null
    private var tmuxSyncJob: Job? = null
    private var connectionWatchJob: Job? = null

    private val sshClient = SshTmuxClient()

    // Track injected messages to suppress the gateway echo.
    // chat.inject stores the message as assistant content and the gateway
    // immediately broadcasts it back as a final chat event. We skip the
    // first matching echo received within ECHO_SUPPRESS_MS of injection.
    private val pendingEchoes = mutableMapOf<String, Long>()
    private val ECHO_SUPPRESS_MS = 5_000L

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

                // Register event callback filtered by session key
                manager.onRemoteSessionEvent = { eventSessionKey, role, content ->
                    if (eventSessionKey == sessionKey) {
                        val sentAt = pendingEchoes[content]
                        val isEcho = sentAt != null &&
                            (System.currentTimeMillis() - sentAt) < ECHO_SUPPRESS_MS
                        if (isEcho) {
                            pendingEchoes.remove(content)
                            Log.d(TAG, "Suppressed echo: '${content.take(30)}'")
                        } else {
                            val msgRole = when (role) {
                                "user" -> MessageRole.USER
                                else -> MessageRole.ASSISTANT
                            }
                            appendMessage(msgRole, content)
                        }
                    }
                }

                // Load history once on first connection
                if (!historyLoaded && manager.isConnected.value) {
                    historyLoaded = true
                    loadHistory(manager, sessionKey)
                }

                // Watch connection state of this manager instance
                manager.isConnected.collectLatest { connected ->
                    _connectionStatus.value = if (connected) "connected" else "reconnecting..."
                    // Load history after first reconnect if not yet loaded
                    if (connected && !historyLoaded) {
                        historyLoaded = true
                        loadHistory(manager, sessionKey)
                    }
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

    fun connectToTmuxSession(hostname: String, sessionName: String) {
        mode = RemoteSessionInfo.Source.SSH_TMUX
        tmuxHostname = hostname
        tmuxSessionName = sessionName

        _connectionStatus.value = "connecting..."

        // Start periodic sync
        tmuxSyncJob = viewModelScope.launch {
            var firstSync = true
            while (isActive) {
                try {
                    val content = sshClient.capturePaneContent(hostname, sessionName)
                    _terminalContent.value = content
                    if (firstSync) {
                        _connectionStatus.value = "connected"
                        firstSync = false
                    }
                } catch (e: Exception) {
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
                sshClient.sendKeys(hostname, sessionName, text)
                Log.d(TAG, "Sent keys to $hostname:$sessionName: ${text.take(50)}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send keys: ${e.message}")
                _connectionStatus.value = "error: send failed"
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun appendMessage(role: MessageRole, content: String) {
        val message = Message(
            role = role,
            content = content,
        )
        _messages.value = _messages.value + message
    }

    fun disconnect() {
        tmuxSyncJob?.cancel()
        tmuxSyncJob = null
        connectionWatchJob?.cancel()
        connectionWatchJob = null

        // Clear remote session event callback
        GatewayForegroundService.instance?.gatewayManager?.onRemoteSessionEvent = null

        _connectionStatus.value = "disconnected"
        Log.i(TAG, "Disconnected")
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
