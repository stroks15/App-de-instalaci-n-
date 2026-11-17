package com.dexterh4ck.control.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.control.R
import com.dexterh4ck.control.data.InstalledApp

/**
 * Adapter para mostrar las apps instaladas en un equipo.
 */
class InstalledAppAdapter(
    private var apps: List<InstalledApp>,
    private val onOpenClick: (InstalledApp) -> Unit,
    private val onUninstallClick: (InstalledApp) -> Unit,
    private val onPinClick: (InstalledApp) -> Unit
) : RecyclerView.Adapter<InstalledAppAdapter.AppViewHolder>() {

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
        val tvVersion: TextView = view.findViewById(R.id.tvVersion)
        val btnOpen: ImageButton = view.findViewById(R.id.btnOpenApp)
        val btnUninstall: ImageButton = view.findViewById(R.id.btnUninstallApp)
        val btnPin: ImageButton = view.findViewById(R.id.btnPinApp)

        init {
            btnOpen.setOnClickListener { onOpenClick(apps[bindingAdapterPosition]) }
            btnUninstall.setOnClickListener { onUninstallClick(apps[bindingAdapterPosition]) }
            btnPin.setOnClickListener { onPinClick(apps[bindingAdapterPosition]) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_installed_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        holder.tvName.text = app.appName
        holder.tvPackage.text = app.packageName
        holder.tvVersion.text = if (app.versionName.isNotEmpty()) "v${app.versionName}" else "Sistema"
        holder.ivIcon.setImageResource(android.R.drawable.ic_menu_info_details)
    }

    override fun getItemCount() = apps.size

    fun updateApps(newApps: List<InstalledApp>) {
        apps = newApps
        notifyDataSetChanged()
    }
}