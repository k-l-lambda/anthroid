package com.anthroid.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.MotionEvent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.anthroid.claude.SherpaOnnxManager
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
    private lateinit var micButton: ImageButton
    private lateinit var statusText: TextView

    // Voice input
    private var sherpaOnnxManager: SherpaOnnxManager? = null
    private var isVoiceInputInitialized = false
    private var isLastInputFromVoice = false
    private var originalInputBackground: android.graphics.drawable.Drawable? = null

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

    // Permission launcher for audio recording
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeVoiceInput()
        } else {
            Toast.makeText(requireContext(), "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

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
        setupVoiceInput()
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
        micButton = view.findViewById(R.id.mic_button)
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

    /**
     * Setup voice input with sherpa-onnx.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceInput() {
        // Setup mic button touch listener (press and hold to speak)
        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startVoiceRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopVoiceRecording()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Initialize sherpa-onnx voice input.
     */
    private fun initializeVoiceInput() {
        if (isVoiceInputInitialized) return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                sherpaOnnxManager = SherpaOnnxManager(requireContext())
                val success = sherpaOnnxManager?.initialize() ?: false
                if (success) {
                    isVoiceInputInitialized = true
                    Log.i(TAG, "Voice input initialized successfully")

                    // Real-time text updates logged for debugging only
                    // Final text with emoji is set in stopVoiceRecording()
                    sherpaOnnxManager?.recognizedText?.collect { text ->
                        if (text.isNotEmpty()) {
                            Log.d(TAG, "Real-time ASR: ")
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to initialize voice input")
                    Toast.makeText(requireContext(), "Failed to initialize voice input", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing voice input: ${e.message}", e)
            }
        }
    }

    /**
     * Start voice recording.
     */
    private fun startVoiceRecording() {
        // Check permission first
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // Initialize if needed
        if (!isVoiceInputInitialized) {
            initializeVoiceInput()
            Toast.makeText(requireContext(), "Initializing voice input...", Toast.LENGTH_SHORT).show()
            return
        }

        // Start recording
        if (sherpaOnnxManager?.startRecording() == true) {
            micButton.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light))
            // Add red tint overlay to input field
            if (originalInputBackground == null) {
                originalInputBackground = inputField.background
            }
            inputField.background.setColorFilter(Color.parseColor("#40FF0000"), PorterDuff.Mode.SRC_ATOP)
            Toast.makeText(requireContext(), "Listening...", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Stop voice recording.
     */
    private fun stopVoiceRecording() {
        micButton.clearColorFilter()
        // Restore input field background
        inputField.background.clearColorFilter()

        val finalText = sherpaOnnxManager?.stopRecording() ?: ""
        if (finalText.isNotEmpty()) {
            val existingText = inputField.text.toString()
            val textWithEmoji = "🎤 $finalText"
            val newText = if (existingText.isNotEmpty()) "$existingText $textWithEmoji" else textWithEmoji
            inputField.setText(newText)
            inputField.setSelection(newText.length)
            isLastInputFromVoice = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sherpaOnnxManager?.release()
        sherpaOnnxManager = null
        isVoiceInputInitialized = false
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
            viewModel.sendMessage(message, isLastInputFromVoice)
            inputField.text.clear()
            isLastInputFromVoice = false
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
