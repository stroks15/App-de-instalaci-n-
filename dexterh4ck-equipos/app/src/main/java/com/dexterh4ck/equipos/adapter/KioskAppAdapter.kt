package com.dexterh4ck.equipos.adapter

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.equipos.R

/**
 * Adaptador de la cuadrícula de apps permitidas en modo kiosk (TV-friendly).
 */
class KioskAppAdapter(
    private var apps: List<Pair<String, String>>, // packageName a appName
    private val onLaunch: (String) -> Unit
) : RecyclerView.Adapter<KioskAppAdapter.KioskViewHolder>() {

    inner class KioskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivKioskAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvKioskAppName)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onLaunch(apps[position].first)
                }
            }
            // Escala al enfocar (mandos D-pad)
            view.setOnFocusChangeListener { _, hasFocus ->
                view.animate().cancel()
                view.animate()
                    .scaleX(if (hasFocus) 1.12f else 1f)
                    .scaleY(if (hasFocus) 1.12f else 1f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KioskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_kiosk_app, parent, false)
        return KioskViewHolder(view)
    }

    override fun onBindViewHolder(holder: KioskViewHolder, position: Int) {
        val (packageName, appName) = apps[position]
        holder.tvName.text = appName
        try {
            val pm = holder.itemView.context.packageManager
            val icon = pm.getApplicationIcon(packageName)
            holder.ivIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }

    override fun getItemCount() = apps.size

    fun updateApps(newApps: List<Pair<String, String>>) {
        apps = newApps
        notifyDataSetChanged()
    }
}