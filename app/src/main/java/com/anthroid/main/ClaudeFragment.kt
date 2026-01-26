package com.anthroid.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
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
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ProgressBar
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
import com.anthroid.accessibility.ScreenAutomationOverlay
import com.anthroid.R
import com.anthroid.claude.ClaudeViewModel
import com.anthroid.claude.Message
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
import com.anthroid.claude.ui.QuickSendAdapter
import com.anthroid.claude.AskUserQuestionActivity
import com.anthroid.claude.QuickSendManager
import com.anthroid.mcp.McpServer

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
    private lateinit var micLoading: ProgressBar
    private lateinit var micContainer: View
    private lateinit var inputWrapper: FrameLayout
    private lateinit var statusText: TextView

    // Voice input
    private var sherpaOnnxManager: SherpaOnnxManager? = null
    private var isVoiceInputInitialized = false
    private var isVoiceInitializing = false
    private var isLastInputFromVoice = false
    private var originalInputBackground: android.graphics.drawable.Drawable? = null

    // Stop button state
    private var isStopMode = false

    // Track recent tool calls for overlay timing
    private var lastToolCallTime: Long = 0

    // Handler for auto-hide overlay
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlayHideRunnable: Runnable? = null

    // Track whether processing has ever started (to avoid showing "Completed" at startup)
    private var hasEverStartedProcessing = false

    // History panel views
    private lateinit var historyPanelContainer: FrameLayout
    private lateinit var historyDimOverlay: View
    private lateinit var historyPanel: LinearLayout
    private lateinit var historyList: RecyclerView
    private lateinit var historyStats: TextView
    private lateinit var historyEmptyText: TextView
    private lateinit var btnNewChat: ImageButton
    private lateinit var btnDeleteSelected: ImageButton

    // Pending images views
    private lateinit var pendingImagesScroll: HorizontalScrollView
    private lateinit var pendingImagesContainer: LinearLayout

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var conversationManager: ConversationManager

    // Quick send candidates
    private lateinit var quickSendManager: QuickSendManager
    private lateinit var quickSendRecyclerView: RecyclerView
    private lateinit var quickSendAdapter: QuickSendAdapter
    private var isKeyboardVisible = false

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

    // Launcher for AskUserQuestion activity
    private val askUserQuestionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val isMcpMode = result.data?.getBooleanExtra("IS_MCP_MODE", false) ?: false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val answersJson = result.data?.getStringExtra(AskUserQuestionActivity.EXTRA_ANSWERS_JSON)
            if (answersJson != null) {
                Log.i(TAG, "User answered questions: $answersJson (MCP mode: $isMcpMode)")
                if (isMcpMode || McpServer.pendingQuestion != null) {
                    // MCP mode - answer through McpServer
                    McpServer.answerQuestion(answersJson)
                } else {
                    // API mode - answer through ViewModel
                    viewModel.sendQuestionAnswer(answersJson)
                }
            }
        } else {
            Log.i(TAG, "User cancelled question dialog (MCP mode: $isMcpMode)")
            if (isMcpMode || McpServer.pendingQuestion != null) {
                McpServer.cancelQuestion()
            } else {
                viewModel.cancelQuestion()
            }
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
        quickSendManager = QuickSendManager.getInstance(requireContext())

        // Configure API from shared preferences
        configureApiFromPrefs()

        setupViews(view)
        setupHistoryPanel(view)
        setupRecyclerView()
        setupVoiceInput()
        setupQuickSend(view)
        observeViewModel()
        setupMcpQuestionCallback()
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
        micLoading = view.findViewById(R.id.mic_loading)
        micContainer = view.findViewById(R.id.mic_container)
        inputWrapper = view.findViewById(R.id.input_wrapper)
        statusText = view.findViewById(R.id.status_text)
        pendingImagesScroll = view.findViewById(R.id.pending_images_scroll)
        pendingImagesContainer = view.findViewById(R.id.pending_images_container)

        sendButton.setOnClickListener {
            if (isStopMode) {
                viewModel.cancelRequest()
            } else {
                sendMessage()
            }
        }

        // Watch for input text changes to toggle stop/send button
        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSendButtonState()
            }
        })

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        // Initialize button state (show send icon by default)
        sendButton.setImageResource(android.R.drawable.ic_menu_send)
    }

    private fun setupHistoryPanel(view: View) {
        historyPanelContainer = view.findViewById(R.id.history_panel_container)
        historyDimOverlay = view.findViewById(R.id.history_dim_overlay)
        historyPanel = view.findViewById(R.id.history_panel)
        historyList = view.findViewById(R.id.history_list)
        historyStats = view.findViewById(R.id.history_stats)
        historyEmptyText = view.findViewById(R.id.history_empty_text)
        btnNewChat = view.findViewById(R.id.btn_new_chat)
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected)

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
                // Delete disabled (use edit mode instead)
            },
            onTitleEdit = { conversation ->
                showEditTitleDialog(conversation)
            },
            onSelectionChanged = { selectedIds ->
                updateEditModeUI(selectedIds.size)
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

        // Delete selected button (visible in edit mode)
        btnDeleteSelected.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            onImageClick = { messageImage ->
                showImagePreviewDialog(messageImage)
            },
            onToolClick = { message ->
                showToolDetailDialog(message)
            }
        )
        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    /**
     * Show a full-screen image preview dialog.
     */
    private fun showImagePreviewDialog(messageImage: MessageImage) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)

        val previewImage = dialog.findViewById<ImageView>(R.id.preview_image)
        val closeButton = dialog.findViewById<ImageButton>(R.id.close_button)

        try {
            previewImage.setImageURI(messageImage.uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image for preview: ${messageImage.uri}", e)
            Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
            return
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        // Dismiss on background click
        previewImage.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Show tool input/output detail dialog.
     */
    private fun showToolDetailDialog(message: Message) {
        Log.d(TAG, "showToolDetailDialog: tool=${message.toolName}, hasOutput=${message.toolOutput != null}")
        val dialogView = layoutInflater.inflate(R.layout.dialog_tool_detail, null)

        val toolInputContent = dialogView.findViewById<TextView>(R.id.tool_input_content)
        val toolOutputContent = dialogView.findViewById<TextView>(R.id.tool_output_content)
        val outputLabel = dialogView.findViewById<TextView>(R.id.output_label)

        // Format tool input as pretty JSON if possible
        val inputText = message.toolInput ?: message.content
        toolInputContent.text = try {
            org.json.JSONObject(inputText).toString(2)
        } catch (e: Exception) {
            inputText
        }

        // Show tool output if available
        val outputText = message.toolOutput
        if (outputText != null && outputText.isNotEmpty()) {
            toolOutputContent.text = outputText
            toolOutputContent.visibility = View.VISIBLE
            outputLabel.visibility = View.VISIBLE
        } else if (message.isStreaming) {
            toolOutputContent.text = "Running..."
            toolOutputContent.visibility = View.VISIBLE
            outputLabel.visibility = View.VISIBLE
        } else {
            toolOutputContent.visibility = View.GONE
            outputLabel.visibility = View.GONE
        }

        // Strip MCP prefix for dialog title
        val displayName = (message.toolName ?: "Tool")
            .removePrefix("mcp__anthroid__")
            .removePrefix("mcp__")

        AlertDialog.Builder(requireContext())
            .setTitle(displayName)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Setup voice input with sherpa-onnx.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceInput() {
        // Check ASR model preference
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val asrModel = prefs.getString("asr_model", "none") ?: "none"
        
        if (asrModel == "none") {
            micButton.visibility = View.GONE
            return
        }
        
        // Check if model is installed
        if (!SherpaOnnxManager.isModelInstalled(requireContext())) {
            micButton.visibility = View.GONE
            Log.w(TAG, "ASR model not installed, hiding mic button")
            return
        }
        
        micButton.visibility = View.VISIBLE
        
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
     * Setup quick send candidates RecyclerView.
     */
    private fun setupQuickSend(view: View) {
        quickSendRecyclerView = view.findViewById(R.id.quick_send_recycler)

        quickSendAdapter = QuickSendAdapter { text ->
            // Send the message immediately when chip is clicked
            viewModel.sendMessage(text)
            quickSendManager.trackMessage(text)
            hideQuickSendCandidates()
        }

        quickSendRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, true)
            adapter = quickSendAdapter
        }

        // Detect keyboard visibility using ViewTreeObserver
        val rootView = view.rootView
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - rect.bottom

            // Keyboard is visible if it takes more than 15% of screen height
            val keyboardVisible = keypadHeight > screenHeight * 0.15

            if (keyboardVisible != isKeyboardVisible) {
                isKeyboardVisible = keyboardVisible
                if (keyboardVisible) {
                    showQuickSendCandidates()
                } else {
                    hideQuickSendCandidates()
                }
            }
        }
    }

    /**
     * Show quick send candidates if any are available.
     */
    private fun showQuickSendCandidates() {
        val candidates = quickSendManager.getCandidates()
        Log.d(TAG, "showQuickSendCandidates: ${candidates.size} candidates")
        if (candidates.isNotEmpty()) {
            // Order: highest frequency at bottom (reversed list since layoutManager is reversed)
            candidates.forEach { Log.d(TAG, "  - ${it.text}: ${it.count}") }
            quickSendAdapter.submitList(candidates.reversed())
            quickSendRecyclerView.visibility = View.VISIBLE
            Log.d(TAG, "QuickSend RecyclerView set to VISIBLE")
        }
    }

    /**
     * Hide quick send candidates.
     */
    private fun hideQuickSendCandidates() {
        quickSendRecyclerView.visibility = View.GONE
    }

    /**
     * Initialize sherpa-onnx voice input.
     */
    private fun initializeVoiceInput() {
        if (isVoiceInputInitialized || isVoiceInitializing) return

        isVoiceInitializing = true
        // Show loading indicator
        micButton.visibility = View.INVISIBLE
        micLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                sherpaOnnxManager = SherpaOnnxManager(requireContext())
                val success = sherpaOnnxManager?.initialize() ?: false
                if (success) {
                    isVoiceInputInitialized = true
                    isVoiceInitializing = false
                    micLoading.visibility = View.GONE
                    micButton.visibility = View.VISIBLE
                    Log.i(TAG, "Voice input initialized successfully")

                    // Real-time text updates logged for debugging only
                    // Final text with emoji is set in stopVoiceRecording()
                    sherpaOnnxManager?.recognizedText?.collect { text ->
                        if (text.isNotEmpty()) {
                            Log.d(TAG, "Real-time ASR: ")
                        }
                    }
                } else {
                    isVoiceInitializing = false
                    micLoading.visibility = View.GONE
                    micButton.visibility = View.VISIBLE
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
            micButton.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
            // Add green tint overlay to input field
            if (originalInputBackground == null) {
                originalInputBackground = inputField.background
            }
            inputWrapper.background?.setColorFilter(Color.parseColor("#4000FF00"), PorterDuff.Mode.SRC_ATOP)
            Toast.makeText(requireContext(), "Listening...", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Stop voice recording.
     */
    private fun stopVoiceRecording() {
        micButton.clearColorFilter()
        // Restore input field background
        inputWrapper.background?.clearColorFilter()

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

    override fun onPause() {
        super.onPause()
        // Set foreground flag false BEFORE showing overlay (fragment onPause runs before activity onPause)
        ScreenAutomationOverlay.isAppInForeground = false
        val isProcessing = viewModel.isProcessing.value
        val hasStreamingMessages = viewModel.messages.value.any { it.isStreaming }
        val hasRecentToolCall = (System.currentTimeMillis() - lastToolCallTime) < 10000 // 10 seconds
        Log.i(TAG, "onPause called, isProcessing=$isProcessing, hasStreamingMessages=$hasStreamingMessages, hasRecentToolCall=$hasRecentToolCall")
        // Show overlay when switching away from Anthroid while agent is processing, streaming, or recently called a tool
        if (isProcessing || hasStreamingMessages || hasRecentToolCall) {
            // Cancel any pending auto-hide (user left the app, keep overlay visible)
            overlayHideRunnable?.let { overlayHandler.removeCallbacks(it) }
            overlayHideRunnable = null
            try {
                val overlay = ScreenAutomationOverlay.getInstance(requireContext())
                if (isProcessing || hasStreamingMessages) {
                    // Still actively processing - show active overlay
                    // Use current tool name if available, otherwise generic message
                    val currentTool = viewModel.currentTool.value
                    val displayText = if (currentTool != null) {
                        val toolName = currentTool.replace("mcp__anthroid__", "").replace("mcp__", "")
                        "🔧 $toolName"
                    } else {
                        "Agent working..."
                    }
                    Log.i(TAG, "Showing active overlay on pause: $displayText")
                    overlay.show(displayText) {
                        viewModel.cancelRequest()
                    }
                } else {
                    // Agent finished but had recent tool call - show completed overlay
                    Log.i(TAG, "Showing completed overlay on pause")
                    overlay.show("Completed") {
                        viewModel.cancelRequest()
                    }
                    overlay.setCompleted("Completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay on pause", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Set foreground flag true (fragment onResume runs after activity onResume)
        ScreenAutomationOverlay.isAppInForeground = true
        Log.i(TAG, "onResume called")
        // Refresh mic button visibility (in case settings changed)
        updateMicButtonVisibility()
        // Hide overlay when returning to Anthroid (user can see the chat)
        try {
            val overlay = ScreenAutomationOverlay.getInstance(requireContext())
            overlay.hide()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Update mic button visibility based on current settings.
     * Also sets up touch listener if model becomes available.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun updateMicButtonVisibility() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val asrModel = prefs.getString("asr_model", "none") ?: "none"
        val modelInstalled = SherpaOnnxManager.isModelInstalled(requireContext())
        val shouldShow = asrModel != "none" && modelInstalled
        micButton.visibility = if (shouldShow) View.VISIBLE else View.GONE
        micContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
        
        // Update input field padding based on mic visibility
        val leftPadding = if (shouldShow) 44 else 12
        val density = resources.displayMetrics.density
        inputField.setPadding(
            (leftPadding * density).toInt(),
            inputField.paddingTop,
            inputField.paddingRight,
            inputField.paddingBottom
        )
        
        // Set up touch listener if showing (in case it wasn't set up during onCreate)
        if (shouldShow) {
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
            
            // Long-press on input wrapper also triggers voice recording
            inputWrapper.setOnLongClickListener {
                startVoiceRecording()
                true
            }
            inputWrapper.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    // Stop recording if it was started by long-press
                    if (sherpaOnnxManager?.isRecording() == true) {
                        stopVoiceRecording()
                    }
                }
                false // Don't consume the event, let it propagate
            }
        } else {
            // Clear listeners when mic is hidden
            inputWrapper.setOnLongClickListener(null)
            inputWrapper.setOnTouchListener(null)
        }
    }

    /**
     * Setup MCP server callback for ask_user_question tool in CLI mode.
     * This allows the MCP server to trigger the question dialog.
     */
    private fun setupMcpQuestionCallback() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        McpServer.onAskUserQuestion = { pending ->
            Log.i(TAG, "MCP ask_user_question callback triggered")
            // Post to main thread to launch activity
            mainHandler.post {
                try {
                    val intent = android.content.Intent(requireContext(), AskUserQuestionActivity::class.java).apply {
                        putExtra(AskUserQuestionActivity.EXTRA_QUESTIONS_JSON, pending.questionsJson)
                        putExtra(AskUserQuestionActivity.EXTRA_TOOL_ID, pending.toolId)
                        putExtra("IS_MCP_MODE", true)
                    }
                    askUserQuestionLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch AskUserQuestionActivity from MCP", e)
                    McpServer.cancelQuestion()
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                Log.d(TAG, "messages.collect: size=${messages.size}")
                messages.forEachIndexed { i, m ->
                    Log.d(TAG, "  [$i] id=${m.id.take(8)}, tool=${m.toolName}, len=${m.content.length}, streaming=${m.isStreaming}")
                }
                // Track tool calls for overlay timing (used in onPause)
                if (messages.any { it.toolName != null }) {
                    lastToolCallTime = System.currentTimeMillis()
                }
                messageAdapter.submitList(messages.toList()) {
                    if (messages.isNotEmpty()) {
                        recyclerView.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        // Use fragment's lifecycleScope (not viewLifecycleOwner) so this continues when fragment is paused
        lifecycleScope.launch {
            viewModel.isProcessing.collectLatest { isProcessing ->
                // Keep input enabled so user can type next message while processing
                updateSendButtonState()

                if (isProcessing) {
                    // Track that processing has started (for overlay logic in onPause)
                    hasEverStartedProcessing = true
                    // Cancel any pending hide operation
                    overlayHideRunnable?.let { overlayHandler.removeCallbacks(it) }
                    overlayHideRunnable = null
                    Log.i(TAG, "Processing started")
                } else if (hasEverStartedProcessing) {
                    // Processing completed - update overlay if it's showing (user is outside app)
                    try {
                        val overlay = ScreenAutomationOverlay.getInstance(requireContext())
                        overlay.setCompleted("Completed")
                        Log.i(TAG, "Processing completed, overlay set to Completed")
                        // Auto-hide overlay after 2 seconds when processing completes
                        overlayHideRunnable = Runnable { overlay.hide() }
                        overlayHandler.postDelayed(overlayHideRunnable!!, 2000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update overlay", e)
                    }
                }
            }
        }

        // Observe streaming text to update overlay banner
        // Use fragment's lifecycleScope so updates continue when fragment is paused (user outside app)
        lifecycleScope.launch {
            viewModel.currentResponse.collectLatest { text ->
                if (text.isNotEmpty()) {
                    try {
                        val overlay = ScreenAutomationOverlay.getInstance(requireContext())
                        val displayText = if (text.length > 80) {
                            "..." + text.takeLast(77)
                        } else {
                            text
                        }
                        Log.d(TAG, "currentResponse: ${displayText.take(50)}...")
                        overlay.updateText(displayText.replace("\n", " "))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update overlay text", e)
                    }
                }
            }
        }

        // Observe current tool to update overlay banner with tool name
        lifecycleScope.launch {
            viewModel.currentTool.collectLatest { toolName ->
                if (toolName != null) {
                    try {
                        val overlay = ScreenAutomationOverlay.getInstance(requireContext())
                        // Strip MCP prefixes for cleaner display
                        val displayName = toolName.replace("mcp__anthroid__", "")
                            .replace("mcp__", "")
                        Log.d(TAG, "currentTool: $displayName")
                        // Use show() instead of updateText() to ensure overlay is visible
                        overlay.show("🔧 $displayName") {
                            viewModel.cancelRequest()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update overlay with tool name", e)
                    }
                }
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
                updateSendButtonState()
            }
        }

        // Observe pending question for ask_user_question tool
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingQuestion.collectLatest { pending ->
                if (pending != null) {
                    Log.i(TAG, "Pending question received, launching AskUserQuestionActivity")
                    val intent = android.content.Intent(requireContext(), AskUserQuestionActivity::class.java).apply {
                        putExtra(AskUserQuestionActivity.EXTRA_QUESTIONS_JSON, pending.questionsJson)
                        putExtra(AskUserQuestionActivity.EXTRA_TOOL_ID, pending.toolId)
                    }
                    askUserQuestionLauncher.launch(intent)
                }
            }
        }

        // Observe debug messages from adb
        viewLifecycleOwner.lifecycleScope.launch {
            DebugReceiver.debugMessageFlow.collect { message ->
                Log.i(TAG, "Debug message received: ${message.take(50)}...")
                quickSendManager.trackMessage(message)
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

        // Observe set input requests from adb (sets text without sending)
        viewLifecycleOwner.lifecycleScope.launch {
            DebugReceiver.setInputFlow.collect { text ->
                Log.i(TAG, "Setting input field: ${text.take(50)}...")
                inputField.setText(text)
                inputField.setSelection(text.length)
            }
        }

        // Observe click send requests from adb
        viewLifecycleOwner.lifecycleScope.launch {
            DebugReceiver.clickSendFlow.collect {
                Log.i(TAG, "Click send triggered via adb")
                sendButton.performClick()
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
            // Track message for quick send candidates
            if (message.isNotEmpty()) {
                quickSendManager.trackMessage(message)
            }
            viewModel.sendMessage(message, isLastInputFromVoice)
            inputField.text.clear()
            isLastInputFromVoice = false
            // Refresh candidates if keyboard is still visible
            if (isKeyboardVisible) {
                showQuickSendCandidates()
            }
        }
    }

    /**
     * Update send button icon and state based on processing state and input text.
     * Shows stop icon (square) when processing and input is empty.
     * Shows send icon when not processing or input has text.
     */
    private fun updateSendButtonState() {
        val isProcessing = viewModel.isProcessing.value
        val inputEmpty = inputField.text.toString().trim().isEmpty()
        val hasPendingImages = viewModel.pendingImages.value.isNotEmpty()

        isStopMode = isProcessing && inputEmpty && !hasPendingImages

        if (isStopMode) {
            sendButton.setImageResource(R.drawable.ic_stop)
            sendButton.isEnabled = true
        } else {
            sendButton.setImageResource(android.R.drawable.ic_menu_send)
            // Enable send button only if there's content to send and not processing
            sendButton.isEnabled = !isProcessing || (!inputEmpty || hasPendingImages)
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
     * Append QR code text to the input field.
     * Called from MainPagerActivity after QR scan result.
     */
    fun appendQrText(text: String) {
        val currentText = inputField.text.toString()
        val newText = if (currentText.isEmpty()) text else currentText + " " + text
        inputField.setText(newText)
        inputField.setSelection(newText.length)
        inputField.requestFocus()
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

    /**
     * Update UI based on edit mode selection count.
     */
    private fun updateEditModeUI(selectedCount: Int) {
        if (conversationAdapter.isEditMode) {
            btnDeleteSelected.visibility = View.VISIBLE
            btnNewChat.visibility = View.GONE
            btnDeleteSelected.isEnabled = selectedCount > 0
            btnDeleteSelected.alpha = if (selectedCount > 0) 1.0f else 0.5f
        } else {
            btnDeleteSelected.visibility = View.GONE
            btnNewChat.visibility = View.VISIBLE
        }
    }

    /**
     * Exit edit mode and reset UI.
     */
    private fun exitEditMode() {
        conversationAdapter.exitEditMode()
        updateEditModeUI(0)
    }

    /**
     * Show confirmation dialog before deleting selected conversations.
     */
    private fun showDeleteConfirmDialog() {
        val count = conversationAdapter.getSelectedCount()
        if (count == 0) return

        AlertDialog.Builder(requireContext())
            .setTitle("Delete Conversations")
            .setMessage("Are you sure you want to delete $count conversation(s)?\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteSelectedConversations()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Delete selected conversations and refresh list.
     */
    private fun deleteSelectedConversations() {
        val selectedIds = conversationAdapter.getSelectedIds()
        if (selectedIds.isEmpty()) return

        viewLifecycleOwner.lifecycleScope.launch {
            var deletedCount = 0
            for (sessionId in selectedIds) {
                if (conversationManager.deleteConversation(sessionId)) {
                    deletedCount++
                }
            }
            exitEditMode()
            refreshHistoryPanel()
            Toast.makeText(requireContext(), "Deleted $deletedCount conversation(s)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show dialog to edit conversation title.
     */
    private fun showEditTitleDialog(conversation: com.anthroid.claude.ConversationManager.Conversation) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(conversation.title)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
            hint = "Enter title"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Title")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    conversationManager.setCustomTitle(conversation.sessionId, newTitle)
                    // Refresh the conversation list
                    refreshHistoryPanel()
                    Toast.makeText(requireContext(), "Title updated", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Reset") { _, _ ->
                conversationManager.setCustomTitle(conversation.sessionId, null)
                refreshHistoryPanel()
                Toast.makeText(requireContext(), "Title reset to default", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()

        // Show keyboard
        input.requestFocus()
        input.postDelayed({
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /**
     * Refresh the history panel with updated conversation list.
     */
    private fun refreshHistoryPanel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val conversations = conversationManager.getConversations()
            conversationAdapter.submitList(conversations)
        }
    }

    private fun hideHistoryPanel() {
        // Exit edit mode when closing panel
        if (conversationAdapter.isEditMode) {
            exitEditMode()
        }
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
