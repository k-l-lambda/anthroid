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
import android.widget.Toast

/**
 * Activity for managing Claude-related components (packages).
 * Shows install status and allows installing/upgrading packages via Termux.
 */
class ComponentsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ComponentsActivity"
        private val PREFIX = "/data/data/com.anthroid/files/usr"
        private val FILES_DIR = "/data/data/com.anthroid/files"

        /**
         * Generate install command that downloads .deb and extracts binary manually.
         * This bypasses broken install scripts that have hardcoded com.termux paths.
         */
        fun makeInstallCommand(pkgName: String, binaryName: String = pkgName): String {
            return "cd ~ && apt-get download $pkgName && " +
                "mkdir -p $PREFIX/tmp/extract_$pkgName && " +
                "dpkg-deb -x `${pkgName}_*.deb $PREFIX/tmp/extract_$pkgName && " +
                "cp $PREFIX/tmp/extract_$pkgName/data/data/com.termux/files/usr/bin/$binaryName $PREFIX/bin/ && " +
                "chmod 755 $PREFIX/bin/$binaryName && " +
                "rm -rf $PREFIX/tmp/extract_$pkgName `${pkgName}_*.deb && " +
                "echo 'Installed $binaryName successfully'"
        }
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
            preInstalled = true,
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
        ),
        Component(
            id = "git",
            name = "Git",
            description = "Version control system",
            checkCommand = "git --version",
            installCommand = "pkg install git -y",
            binaryPath = "$PREFIX/bin/git"
        ),
        Component(
            id = "gh",
            name = "GitHub CLI",
            description = "GitHub command line tool",
            checkCommand = "gh --version",
            installCommand = makeInstallCommand("gh"),
            binaryPath = "$PREFIX/bin/gh"
        ),
        Component(
            id = "sshpass",
            name = "sshpass",
            description = "Non-interactive SSH password authentication",
            checkCommand = "sshpass -V",
            installCommand = makeInstallCommand("sshpass"),
            binaryPath = "$PREFIX/bin/sshpass"
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
        // Check if bootstrap is extracted
        val prefixDir = File(PREFIX)
        if (!prefixDir.exists() || (prefixDir.listFiles()?.size ?: 0) < 5) {
            runOnUiThread {
                Toast.makeText(this, "Bootstrap not yet installed. Please open Terminal first.", Toast.LENGTH_LONG).show()
            }
            // Set all components to NOT_INSTALLED and return
            components.forEach { it.status = ComponentStatus.NOT_INSTALLED }
            runOnUiThread { adapter.notifyDataSetChanged() }
            return
        }
        
        Thread {
            components.forEach { component ->
                val installed = File(component.binaryPath).exists()
                if (installed) {
                    // Try to get version by running the check command
                    val version = getComponentVersion(component)
                    component.version = version
                    component.status = ComponentStatus.INSTALLED
                } else {
                    component.status = ComponentStatus.NOT_INSTALLED
                    component.version = null
                }

                runOnUiThread {
                    adapter.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun getComponentVersion(component: Component): String? {
        return try {
            val env = arrayOf(
                "PATH=$PREFIX/bin",
                "LD_LIBRARY_PATH=$PREFIX/lib",
                "HOME=/data/data/com.anthroid/files/home"
            )
            val process = Runtime.getRuntime().exec(
                arrayOf("$PREFIX/bin/bash", "-c", component.checkCommand),
                env
            )
            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            process.waitFor()

            // Parse version from output - extract version number pattern
            val combinedOutput = if (output.isNotEmpty()) output else errorOutput
            parseVersion(combinedOutput, component.id)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version for ${component.id}: ${e.message}")
            null
        }
    }

    private fun parseVersion(output: String, componentId: String): String? {
        if (output.isBlank()) return null

        return when (componentId) {
            "nodejs" -> {
                // "v22.12.0" -> "22.12.0"
                output.trim().removePrefix("v")
            }
            "claude-code" -> {
                // "2.0.76 (Claude Code)" -> "2.0.76"
                output.split(" ").firstOrNull()
            }
            "openssh" -> {
                // "OpenSSH_10.2p1, OpenSSL 3.6.0" -> "10.2p1"
                Regex("OpenSSH_([\\d.p]+)").find(output)?.groupValues?.get(1)
            }
            "git" -> {
                // "git version 2.48.1" -> "2.48.1"
                Regex("git version ([\\d.]+)").find(output)?.groupValues?.get(1)
            }
            "gh" -> {
                // "gh version 2.65.0" -> "2.65.0"
                Regex("gh version ([\\d.]+)").find(output)?.groupValues?.get(1)
            }
            "sshpass" -> {
                // "sshpass 1.09" -> "1.09"
                Regex("sshpass ([\\d.]+)").find(output)?.groupValues?.get(1)
            }
            else -> output.trim().take(20)
        }
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
        val preInstalled: Boolean = false,  // Pre-installed components cannot be reinstalled via UI
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
                    holder.statusText.text = "Installed" + (component.version?.let { " ($it)" } ?: "") + (if (component.preInstalled) " (bundled)" else "")
                    holder.installButton.visibility = if (component.preInstalled) View.GONE else View.VISIBLE
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
