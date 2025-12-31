package com.anthroid.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.anthroid.R
import com.anthroid.app.TermuxActivity
import com.anthroid.app.TermuxService
import com.anthroid.app.activities.SettingsActivity
import com.anthroid.claude.TerminalCommandBridge
import com.anthroid.terminal.TerminalSession

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
                    // Create a default session for Claude to use
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
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
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
