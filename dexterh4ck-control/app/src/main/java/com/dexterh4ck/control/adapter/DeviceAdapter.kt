package com.dexterh4ck.control.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.control.R
import com.dexterh4ck.control.data.Device

/** Adapter para mostrar la lista de dispositivos en el RecyclerView. */
class DeviceAdapter(
    private var devices: List<Device>,
    private val onItemClick: (Device) -> Unit,
    private val onReconnect: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvId: TextView = view.findViewById(R.id.tvDeviceId)
        val tvStatus: TextView = view.findViewById(R.id.tvDeviceStatus)
        val statusIndicator: View = view.findViewById(R.id.viewStatusIndicator)
        val reconnect: Button = view.findViewById(R.id.btnReconnect)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position in devices.indices) {
                    onItemClick(devices[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.tvName.text = device.name
        val vpnIp = device.assigned_ip?.substringBefore("/")
        holder.tvId.text = "ID: ${device.device_id.take(12)}" + (vpnIp?.let { " · VPN $it" } ?: "")
        holder.reconnect.isEnabled = device.vpn_enabled && !vpnIp.isNullOrBlank()
        holder.reconnect.alpha = if (holder.reconnect.isEnabled) 1f else 0.45f
        holder.reconnect.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION && current in devices.indices) onReconnect(devices[current])
        }

        val statusText = when (device.status) {
            "online" -> "Online"
            "busy" -> "Ocupado"
            else -> "Offline"
        }
        holder.tvStatus.text = statusText

        val color = when (device.status) {
            "online" -> android.R.color.holo_green_dark
            "busy" -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        holder.statusIndicator.setBackgroundColor(holder.itemView.context.getColor(color))
        holder.tvStatus.setTextColor(holder.itemView.context.getColor(color))
    }

    override fun getItemCount() = devices.size

    fun updateDevices(newDevices: List<Device>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
