package com.anthroid.remote

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
import com.anthroid.claude.SherpaOnnxManager
import com.anthroid.claude.ui.MessageAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen overlay Fragment for viewing and interacting with a remote agent session.
 *
 * Supports two modes:
 * - OpenClaw: structured messages via gateway events + chat.inject
 * - SSH+tmux: raw terminal content via periodic capture + send-keys
 */
class RemoteAgentFragment : Fragment() {

    companion object {
        private const val TAG = "RemoteAgentFragment"
        private const val ARG_SESSION_KEY = "session_key"
        private const val ARG_DISPLAY_NAME = "display_name"
        private const val ARG_SOURCE = "source"
        private const val ARG_HOSTNAME = "hostname"

        fun newInstance(session: RemoteSessionInfo): RemoteAgentFragment {
            return RemoteAgentFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_KEY, session.sessionKey)
                    putString(ARG_DISPLAY_NAME, session.label)
                    putString(ARG_SOURCE, session.source.name)
                    putString(ARG_HOSTNAME, session.hostname)
                }
            }
        }
    }

    private lateinit var viewModel: RemoteAgentViewModel

    private lateinit var headerBar: LinearLayout
    private lateinit var sessionNameView: TextView
    private lateinit var statusIndicator: TextView
    private lateinit var syncDownIndicator: TextView
    private lateinit var syncUpIndicator: TextView
    private lateinit var candidateMessage: TextView
    private lateinit var messageList: RecyclerView
    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalContent: TextView
    private lateinit var inputField: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnBack: ImageButton

    private lateinit var micContainer: FrameLayout
    private lateinit var btnMic: ImageButton
    private lateinit var micLoading: ProgressBar

    private lateinit var messageAdapter: MessageAdapter

    // Voice input
    private var sherpaOnnxManager: SherpaOnnxManager? = null
    private var isVoiceInputInitialized = false
    private var isVoiceInitializing = false

    private var lastSentMessage: String? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeVoiceInput()
        } else {
            Toast.makeText(requireContext(), "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    private var sessionDisplayName = ""   // stored for onDestroyView injection
    private var sessionAgentId = ""       // agentId from sessionKey (e.g. "main" from "agent:main:main")
    private var sessionSource = RemoteSessionInfo.Source.OPENCLAW
    private var sessionHostname = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_remote_agent, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[RemoteAgentViewModel::class.java]

        headerBar = view.findViewById(R.id.header_bar)
        sessionNameView = view.findViewById(R.id.session_name)
        syncDownIndicator = view.findViewById(R.id.sync_down_indicator)
        syncUpIndicator = view.findViewById(R.id.sync_up_indicator)
        statusIndicator = view.findViewById(R.id.status_indicator)
        candidateMessage = view.findViewById(R.id.candidate_message)
        messageList = view.findViewById(R.id.message_list)
        terminalScroll = view.findViewById(R.id.terminal_scroll)
        terminalContent = view.findViewById(R.id.terminal_content)
        inputField = view.findViewById(R.id.input_field)
        btnSend = view.findViewById(R.id.btn_send)
        btnBack = view.findViewById(R.id.btn_back)
        micContainer = view.findViewById(R.id.mic_container)
        btnMic = view.findViewById(R.id.btn_mic)
        micLoading = view.findViewById(R.id.mic_loading)

        val sessionKey = arguments?.getString(ARG_SESSION_KEY) ?: ""
        val displayName = arguments?.getString(ARG_DISPLAY_NAME) ?: sessionKey
        sessionDisplayName = displayName   // save for onDestroyView injection
        sessionAgentId = Regex("^agent:([^:]+):.+$").find(sessionKey)?.groupValues?.get(1) ?: ""
        val sourceName = arguments?.getString(ARG_SOURCE) ?: "OPENCLAW"
        val hostname = arguments?.getString(ARG_HOSTNAME)
        val source = RemoteSessionInfo.Source.valueOf(sourceName)
        sessionSource = source
        sessionHostname = hostname ?: ""

        sessionNameView.text = when (source) {
            RemoteSessionInfo.Source.OPENCLAW -> {
                val agentId = Regex("^agent:([^:]+):.+$").find(sessionKey)?.groupValues?.get(1)
                val suffix = agentId?.let { " [$it]" } ?: ""
                "🦞 $displayName$suffix"
            }
            RemoteSessionInfo.Source.SSH_TMUX -> displayName
        }

        // Setup based on source
        when (source) {
            RemoteSessionInfo.Source.OPENCLAW -> setupOpenClawMode(sessionKey)
            RemoteSessionInfo.Source.SSH_TMUX -> setupSshTmuxMode(sessionKey, hostname ?: "")
        }

        // Back button
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Send button
        btnSend.setOnClickListener { sendMessage() }
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        // Voice input
        setupVoiceInput()

        // Observe connection status — show as colored dot only
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionStatus.collectLatest { status ->
                statusIndicator.setTextColor(when {
                    status.startsWith("error") || status == "disconnected" -> 0xFFEF4444.toInt() // red
                    status == "connected" -> 0xFF22C55E.toInt()                                  // green
                    else -> 0xFFFBBF24.toInt()                                                   // yellow (connecting/reconnecting)
                })
            }
        }
    }

    private fun applyBannerStyle(bgColor: Int) {
        headerBar.setBackgroundColor(bgColor)
        sessionNameView.setTextColor(Color.WHITE)
        btnBack.setColorFilter(Color.WHITE)
        statusIndicator.setTextColor(0xFFA7F3D0.toInt()) // light green on dark bg
    }

    private fun setupOpenClawMode(sessionKey: String) {
        // Light red background for content area; dark red banner
        view?.setBackgroundColor(0xFFFFF0F0.toInt())
        applyBannerStyle(0xFF7F1D1D.toInt()) // dark red

        // Show message list, hide terminal
        messageList.visibility = View.VISIBLE
        terminalScroll.visibility = View.GONE

        // Setup RecyclerView with MessageAdapter
        messageAdapter = MessageAdapter(
            onImageClick = null,
            onToolClick = null,
        )
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        messageList.layoutManager = layoutManager
        messageList.adapter = messageAdapter

        // Observe messages
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collectLatest { messages ->
                messageAdapter.submitList(messages) {
                    if (messages.isNotEmpty()) {
                        messageList.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        viewModel.connectToOpenClawSession(sessionKey)
    }

    private fun setupSshTmuxMode(sessionName: String, hostname: String) {
        // Dark blue banner for tmux mode
        applyBannerStyle(0xFF0F172A.toInt()) // near-black dark blue

        // Show sync indicators and terminal, hide message list
        syncDownIndicator.visibility = View.VISIBLE
        syncUpIndicator.visibility = View.VISIBLE
        messageList.visibility = View.GONE
        terminalScroll.visibility = View.VISIBLE

        // Enable text selection on terminal content
        terminalContent.setTextIsSelectable(true)

        // Observe terminal content
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.terminalContent.collectLatest { content ->
                terminalContent.text = content
                // Auto-scroll to bottom only if not currently selecting text
                if (!terminalContent.hasSelection()) {
                    terminalScroll.post {
                        terminalScroll.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }

        // Observe sync status → highlight arrows
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSyncing.collectLatest { syncing ->
                syncDownIndicator.setTextColor(if (syncing) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSending.collectLatest { sending ->
                syncUpIndicator.setTextColor(if (sending) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            }
        }

        // Candidate message: tap to fill input with last sent message
        candidateMessage.setOnClickListener {
            lastSentMessage?.let { msg ->
                inputField.setText(msg)
                inputField.setSelection(msg.length)
            }
        }

        viewModel.connectToTmuxSession(hostname, sessionName)
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.text.clear()
        viewModel.sendMessage(text)

        // Show candidate for re-send (tmux mode only)
        if (sessionSource == RemoteSessionInfo.Source.SSH_TMUX) {
            lastSentMessage = text
            val display = if (text.length > 20) text.take(20) + "..." else text
            candidateMessage.text = display
            candidateMessage.visibility = View.VISIBLE
        }
    }

    // ── Voice Input ──────────────────────────────────────────────

    private fun setupVoiceInput() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val asrModel = prefs.getString("asr_model", "none") ?: "none"

        if (asrModel == "none") {
            micContainer.visibility = View.GONE
            return
        }

        if (!SherpaOnnxManager.isModelInstalled(requireContext())) {
            micContainer.visibility = View.GONE
            Log.w(TAG, "ASR model not installed, hiding mic button")
            return
        }

        micContainer.visibility = View.VISIBLE

        btnMic.setOnTouchListener { _, event ->
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

    private fun initializeVoiceInput() {
        if (isVoiceInputInitialized || isVoiceInitializing) return

        isVoiceInitializing = true
        btnMic.visibility = View.INVISIBLE
        micLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                sherpaOnnxManager = SherpaOnnxManager(requireContext())
                val success = sherpaOnnxManager?.initialize() ?: false
                if (success) {
                    isVoiceInputInitialized = true
                    isVoiceInitializing = false
                    micLoading.visibility = View.GONE
                    btnMic.visibility = View.VISIBLE
                    Log.i(TAG, "Voice input initialized successfully")
                } else {
                    isVoiceInitializing = false
                    micLoading.visibility = View.GONE
                    btnMic.visibility = View.VISIBLE
                    Log.e(TAG, "Failed to initialize voice input")
                    Toast.makeText(requireContext(), "Failed to initialize voice input", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing voice input: ${e.message}", e)
            }
        }
    }

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!isVoiceInputInitialized) {
            initializeVoiceInput()
            Toast.makeText(requireContext(), "Initializing voice input...", Toast.LENGTH_SHORT).show()
            return
        }

        if (sherpaOnnxManager?.startRecording() == true) {
            btnMic.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
            inputField.background?.setColorFilter(Color.parseColor("#4000FF00"), PorterDuff.Mode.SRC_ATOP)
            Toast.makeText(requireContext(), "Listening...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceRecording() {
        btnMic.clearColorFilter()
        inputField.background?.clearColorFilter()

        val finalText = sherpaOnnxManager?.stopRecording() ?: ""
        if (finalText.isNotEmpty()) {
            val existingText = inputField.text.toString()
            val textWithEmoji = "\uD83C\uDF99 $finalText"
            val newText = if (existingText.isNotEmpty()) "$existingText $textWithEmoji" else textWithEmoji
            inputField.setText(newText)
            inputField.setSelection(newText.length)
        }
    }

    override fun onDestroyView() {
        // Only inject when the fragment is truly being removed (user pressed Back),
        // NOT on config changes or when pushed onto the back stack.
        if (isRemoving) {
            val hostActivity = activity
            if (hostActivity != null) {
                try {
                    val claudeVm = ViewModelProvider(hostActivity)[com.anthroid.claude.ClaudeViewModel::class.java]
                    when (sessionSource) {
                        RemoteSessionInfo.Source.OPENCLAW -> {
                            val msgs = viewModel.messages.value
                            if (msgs.isNotEmpty()) {
                                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(hostActivity)
                                val host = prefs.getString("gateway_host", "") ?: ""
                                val port = prefs.getString("gateway_port", "40445") ?: "40445"
                                val gatewayAddr = if (host.isNotEmpty()) "$host:$port" else "gateway"
                                val agentSuffix = if (sessionAgentId.isNotEmpty()) " [$sessionAgentId]" else ""
                                val entryInfo = "openclaw $gatewayAddr, $sessionDisplayName$agentSuffix"

                                val history = msgs.joinToString("\n") { msg ->
                                    val label = when (msg.role) {
                                        com.anthroid.claude.MessageRole.USER -> "[user]"
                                        com.anthroid.claude.MessageRole.ASSISTANT -> "[assistant]"
                                        else -> "[system]"
                                    }
                                    "$label: ${msg.content}"
                                }.takeLast(5000)

                                claudeVm.injectRemoteResult("remote-agent", entryInfo, history)
                            }
                        }
                        RemoteSessionInfo.Source.SSH_TMUX -> {
                            val content = viewModel.terminalContent.value
                            if (content.isNotBlank()) {
                                val entryInfo = "tmux $sessionHostname, $sessionDisplayName"
                                val output = content.takeLast(5000)
                                claudeVm.injectRemoteResult("remote-tmux", entryInfo, output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to inject remote result: ${e.message}")
                }
            } else {
                Log.w(TAG, "activity null in onDestroyView, skipping injection")
            }
        }
        sherpaOnnxManager?.release()
        sherpaOnnxManager = null
        isVoiceInputInitialized = false

        viewModel.disconnect()
        super.onDestroyView()
    }
}
