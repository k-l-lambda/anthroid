package com.anthroid.app.activities

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.vpn.models.ProxyServer
import com.anthroid.R

class ProxyServerAdapter(
    private val onItemClick: (ProxyServer) -> Unit,
    private val onActiveChange: (ProxyServer) -> Unit,
    private val onEnabledChange: (ProxyServer, Boolean) -> Unit,
    private val onEditClick: (ProxyServer) -> Unit,
    private val onDeleteClick: (ProxyServer) -> Unit,
    private val onStartClick: (ProxyServer) -> Unit
) : ListAdapter<ProxyServer, ProxyServerAdapter.ViewHolder>(ServerDiffCallback()) {

    private var activeServerId: String? = null

    fun setActiveServerId(serverId: String?) {
        activeServerId = serverId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_proxy_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val radioActive: RadioButton = itemView.findViewById(R.id.radio_active)
        private val serverName: TextView = itemView.findViewById(R.id.server_name)
        private val serverAddress: TextView = itemView.findViewById(R.id.server_address)
        private val serverType: TextView = itemView.findViewById(R.id.server_type)
        private val switchEnabled: SwitchCompat = itemView.findViewById(R.id.switch_enabled)
        private val btnMenu: ImageButton = itemView.findViewById(R.id.btn_menu)

        fun bind(server: ProxyServer) {
            serverName.text = server.name
            serverAddress.text = "${server.host}:${server.port}"
            serverType.text = server.getDisplayInfo()

            radioActive.isChecked = server.id == activeServerId
            switchEnabled.isChecked = server.enabled

            // Dim disabled servers
            itemView.alpha = if (server.enabled) 1.0f else 0.5f

            itemView.setOnClickListener {
                onItemClick(server)
            }

            radioActive.setOnClickListener {
                if (server.enabled) {
                    onActiveChange(server)
                }
            }

            switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != server.enabled) {
                    onEnabledChange(server, isChecked)
                }
            }

            btnMenu.setOnClickListener { view ->
                showPopupMenu(view, server)
            }
        }

        private fun showPopupMenu(anchor: View, server: ProxyServer) {
            PopupMenu(anchor.context, anchor).apply {
                menu.add(0, 1, 0, "Start VPN")
                menu.add(0, 2, 0, "Edit")
                menu.add(0, 3, 0, "Delete")

                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            onStartClick(server)
                            true
                        }
                        2 -> {
                            onEditClick(server)
                            true
                        }
                        3 -> {
                            onDeleteClick(server)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
    }

    private class ServerDiffCallback : DiffUtil.ItemCallback<ProxyServer>() {
        override fun areItemsTheSame(oldItem: ProxyServer, newItem: ProxyServer): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ProxyServer, newItem: ProxyServer): Boolean {
            return oldItem == newItem
        }
    }
}
