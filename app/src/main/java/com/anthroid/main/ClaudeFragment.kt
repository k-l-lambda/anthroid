package com.anthroid.main

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.anthroid.claude.MessageRole
import com.anthroid.claude.MessageImage
import com.anthroid.claude.ui.MessageAdapter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.anthroid.claude.DebugReceiver
import com.anthroid.claude.ConversationManager
import com.anthroid.claude.ui.ConversationAdapter

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

    // History panel views
    private lateinit var historyPanelContainer: FrameLayout
    private lateinit var historyDimOverlay: View
    private lateinit var historyPanel: LinearLayout
    private lateinit var historyList: RecyclerView
    private lateinit var historyStats: TextView
    private lateinit var historyEmptyText: TextView
    private lateinit var btnNewChat: ImageButton

    // Pending images views
    private lateinit var pendingImagesScroll: HorizontalScrollView
    private lateinit var pendingImagesContainer: LinearLayout

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var conversationManager: ConversationManager

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
        conversationManager = ConversationManager(requireContext())

        // Configure API from shared preferences
        configureApiFromPrefs()

        setupViews(view)
        setupHistoryPanel(view)
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
        pendingImagesScroll = view.findViewById(R.id.pending_images_scroll)
        pendingImagesContainer = view.findViewById(R.id.pending_images_container)

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

    private fun setupHistoryPanel(view: View) {
        historyPanelContainer = view.findViewById(R.id.history_panel_container)
        historyDimOverlay = view.findViewById(R.id.history_dim_overlay)
        historyPanel = view.findViewById(R.id.history_panel)
        historyList = view.findViewById(R.id.history_list)
        historyStats = view.findViewById(R.id.history_stats)
        historyEmptyText = view.findViewById(R.id.history_empty_text)
        btnNewChat = view.findViewById(R.id.btn_new_chat)

        // Set panel width to 70% of screen
        view.post {
            val screenWidth = view.width
            val panelWidth = (screenWidth * 0.7).toInt()
            historyPanel.layoutParams = FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.END
            }
        }

        // Click dim area to close
        historyDimOverlay.setOnClickListener {
            hideHistoryPanel()
        }

        // Setup conversation adapter
        conversationAdapter = ConversationAdapter(
            onConversationClick = { conversation ->
                Log.i(TAG, "Selected conversation: ${conversation.sessionId}")
                viewModel.resumeConversation(conversation.sessionId)
                hideHistoryPanel()
                Toast.makeText(requireContext(), "Resumed: ${conversation.title.take(30)}", Toast.LENGTH_SHORT).show()
            },
            onDeleteClick = { _ ->
                // Delete disabled
            }
        )

        historyList.layoutManager = LinearLayoutManager(requireContext())
        historyList.adapter = conversationAdapter

        // New chat button
        btnNewChat.setOnClickListener {
            viewModel.startNewConversation()
            hideHistoryPanel()
            Toast.makeText(requireContext(), "Started new conversation", Toast.LENGTH_SHORT).show()
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

        // Observe pending images
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingImages.collect { images ->
                updatePendingImagesView(images)
            }
        }

        // Observe debug messages from adb
        viewLifecycleOwner.lifecycleScope.launch {
            DebugReceiver.debugMessageFlow.collect { message ->
                Log.i(TAG, "Debug message received: ${message.take(50)}...")
                viewModel.sendMessage(message)
            }
        }

        // Observe API config changes from adb
        viewLifecycleOwner.lifecycleScope.launch {
            DebugReceiver.apiConfigFlow.collect { config ->
                Log.i(TAG, "API config received: key=${config.apiKey.take(10)}...")
                configureApiFromPrefs()
                viewModel.checkClaudeInstallation()
                Toast.makeText(requireContext(), "API key configured via adb", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe read conversation requests from adb
        viewLifecycleOwner.lifecycleScope.launch {
            DebugReceiver.readConversationFlow.collect {
                Log.i(TAG, "Read conversation request received")
                val messages = viewModel.messages.value
                val sb = StringBuilder()
                messages.forEach { msg ->
                    val role = if (msg.role == MessageRole.USER) "USER" else "ASSISTANT"
                    sb.append("[$role] ${msg.content}")
                    if (msg.toolName != null) {
                        sb.append("\nTOOL: ${msg.toolName}(${msg.toolInput?.take(100) ?: ""})")
                    }
                    sb.append("\n\n")
                }
                DebugReceiver.updateConversation(sb.toString())
                Log.i(TAG, "Conversation updated: ${messages.size} messages")
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
        val hasPendingImages = viewModel.pendingImages.value.isNotEmpty()
        if (message.isNotEmpty() || hasPendingImages) {
            viewModel.sendMessage(message)
            inputField.text.clear()
        }
    }

    /**
     * Add a pending image from camera or gallery.
     * Called from MainPagerActivity after camera result.
     */
    fun addPendingImage(uri: Uri) {
        viewModel.addPendingImage(uri)
    }

    /**
     * Update the pending images preview area.
     */
    private fun updatePendingImagesView(images: List<MessageImage>) {
        pendingImagesContainer.removeAllViews()

        if (images.isEmpty()) {
            pendingImagesScroll.visibility = View.GONE
            return
        }

        pendingImagesScroll.visibility = View.VISIBLE

        images.forEach { image ->
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_pending_image, pendingImagesContainer, false)

            val imageView = itemView.findViewById<ImageView>(R.id.pending_image)
            val removeButton = itemView.findViewById<ImageButton>(R.id.btn_remove_image)

            imageView.setImageURI(image.uri)

            removeButton.setOnClickListener {
                viewModel.removePendingImage(image.id)
            }

            pendingImagesContainer.addView(itemView)
        }
    }

    /**
     * Show history panel (right side, 70% width).
     * Public to allow calling from MainPagerActivity menu.
     */
    fun showConversationHistoryDialog() {
        showHistoryPanel()
    }

    private fun showHistoryPanel() {
        historyPanelContainer.visibility = View.VISIBLE

        // Load conversations
        viewLifecycleOwner.lifecycleScope.launch {
            val conversations = conversationManager.getConversations()
            val stats = conversationManager.getStorageStats()

            conversationAdapter.submitList(conversations)

            if (conversations.isEmpty()) {
                historyEmptyText.visibility = View.VISIBLE
                historyList.visibility = View.GONE
            } else {
                historyEmptyText.visibility = View.GONE
                historyList.visibility = View.VISIBLE
            }

            val sizeKb = stats.totalSizeBytes / 1024
            historyStats.text = "${stats.conversationCount} conversations · ${sizeKb}KB"
        }

        // Animate panel slide in from right
        historyPanel.translationX = historyPanel.width.toFloat()
        historyPanel.animate()
            .translationX(0f)
            .setDuration(200)
            .start()

        historyDimOverlay.alpha = 0f
        historyDimOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun hideHistoryPanel() {
        // Animate panel slide out to right
        historyPanel.animate()
            .translationX(historyPanel.width.toFloat())
            .setDuration(200)
            .start()

        historyDimOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                historyPanelContainer.visibility = View.GONE
            }
            .start()
    }

    /**
     * Show dialog to configure API key for Android tools support.
     */
    fun showApiConfigDialog() {
        val prefs = requireActivity().getSharedPreferences("claude_config", android.content.Context.MODE_PRIVATE)
        val currentKey = prefs.getString("api_key", "") ?: ""

        val input = EditText(requireContext()).apply {
            hint = "sk-ant-api03-..."
            setText(currentKey)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Configure Claude API")
            .setMessage("Enter your Anthropic API key to enable Android tools (notification, location, calendar, etc.)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString("api_key", key).apply()
                configureApiFromPrefs()
                viewModel.checkClaudeInstallation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

}
