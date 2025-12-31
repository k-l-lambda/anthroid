package com.anthroid.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R
import com.anthroid.app.TermuxActivity
import com.anthroid.app.TermuxService
import com.anthroid.shared.activity.media.AppCompatActivityUtils
import com.anthroid.shared.theme.NightMode
import com.anthroid.terminal.TerminalSession
import java.io.File

/**
 * Activity for managing Claude-related components (packages).
 * Shows install status and allows installing/upgrading packages via Termux.
 */
class ComponentsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ComponentsActivity"
        private val PREFIX = "/data/data/com.anthroid/files/usr"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ComponentAdapter
    private var termuxService: TermuxService? = null
    private var serviceBound = false

    // Define the components to manage
    private val components = listOf(
        Component(
            id = "nodejs",
            name = "Node.js",
            description = "JavaScript runtime for Claude CLI",
            checkCommand = "node --version",
            installCommand = "pkg install nodejs -y",
            binaryPath = "$PREFIX/bin/node"
        ),
        Component(
            id = "claude-code",
            name = "Claude Code CLI",
            description = "Claude AI assistant CLI tool",
            checkCommand = "claude --version",
            installCommand = "npm install -g @anthropic-ai/claude-code",
            binaryPath = "$PREFIX/bin/claude"
        ),
        Component(
            id = "openssh",
            name = "OpenSSH",
            description = "SSH client and server",
            checkCommand = "ssh -V",
            installCommand = "pkg install openssh -y",
            binaryPath = "$PREFIX/bin/ssh"
        )
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TermuxService.LocalBinder
            termuxService = binder?.service
            Log.d(TAG, "TermuxService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            termuxService = null
            Log.d(TAG, "TermuxService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true)

        setContentView(R.layout.activity_components)

        AppCompatActivityUtils.setToolbar(this, com.anthroid.shared.R.id.toolbar)
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true)
        supportActionBar?.title = "Components"

        recyclerView = findViewById(R.id.components_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ComponentAdapter(components) { component ->
            installComponent(component)
        }
        recyclerView.adapter = adapter

        // Bind to TermuxService
        bindTermuxService()

        // Check component status
        checkComponentStatus()
    }

    override fun onDestroy() {
        unbindTermuxService()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun bindTermuxService() {
        if (!serviceBound) {
            val intent = Intent(this, TermuxService::class.java)
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            serviceBound = true
        }
    }

    private fun unbindTermuxService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            termuxService = null
        }
    }

    private fun checkComponentStatus() {
        Thread {
            components.forEach { component ->
                val installed = File(component.binaryPath).exists()
                component.status = if (installed) ComponentStatus.INSTALLED else ComponentStatus.NOT_INSTALLED

                runOnUiThread {
                    adapter.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun installComponent(component: Component) {
        // Update status to installing
        component.status = ComponentStatus.INSTALLING
        adapter.notifyDataSetChanged()

        // Open Termux and run the install command
        val intent = Intent(this, TermuxActivity::class.java).apply {
            putExtra("execute_command", component.installCommand)
        }
        startActivity(intent)

        // Also try to execute via service if available
        termuxService?.let { service ->
            val sessions = service.termuxSessions
            if (sessions.isNotEmpty()) {
                val session = sessions[0].terminalSession
                // Write command to terminal
                session.write("${component.installCommand}\n")
                Log.d(TAG, "Sent install command to terminal: ${component.installCommand}")
            }
        }
    }

    // Data classes
    data class Component(
        val id: String,
        val name: String,
        val description: String,
        val checkCommand: String,
        val installCommand: String,
        val binaryPath: String,
        var status: ComponentStatus = ComponentStatus.CHECKING,
        var version: String? = null
    )

    enum class ComponentStatus {
        CHECKING,
        NOT_INSTALLED,
        INSTALLED,
        INSTALLING,
        UPDATE_AVAILABLE
    }

    // RecyclerView Adapter
    inner class ComponentAdapter(
        private val components: List<Component>,
        private val onInstallClick: (Component) -> Unit
    ) : RecyclerView.Adapter<ComponentAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.component_name)
            val descriptionText: TextView = view.findViewById(R.id.component_description)
            val statusText: TextView = view.findViewById(R.id.component_status)
            val installButton: Button = view.findViewById(R.id.install_button)
            val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_component, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val component = components[position]

            holder.nameText.text = component.name
            holder.descriptionText.text = component.description

            when (component.status) {
                ComponentStatus.CHECKING -> {
                    holder.statusText.text = "Checking..."
                    holder.installButton.visibility = View.GONE
                    holder.progressBar.visibility = View.VISIBLE
                }
                ComponentStatus.NOT_INSTALLED -> {
                    holder.statusText.text = "Not installed"
                    holder.installButton.visibility = View.VISIBLE
                    holder.installButton.text = "Install"
                    holder.installButton.isEnabled = true
                    holder.progressBar.visibility = View.GONE
                }
                ComponentStatus.INSTALLED -> {
                    holder.statusText.text = "Installed" + (component.version?.let { " ($it)" } ?: "")
                    holder.installButton.visibility = View.VISIBLE
                    holder.installButton.text = "Reinstall"
                    holder.installButton.isEnabled = true
                    holder.progressBar.visibility = View.GONE
                }
                ComponentStatus.INSTALLING -> {
                    holder.statusText.text = "Installing..."
                    holder.installButton.visibility = View.GONE
                    holder.progressBar.visibility = View.VISIBLE
                }
                ComponentStatus.UPDATE_AVAILABLE -> {
                    holder.statusText.text = "Update available"
                    holder.installButton.visibility = View.VISIBLE
                    holder.installButton.text = "Update"
                    holder.installButton.isEnabled = true
                    holder.progressBar.visibility = View.GONE
                }
            }

            holder.installButton.setOnClickListener {
                onInstallClick(component)
            }
        }

        override fun getItemCount() = components.size
    }
}
