package com.anthroid.app.activities

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.vpn.ProxyConfigManager
import com.anthroid.vpn.ProxyVpnService
import com.anthroid.vpn.models.ProxyConfig
import com.anthroid.vpn.models.ProxyServer
import com.anthroid.shared.activity.media.AppCompatActivityUtils
import com.anthroid.shared.theme.NightMode
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.anthroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tun.proxy.service.Tun2HttpVpnService

class ProxySettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProxySettingsActivity"
    }

    private lateinit var configManager: ProxyConfigManager
    private lateinit var adapter: ProxyServerAdapter

    // Views
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var btnStop: Button
    private lateinit var btnRestart: Button
    private lateinit var cardAppList: CardView
    private lateinit var appListSummary: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddServer: FloatingActionButton

    private var currentConfig: ProxyConfig = ProxyConfig.empty()
    private var pendingServerToStart: ProxyServer? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingServerToStart?.let { startVpnWithServer(it) }
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingServerToStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true)

        setContentView(R.layout.activity_proxy_settings)

        AppCompatActivityUtils.setToolbar(this, com.anthroid.shared.R.id.toolbar)
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true)
        supportActionBar?.title = "Proxy Settings"

        configManager = ProxyConfigManager.getInstance(this)

        initViews()
        setupRecyclerView()
        setupListeners()
        loadConfig()
    }

    override fun onResume() {
        super.onResume()
        updateVpnStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun initViews() {
        statusIndicator = findViewById(R.id.status_indicator)
        statusText = findViewById(R.id.status_text)
        statusDetail = findViewById(R.id.status_detail)
        btnStop = findViewById(R.id.btn_stop)
        btnRestart = findViewById(R.id.btn_restart)
        cardAppList = findViewById(R.id.card_app_list)
        appListSummary = findViewById(R.id.app_list_summary)
        recyclerView = findViewById(R.id.proxy_servers_recycler_view)
        fabAddServer = findViewById(R.id.fab_add_server)
    }

    private fun setupRecyclerView() {
        adapter = ProxyServerAdapter(
            onItemClick = { server -> showServerDialog(server) },
            onActiveChange = { server -> setActiveServer(server) },
            onEnabledChange = { server, enabled -> toggleServerEnabled(server, enabled) },
            onEditClick = { server -> showServerDialog(server) },
            onDeleteClick = { server -> confirmDeleteServer(server) },
            onStartClick = { server -> requestStartVpn(server) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        fabAddServer.setOnClickListener {
            showServerDialog(null)
        }

        cardAppList.setOnClickListener {
            showAppSelectorDialog()
        }

        btnStop.setOnClickListener {
            stopAllVpn()
        }

        btnRestart.setOnClickListener {
            currentConfig.getActiveServer()?.let { startVpnWithServer(it) }
        }
    }

    private fun loadConfig() {
        lifecycleScope.launch {
            currentConfig = configManager.loadConfig()
            updateUI()
        }
    }

    private fun updateUI() {
        adapter.submitList(currentConfig.servers.toList())
        adapter.setActiveServerId(currentConfig.activeServerId)
        updateAppListSummary()
        updateVpnStatus()
    }

    private fun updateAppListSummary() {
        val count = currentConfig.globalAppList.size
        appListSummary.text = if (count == 0) {
            "No apps selected"
        } else {
            "$count app${if (count > 1) "s" else ""} selected"
        }
    }

    private fun updateVpnStatus() {
        val socks5Running = ProxyVpnService.isRunning()
        val httpRunning = Tun2HttpVpnService.isRunning()

        if (socks5Running || httpRunning) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_running)
            statusText.text = "Running"
            btnStop.visibility = View.VISIBLE
            btnRestart.visibility = View.VISIBLE
            statusDetail.visibility = View.VISIBLE

            val info = if (socks5Running) {
                ProxyVpnService.getProxyInfo()
            } else {
                Tun2HttpVpnService.getProxyInfo()
            }
            statusDetail.text = info
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_stopped)
            statusText.text = "Stopped"
            btnStop.visibility = View.GONE
            btnRestart.visibility = View.GONE
            statusDetail.visibility = View.GONE
        }
    }

    private fun setActiveServer(server: ProxyServer) {
        lifecycleScope.launch {
            currentConfig = configManager.setActiveServer(server.id)
            updateUI()
        }
    }

    private fun toggleServerEnabled(server: ProxyServer, enabled: Boolean) {
        lifecycleScope.launch {
            val updatedServer = server.copy(enabled = enabled)
            currentConfig = configManager.updateServer(updatedServer)
            updateUI()
        }
    }

    private fun confirmDeleteServer(server: ProxyServer) {
        AlertDialog.Builder(this)
            .setTitle("Delete Server")
            .setMessage("Delete '${server.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    currentConfig = configManager.deleteServer(server.id)
                    updateUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestStartVpn(server: ProxyServer) {
        if (currentConfig.globalAppList.isEmpty()) {
            Toast.makeText(this, "Please select apps first", Toast.LENGTH_SHORT).show()
            return
        }

        // Check VPN permission
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingServerToStart = server
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnWithServer(server)
        }
    }

    private fun startVpnWithServer(server: ProxyServer) {
        // Stop any running VPN first
        stopAllVpn()

        val apps = ArrayList(currentConfig.globalAppList)

        when (server.type) {
            ProxyServer.ProxyType.SOCKS5 -> {
                val intent = Intent(this, ProxyVpnService::class.java).apply {
                    action = ProxyVpnService.ACTION_START
                    putExtra(ProxyVpnService.EXTRA_PROXY_HOST, server.host)
                    putExtra(ProxyVpnService.EXTRA_PROXY_PORT, server.port)
                    putStringArrayListExtra(ProxyVpnService.EXTRA_TARGET_APPS, apps)
                }
                startService(intent)
            }
            ProxyServer.ProxyType.HTTP -> {
                val intent = Intent(this, Tun2HttpVpnService::class.java).apply {
                    action = Tun2HttpVpnService.ACTION_START
                    putExtra(Tun2HttpVpnService.EXTRA_PROXY_HOST, server.host)
                    putExtra(Tun2HttpVpnService.EXTRA_PROXY_PORT, server.port)
                    putExtra(Tun2HttpVpnService.EXTRA_PROXY_USER, server.username)
                    putExtra(Tun2HttpVpnService.EXTRA_PROXY_PASS, server.password)
                    putStringArrayListExtra(Tun2HttpVpnService.EXTRA_TARGET_APPS, apps)
                }
                startService(intent)
            }
        }

        // Set as active server
        lifecycleScope.launch {
            currentConfig = configManager.setActiveServer(server.id)
            updateUI()
        }

        Toast.makeText(this, "Starting VPN with ${server.name}", Toast.LENGTH_SHORT).show()

        // Update status after a short delay
        recyclerView.postDelayed({ updateVpnStatus() }, 500)
    }

    private fun stopAllVpn() {
        if (ProxyVpnService.isRunning()) {
            val intent = Intent(this, ProxyVpnService::class.java).apply {
                action = ProxyVpnService.ACTION_STOP
            }
            startService(intent)
        }
        if (Tun2HttpVpnService.isRunning()) {
            val intent = Intent(this, Tun2HttpVpnService::class.java).apply {
                action = Tun2HttpVpnService.ACTION_STOP
            }
            startService(intent)
        }
        recyclerView.postDelayed({ updateVpnStatus() }, 500)
    }

    private fun showServerDialog(server: ProxyServer?) {
        val isEdit = server != null
        val dialogView = layoutInflater.inflate(R.layout.dialog_proxy_server, null)

        val editName = dialogView.findViewById<TextInputEditText>(R.id.edit_name)
        val editHost = dialogView.findViewById<TextInputEditText>(R.id.edit_host)
        val editPort = dialogView.findViewById<TextInputEditText>(R.id.edit_port)
        val radioGroupType = dialogView.findViewById<RadioGroup>(R.id.radio_group_type)
        val radioSocks5 = dialogView.findViewById<RadioButton>(R.id.radio_socks5)
        val radioHttp = dialogView.findViewById<RadioButton>(R.id.radio_http)
        val editUsername = dialogView.findViewById<TextInputEditText>(R.id.edit_username)
        val editPassword = dialogView.findViewById<TextInputEditText>(R.id.edit_password)
        val checkboxEnabled = dialogView.findViewById<CheckBox>(R.id.checkbox_enabled)

        if (isEdit) {
            editName.setText(server!!.name)
            editHost.setText(server.host)
            editPort.setText(server.port.toString())
            when (server.type) {
                ProxyServer.ProxyType.SOCKS5 -> radioSocks5.isChecked = true
                ProxyServer.ProxyType.HTTP -> radioHttp.isChecked = true
            }
            editUsername.setText(server.username)
            editPassword.setText(server.password)
            checkboxEnabled.isChecked = server.enabled
        } else {
            radioSocks5.isChecked = true
            checkboxEnabled.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "Edit Server" else "Add Server")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = editName.text?.toString()?.trim() ?: ""
                val host = editHost.text?.toString()?.trim() ?: ""
                val portStr = editPort.text?.toString()?.trim() ?: ""
                val port = portStr.toIntOrNull() ?: 0
                val type = if (radioHttp.isChecked) ProxyServer.ProxyType.HTTP else ProxyServer.ProxyType.SOCKS5
                val username = editUsername.text?.toString() ?: ""
                val password = editPassword.text?.toString() ?: ""
                val enabled = checkboxEnabled.isChecked

                if (name.isEmpty() || host.isEmpty() || port <= 0) {
                    Toast.makeText(this, "Please fill required fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newServer = ProxyServer(
                    id = server?.id ?: configManager.generateServerId(),
                    name = name,
                    host = host,
                    port = port,
                    type = type,
                    username = username,
                    password = password,
                    enabled = enabled
                )

                lifecycleScope.launch {
                    currentConfig = if (isEdit) {
                        configManager.updateServer(newServer)
                    } else {
                        configManager.addServer(newServer)
                    }
                    updateUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppSelectorDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_selector, null)
        val recyclerViewApps = dialogView.findViewById<RecyclerView>(R.id.app_list)
        val editSearch = dialogView.findViewById<EditText>(R.id.edit_search)
        val btnSelectAll = dialogView.findViewById<Button>(R.id.btn_select_all)
        val btnClearAll = dialogView.findViewById<Button>(R.id.btn_clear_all)
        val textCount = dialogView.findViewById<TextView>(R.id.text_count)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)

        val appAdapter = AppSelectorAdapter { selected ->
            textCount.text = "${selected.size} apps selected"
        }

        recyclerViewApps.layoutManager = LinearLayoutManager(this)
        recyclerViewApps.adapter = appAdapter

        btnSelectAll.setOnClickListener { appAdapter.selectAll() }
        btnClearAll.setOnClickListener { appAdapter.clearAll() }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                appAdapter.filter(s?.toString() ?: "")
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val selected = appAdapter.getSelectedPackages()
                lifecycleScope.launch {
                    currentConfig = configManager.updateGlobalAppList(selected)
                    updateAppListSummary()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Load apps in background
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppSelectorAdapter.loadInstalledApps(packageManager)
            }
            progressBar.visibility = View.GONE
            appAdapter.setApps(apps)
            appAdapter.setSelectedPackages(currentConfig.globalAppList)
            textCount.text = "${currentConfig.globalAppList.size} apps selected"
        }
    }
}
