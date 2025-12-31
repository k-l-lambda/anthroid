package com.anthroid.claude

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.anthroid.BuildConfig
import com.anthroid.R
import com.anthroid.app.TermuxService
import com.anthroid.claude.ui.MessageAdapter
import com.anthroid.terminal.TerminalSession
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chat activity for Claude AI interaction.
 */
class ClaudeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClaudeActivity"
    }

    private val viewModel: ClaudeViewModel by viewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var statusText: TextView

    private lateinit var messageAdapter: MessageAdapter

    private var termuxService: TermuxService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "TermuxService connected")
            val binder = service as? TermuxService.LocalBinder
            termuxService = binder?.service

            termuxService?.let { svc ->
                // Register terminal command bridge
                TerminalCommandBridge.register(svc, ::getCurrentSession)
                Log.d(TAG, "TerminalCommandBridge registered")

                // Ensure at least one session exists for command execution
                if (svc.termuxSessions.isEmpty()) {
                    Log.d(TAG, "No terminal sessions, creating one for Claude")
                    svc.createTermuxSession(null, null, null, null, false, "claude-session")
                    Log.d(TAG, "Terminal session created")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "TermuxService disconnected")
            TerminalCommandBridge.unregister()
            termuxService = null
        }
    }

    /**
     * Get current/first available terminal session for command execution.
     */
    private fun getCurrentSession(): TerminalSession? {
        val svc = termuxService ?: return null
        return if (svc.termuxSessions.isNotEmpty()) {
            svc.termuxSessions[0].terminalSession
        } else {
            null
        }
    }

    private fun bindTermuxService() {
        val intent = Intent(this, TermuxService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_claude)

        // Configure API from shared preferences or environment
        configureApiFromPrefs()

        // Bind to TermuxService for terminal command execution
        bindTermuxService()

        setupViews()
        setupRecyclerView()
        observeViewModel()
    }

    private fun configureApiFromPrefs() {
        val prefs = getSharedPreferences("claude_config", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        // Use BuildConfig defaults, allow override from prefs
        val baseUrl = prefs.getString("base_url", BuildConfig.CLAUDE_API_BASE_URL) ?: BuildConfig.CLAUDE_API_BASE_URL
        val model = prefs.getString("model", BuildConfig.CLAUDE_API_MODEL) ?: BuildConfig.CLAUDE_API_MODEL

        if (apiKey.isNotBlank()) {
            viewModel.configureApi(apiKey, baseUrl, model)
        }
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.messages_recycler_view)
        inputField = findViewById(R.id.input_field)
        sendButton = findViewById(R.id.send_button)
        statusText = findViewById(R.id.status_text)

        sendButton.setOnClickListener {
            sendMessage()
        }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ClaudeActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                messageAdapter.submitList(messages.toList())
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isProcessing.collectLatest { isProcessing ->
                sendButton.isEnabled = !isProcessing
                inputField.isEnabled = !isProcessing
            }
        }

        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    Toast.makeText(this@ClaudeActivity, it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isClaudeInstalled.collectLatest { isInstalled ->
                if (!isInstalled) {
                    statusText.visibility = View.VISIBLE
                    statusText.text = "Claude CLI not installed. Run: npm install -g @anthropic-ai/claude-code"
                    sendButton.isEnabled = false
                } else {
                    statusText.visibility = View.GONE
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun sendMessage() {
        val message = inputField.text.toString().trim()
        if (message.isNotEmpty()) {
            viewModel.sendMessage(message)
            inputField.text.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding service", e)
        }
    }
}
