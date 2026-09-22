package com.anthroid.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.anthroid.R
import com.anthroid.app.TermuxActivity
import com.anthroid.accessibility.ScreenAutomationOverlay
import com.anthroid.app.TermuxService
import com.anthroid.app.activities.SettingsActivity
import com.anthroid.claude.AskUserQuestionActivity
import com.anthroid.claude.CameraCaptureActivity
import com.anthroid.claude.DebugReceiver
import com.anthroid.claude.TerminalCommandBridge
import com.anthroid.gateway.GatewayForegroundService
import com.anthroid.gateway.GatewayNotificationHelper
import com.anthroid.gateway.GatewayManager
import com.anthroid.terminal.TerminalSession
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Main activity that hosts ClaudeFragment as the default screen.
 * Terminal and Settings accessible via ActionBar menu.
 * Also binds to TermuxService to enable terminal command bridge for Claude.
 */
class MainPagerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainPagerActivity"
    }

    private var termuxService: TermuxService? = null
    private var serviceBound = false

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, granted) ->
            Log.d(TAG, "Permission $permission: ${if (granted) "granted" else "denied"}")
        }
    }

    // Camera activity launcher
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val imageUri = result.data?.getParcelableExtra<Uri>(CameraCaptureActivity.EXTRA_IMAGE_URI)
            val qrText = result.data?.getStringExtra(CameraCaptureActivity.EXTRA_QR_TEXT)
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (fragment is ClaudeFragment) {
                if (imageUri != null) {
                    Log.d(TAG, "Camera result: $imageUri")
                    fragment.addPendingImage(imageUri)
                } else if (qrText != null) {
                    Log.d(TAG, "QR scan result: " + qrText.take(50) + "...")
                    fragment.appendQrText(qrText)
                }
            }
        }
    }

    // Required permissions for Android tools
    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Location permissions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Calendar permissions
        permissions.add(Manifest.permission.READ_CALENDAR)
        permissions.add(Manifest.permission.WRITE_CALENDAR)

        // Media permissions (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        return permissions.toTypedArray()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d(TAG, "All permissions already granted")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "TermuxService connected")
            val binder = service as? TermuxService.LocalBinder
            termuxService = binder?.service

            termuxService?.let { svc ->
                // Register terminal command bridge
                TerminalCommandBridge.register(svc, ::getCurrentSession)

                // Ensure at least one session exists for command execution
                if (svc.termuxSessions.isEmpty()) {
                    Log.d(TAG, "No terminal sessions, creating one for Claude")
                    val termuxSession = svc.createTermuxSession(null, null, null, null, false, "claude-session")
                    // Force-initialize emulator with default dimensions (80x24)
                    // Without this, the emulator stays null until a TerminalView is attached
                    termuxSession?.terminalSession?.let { ts ->
                        if (ts.emulator == null) {
                            Log.d(TAG, "Initializing terminal emulator with default 80x24")
                            ts.updateSize(80, 24, 0, 0)
                        }
                    }
                    Log.d(TAG, "Terminal session created and initialized")
                } else {
                    // Ensure existing session has emulator initialized
                    svc.termuxSessions.firstOrNull()?.terminalSession?.let { ts ->
                        if (ts.emulator == null) {
                            Log.d(TAG, "Initializing existing session emulator with default 80x24")
                            ts.updateSize(80, 24, 0, 0)
                        }
                    }
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
        val service = termuxService ?: return null
        val sessions = service.termuxSessions
        return if (sessions.isNotEmpty()) {
            sessions[0].terminalSession
        } else {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if bootstrap is needed - redirect to Terminal if so
        val binDir = java.io.File("/data/data/com.anthroid/files/usr/bin")
        val bashBinary = java.io.File(binDir, "bash")
        if (!binDir.exists() || !bashBinary.exists()) {
            Log.d(TAG, "Bootstrap not complete, redirecting to Terminal")
            startActivity(Intent(this, TermuxActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main_pager)
        
        // Check and request required permissions for Android tools
        checkAndRequestPermissions()
        
        // Bind to TermuxService early and keep it bound
        bindTermuxService()

        // Load ClaudeFragment if not already present
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ClaudeFragment.newInstance())
                .commit()
        }

        // Handle notification deep-link
        handleNotificationDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        ScreenAutomationOverlay.isAppInForeground = true
        // Clear gateway message notifications when user returns to app
        GatewayForegroundService.instance?.notificationHelper?.clearAll()
        // Clear direct Claude response notification
        NotificationManagerCompat.from(this).cancel(49999)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNotificationDeepLink(it) }
    }

    private fun handleNotificationDeepLink(intent: Intent) {
        // A question tap takes priority: an agent is blocked on it. Consume the extra
        // so a configuration change or a later onNewIntent cannot reopen the dialog.
        val questionId = intent.getStringExtra(GatewayNotificationHelper.EXTRA_QUESTION_ID)
        if (questionId != null) {
            intent.removeExtra(GatewayNotificationHelper.EXTRA_QUESTION_ID)
            openGatewayQuestion(questionId)
            return
        }
        val sessionKey = intent.getStringExtra(GatewayNotificationHelper.EXTRA_SESSION_KEY)
        if (sessionKey != null) {
            val canonicalSessionKey = GatewayManager.canonicalSessionKey(sessionKey)
            Log.d(TAG, "Notification deep-link to session: $sessionKey -> $canonicalSessionKey")
            GatewayForegroundService.instance?.notificationHelper?.clearSession(sessionKey)
            DebugReceiver.emitOpenRemoteSession(canonicalSessionKey)
        }
    }

    // Question id currently shown in AskUserQuestionActivity, so the result can be
    // routed back to the right gateway question.
    private var activeGatewayQuestionId: String? = null

    private val gatewayQuestionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val questionId = activeGatewayQuestionId
        activeGatewayQuestionId = null
        if (questionId == null) {
            Log.w(TAG, "Question result with no active question id")
            return@registerForActivityResult
        }
        val manager = GatewayForegroundService.instance?.gatewayManager
        if (manager == null) {
            Log.w(TAG, "Question result but gateway manager is gone; question $questionId left pending")
            return@registerForActivityResult
        }
        val answersJson = result.data?.getStringExtra(AskUserQuestionActivity.EXTRA_ANSWERS_JSON)
        if (result.resultCode == RESULT_OK && answersJson != null) {
            val answers = parseGatewayAnswers(questionId, answersJson, manager)
            if (answers == null) {
                Log.w(TAG, "Could not map answers for question $questionId")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val ok = manager.resolveQuestion(questionId, answers)
                Log.i(TAG, "Answered gateway question $questionId: accepted=$ok")
                GatewayForegroundService.instance?.notificationHelper?.clearQuestion(questionId)
            }
        } else {
            // Dismissed without answering. Cancel explicitly so the asking agent is
            // released now instead of waiting out its full timeout.
            lifecycleScope.launch {
                manager.cancelQuestion(questionId)
                GatewayForegroundService.instance?.notificationHelper?.clearQuestion(questionId)
            }
        }
    }

    /**
     * Map AskUserQuestionActivity's answer shape (question text -> comma-joined
     * labels) onto the gateway's shape (questionId -> string[]).
     */
    private fun parseGatewayAnswers(
        questionId: String,
        answersJson: String,
        manager: GatewayManager,
    ): Map<String, List<String>>? {
        val pending = manager.getPendingQuestion(questionId) ?: return null
        return try {
            val answered = JSONObject(answersJson)
            val questions = JSONArray(pending.questionsJson)
            val out = mutableMapOf<String, List<String>>()
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                val qid = q.optString("questionId", "").takeIf { it.isNotEmpty() } ?: continue
                val questionText = q.optString("question", "")
                val raw = answered.optString(questionText, "")
                if (raw.isEmpty()) continue
                val multiSelect = q.optBoolean("multiSelect", false)
                out[qid] = if (multiSelect) {
                    splitMultiSelect(raw, q.optJSONArray("options"))
                } else {
                    listOf(raw)
                }
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map gateway answers for $questionId", e)
            null
        }
    }

    /**
     * Recover the selected labels from AskUserQuestionActivity's ", "-joined string.
     *
     * A naive split on "," is lossy: an option label may itself contain a comma, and
     * splitting would report two selections that were never offered. Prefer matching
     * against the question's declared option labels, and fall back to a split only for
     * the remainder (a free-text "Other" value).
     */
    private fun splitMultiSelect(raw: String, options: JSONArray?): List<String> {
        val labels = mutableListOf<String>()
        if (options != null) {
            for (i in 0 until options.length()) {
                options.optJSONObject(i)?.optString("label", "")
                    ?.takeIf { it.isNotEmpty() }?.let { labels.add(it) }
            }
        }
        // Longest first so "Ship it now" is matched before "Ship it".
        labels.sortByDescending { it.length }
        val selected = mutableListOf<String>()
        var rest = raw
        for (label in labels) {
            val idx = rest.indexOf(label)
            if (idx < 0) continue
            selected.add(label)
            rest = (rest.substring(0, idx) + rest.substring(idx + label.length))
        }
        // Whatever is left over is a free-text answer, minus the join separators.
        rest.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { selected.add(it) }
        return if (selected.isNotEmpty()) selected else listOf(raw)
    }

    /**
     * Open the question dialog for one gateway question. Re-reads the question from
     * the gateway when it is not in the local cache (cold start after a process
     * death, or a tap on a notification older than this process).
     */
    private fun openGatewayQuestion(questionId: String) {
        val manager = GatewayForegroundService.instance?.gatewayManager
        if (manager == null) {
            Log.w(TAG, "Cannot open question $questionId: gateway not running")
            android.widget.Toast.makeText(
                this, "Gateway not connected", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        lifecycleScope.launch {
            val cached = manager.getPendingQuestion(questionId)
            val question = if (cached != null && !cached.isExpired()) {
                cached
            } else {
                manager.fetchQuestion(questionId)
            }
            GatewayForegroundService.instance?.notificationHelper?.clearQuestion(questionId)
            if (question == null) {
                Log.i(TAG, "Question $questionId is no longer pending")
                android.widget.Toast.makeText(
                    this@MainPagerActivity,
                    "That question is no longer waiting for an answer",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            activeGatewayQuestionId = questionId
            val intent = Intent(this@MainPagerActivity, AskUserQuestionActivity::class.java).apply {
                putExtra(AskUserQuestionActivity.EXTRA_QUESTIONS_JSON, question.questionsJson)
                putExtra(AskUserQuestionActivity.EXTRA_TOOL_ID, questionId)
                putExtra("IS_MCP_MODE", false)
            }
            try {
                gatewayQuestionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch question dialog for $questionId", e)
                activeGatewayQuestionId = null
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ScreenAutomationOverlay.isAppInForeground = false
    }
    
    override fun onDestroy() {
        // Unbind from TermuxService only when activity is destroyed
        unbindTermuxService()
        super.onDestroy()
    }

    private fun bindTermuxService() {
        if (!serviceBound) {
            Log.d(TAG, "Binding to TermuxService")
            val intent = Intent(this, TermuxService::class.java)
            // Start service first to ensure it exists
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    private fun unbindTermuxService() {
        if (serviceBound) {
            Log.d(TAG, "Unbinding from TermuxService")
            TerminalCommandBridge.unregister()
            unbindService(serviceConnection)
            serviceBound = false
            termuxService = null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_terminal -> {
                startActivity(Intent(this, TermuxActivity::class.java))
                true
            }
            R.id.action_camera -> {
                // Launch camera capture activity
                cameraLauncher.launch(Intent(this, CameraCaptureActivity::class.java))
                true
            }
            R.id.action_history -> {
                // Show conversation history dialog via ClaudeFragment
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (fragment is ClaudeFragment) {
                    fragment.showConversationHistoryDialog()
                }
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
