package com.anthroid.claude

import android.content.Context
import android.os.Bundle
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
import android.widget.ProgressBar
import android.widget.TextView
import com.anthroid.BuildConfig
import com.anthroid.R
import com.anthroid.claude.ui.MessageAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chat activity for Claude AI interaction.
 */
class ClaudeActivity : AppCompatActivity() {

    private val viewModel: ClaudeViewModel by viewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var streamingText: TextView

    private lateinit var messageAdapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_claude)

        // Configure API from shared preferences or environment
        configureApiFromPrefs()

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
        progressBar = findViewById(R.id.progress_bar)
        statusText = findViewById(R.id.status_text)
        streamingText = findViewById(R.id.streaming_text)

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
            viewModel.messages.collectLatest { messages ->
                messageAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.currentResponse.collectLatest { response ->
                if (response.isNotEmpty()) {
                    streamingText.visibility = View.VISIBLE
                    streamingText.text = response
                } else {
                    streamingText.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isProcessing.collectLatest { isProcessing ->
                progressBar.visibility = if (isProcessing) View.VISIBLE else View.GONE
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
    }
}
