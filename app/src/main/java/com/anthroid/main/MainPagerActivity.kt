package com.anthroid.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
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
                    Log.d(TAG, "No terminal sessions, creating one")
                    // Sessions will be created when user opens terminal
                    // For now, bridge is registered but may not have active session
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

        // Load ClaudeFragment if not already present
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ClaudeFragment.newInstance())
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to TermuxService
        bindTermuxService()
    }

    override fun onStop() {
        super.onStop()
        // Unbind from TermuxService
        unbindTermuxService()
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
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
