package com.anthroid.main

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.BuildConfig
import com.anthroid.R
import com.anthroid.claude.ClaudeViewModel
import com.anthroid.claude.ui.MessageAdapter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment for Claude AI chat interface.
 * Default screen when app opens.
 */
class ClaudeFragment : Fragment() {

    companion object {
        private const val TAG = "ClaudeFragment"
        fun newInstance(): ClaudeFragment = ClaudeFragment()
    }

    private lateinit var viewModel: ClaudeViewModel

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var statusText: TextView

    private lateinit var messageAdapter: MessageAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_claude, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel - scoped to Activity to survive fragment recreation
        viewModel = ViewModelProvider(requireActivity())[ClaudeViewModel::class.java]

        // Configure API from shared preferences
        configureApiFromPrefs()

        setupViews(view)
        setupRecyclerView()
        observeViewModel()
    }

    private fun configureApiFromPrefs() {
        val prefs = requireActivity().getSharedPreferences("claude_config", android.content.Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("base_url", BuildConfig.CLAUDE_API_BASE_URL) ?: BuildConfig.CLAUDE_API_BASE_URL
        val model = prefs.getString("model", BuildConfig.CLAUDE_API_MODEL) ?: BuildConfig.CLAUDE_API_MODEL

        if (apiKey.isNotBlank()) {
            viewModel.configureApi(apiKey, baseUrl, model)
        }
    }

    private fun setupViews(view: View) {
        recyclerView = view.findViewById(R.id.messages_recycler_view)
        inputField = view.findViewById(R.id.input_field)
        sendButton = view.findViewById(R.id.send_button)
        statusText = view.findViewById(R.id.status_text)

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
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                Log.d(TAG, "messages.collect: size=${messages.size}")
                messages.forEachIndexed { i, m ->
                    Log.d(TAG, "  [$i] id=${m.id.take(8)}, contentLen=${m.content.length}, streaming=${m.isStreaming}")
                }
                messageAdapter.submitList(messages.toList())
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isProcessing.collectLatest { isProcessing ->
                sendButton.isEnabled = !isProcessing
                inputField.isEnabled = !isProcessing
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                    viewModel.clearError()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
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

}
