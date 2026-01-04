package com.anthroid.app.activities

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anthroid.R

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var isSelected: Boolean = false
)

class AppSelectorAdapter(
    private val onSelectionChanged: (List<String>) -> Unit
) : RecyclerView.Adapter<AppSelectorAdapter.ViewHolder>() {

    private var allApps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()
    private var searchQuery: String = ""

    fun setApps(apps: List<AppInfo>) {
        allApps = apps
        applyFilter()
    }

    fun setSelectedPackages(packages: List<String>) {
        val selectedSet = packages.toSet()
        allApps = allApps.map { it.copy(isSelected = selectedSet.contains(it.packageName)) }
        applyFilter()
    }

    fun filter(query: String) {
        searchQuery = query.lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        filteredApps = if (searchQuery.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(searchQuery) ||
                    it.packageName.lowercase().contains(searchQuery)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        allApps = allApps.map { it.copy(isSelected = true) }
        applyFilter()
        notifySelectionChanged()
    }

    fun clearAll() {
        allApps = allApps.map { it.copy(isSelected = false) }
        applyFilter()
        notifySelectionChanged()
    }

    fun getSelectedPackages(): List<String> {
        return allApps.filter { it.isSelected }.map { it.packageName }
    }

    fun getSelectedCount(): Int {
        return allApps.count { it.isSelected }
    }

    private fun notifySelectionChanged() {
        onSelectionChanged(getSelectedPackages())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_checkbox, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredApps[position])
    }

    override fun getItemCount(): Int = filteredApps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)
        private val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        private val appName: TextView = itemView.findViewById(R.id.app_name)
        private val appPackage: TextView = itemView.findViewById(R.id.app_package)

        fun bind(app: AppInfo) {
            appName.text = app.appName
            appPackage.text = app.packageName
            app.icon?.let { appIcon.setImageDrawable(it) }
            checkbox.isChecked = app.isSelected

            itemView.setOnClickListener {
                toggleSelection(app)
            }
        }

        private fun toggleSelection(app: AppInfo) {
            val index = allApps.indexOfFirst { it.packageName == app.packageName }
            if (index >= 0) {
                allApps = allApps.toMutableList().also {
                    it[index] = it[index].copy(isSelected = !it[index].isSelected)
                }
                applyFilter()
                notifySelectionChanged()
            }
        }
    }

    companion object {
        fun loadInstalledApps(pm: PackageManager): List<AppInfo> {
            return pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    // Filter to show user apps and some system apps
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        app.packageName.startsWith("com.android.chrome") ||
                        app.packageName.startsWith("com.google")
                }
                .map { app ->
                    AppInfo(
                        packageName = app.packageName,
                        appName = app.loadLabel(pm).toString(),
                        icon = try { app.loadIcon(pm) } catch (e: Exception) { null }
                    )
                }
                .sortedBy { it.appName.lowercase() }
        }
    }
}
