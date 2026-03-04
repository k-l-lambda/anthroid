package com.anthroid.remote

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.graphics.Color
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
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
    private lateinit var messageList: RecyclerView
    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalContent: TextView
    private lateinit var inputField: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnBack: ImageButton

    private lateinit var messageAdapter: MessageAdapter
    private var sessionDisplayName = ""   // stored for onDestroyView injection

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
        statusIndicator = view.findViewById(R.id.status_indicator)
        messageList = view.findViewById(R.id.message_list)
        terminalScroll = view.findViewById(R.id.terminal_scroll)
        terminalContent = view.findViewById(R.id.terminal_content)
        inputField = view.findViewById(R.id.input_field)
        btnSend = view.findViewById(R.id.btn_send)
        btnBack = view.findViewById(R.id.btn_back)

        val sessionKey = arguments?.getString(ARG_SESSION_KEY) ?: ""
        val displayName = arguments?.getString(ARG_DISPLAY_NAME) ?: sessionKey
        sessionDisplayName = displayName   // save for onDestroyView injection
        val sourceName = arguments?.getString(ARG_SOURCE) ?: "OPENCLAW"
        val hostname = arguments?.getString(ARG_HOSTNAME)
        val source = RemoteSessionInfo.Source.valueOf(sourceName)

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

        // Show terminal, hide message list
        messageList.visibility = View.GONE
        terminalScroll.visibility = View.VISIBLE

        // Observe terminal content
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.terminalContent.collectLatest { content ->
                terminalContent.text = content
                // Auto-scroll to bottom
                terminalScroll.post {
                    terminalScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        viewModel.connectToTmuxSession(hostname, sessionName)
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.text.clear()
        viewModel.sendMessage(text)
    }

    override fun onDestroyView() {
        // Inject session summary into local conversation when Remote Agent View closes
        val msgs = viewModel.messages.value
        val userMsgs = msgs.filter { it.role == com.anthroid.claude.MessageRole.USER }
            .joinToString("\n---\n") { it.content }
        val agentMsgs = msgs.filter { it.role == com.anthroid.claude.MessageRole.ASSISTANT }
            .joinToString("\n---\n") { it.content }
        if (userMsgs.isNotEmpty() || agentMsgs.isNotEmpty()) {
            try {
                val claudeVm = ViewModelProvider(requireActivity())[com.anthroid.claude.ClaudeViewModel::class.java]
                claudeVm.injectRemoteResult(sessionDisplayName, userMsgs, agentMsgs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inject remote result: ${e.message}")
            }
        }
        viewModel.disconnect()
        super.onDestroyView()
    }
}
