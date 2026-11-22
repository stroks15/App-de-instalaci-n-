package com.dexterh4ck.control.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.control.R
import com.dexterh4ck.control.data.InstalledApp

/** App list with persistent multi-selection, search and system/user filtering. */
class InstalledAppAdapter(
    apps: List<InstalledApp>,
    private val onOpenClick: (InstalledApp) -> Unit,
    private val onUninstallClick: (InstalledApp) -> Unit,
    private val onPinClick: (InstalledApp) -> Unit,
    private val onParentalClick: (InstalledApp) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit
) : RecyclerView.Adapter<InstalledAppAdapter.AppViewHolder>() {

    private var allApps = apps.toList()
    private var visibleApps = apps.toList()
    private val selectedPackages = linkedSetOf<String>()
    private var query = ""
    private var filter = "ALL"

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
        val tvVersion: TextView = view.findViewById(R.id.tvVersion)
        val checkBox: CheckBox = view.findViewById(R.id.cbSelectApp)
        val btnOpen: ImageButton = view.findViewById(R.id.btnOpenApp)
        val btnUninstall: ImageButton = view.findViewById(R.id.btnUninstallApp)
        val btnPin: ImageButton = view.findViewById(R.id.btnPinApp)
        val btnParental: ImageButton = view.findViewById(R.id.btnParentalApp)

        init {
            btnOpen.setOnClickListener { currentApp()?.let(onOpenClick) }
            btnUninstall.setOnClickListener { currentApp()?.let(onUninstallClick) }
            btnPin.setOnClickListener { currentApp()?.let(onPinClick) }
            btnParental.setOnClickListener { currentApp()?.let(onParentalClick) }
            checkBox.setOnCheckedChangeListener { _, checked ->
                currentApp()?.let { app ->
                    if (checked) selectedPackages.add(app.packageName)
                    else selectedPackages.remove(app.packageName)
                    itemView.setBackgroundColor(if (checked) Color.rgb(224, 244, 255) else Color.WHITE)
                    onSelectionChanged(selectedPackages.toSet())
                }
            }
        }

        fun currentApp(): InstalledApp? {
            val position = bindingAdapterPosition
            return if (position != RecyclerView.NO_POSITION && position in visibleApps.indices) visibleApps[position] else null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_installed_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = visibleApps[position]
        holder.tvName.text = app.appName
        holder.tvPackage.text = app.packageName
        holder.tvVersion.text = if (app.versionName.isNotEmpty()) "v${app.versionName}" else if (app.isSystemApp) "Sistema" else "Usuario"
        holder.ivIcon.setImageResource(android.R.drawable.ic_menu_info_details)
        holder.checkBox.setOnCheckedChangeListener(null)
        val selected = app.packageName in selectedPackages
        holder.checkBox.isChecked = selected
        holder.itemView.setBackgroundColor(if (selected) Color.rgb(224, 244, 255) else Color.WHITE)
        holder.checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
            holder.itemView.setBackgroundColor(if (checked) Color.rgb(224, 244, 255) else Color.WHITE)
            onSelectionChanged(selectedPackages.toSet())
        }
    }

    override fun getItemCount() = visibleApps.size

    fun filter(query: String, filter: String) {
        this.query = query.trim()
        this.filter = filter
        visibleApps = allApps.filter { app ->
            val matchesText = this.query.isBlank() ||
                app.appName.contains(this.query, ignoreCase = true) ||
                app.packageName.contains(this.query, ignoreCase = true)
            val matchesType = when (filter) {
                "SYSTEM" -> app.isSystemApp
                "USER" -> !app.isSystemApp
                else -> true
            }
            matchesText && matchesType
        }.sortedBy { it.appName.lowercase() }
        notifyDataSetChanged()
    }

    fun selectedPackages(): Set<String> = selectedPackages.toSet()

    fun clearSelection() {
        selectedPackages.clear()
        onSelectionChanged(emptySet())
        notifyDataSetChanged()
    }

    fun updateApps(newApps: List<InstalledApp>) {
        allApps = newApps.toList()
        selectedPackages.retainAll(newApps.map { it.packageName }.toSet())
        filter(query, filter)
    }
}
