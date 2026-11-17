package com.dexterh4ck.equipos.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dexterh4ck.equipos.ui.MainActivity

/**
 * Notificaciones locales del agente.
 * Se usan para los comandos `notify` remotos (push in-band, funciona en Fire OS
 * que no tiene Google Play Services / FCM).
 */
object NotifyHelper {

    const val CHANNEL_COMMANDS = "dexter_commands"
    const val CHANNEL_KIOSK = "dexter_kiosk"
    const val NOTIF_ID_COMMAND = 2001
    const val NOTIF_ID_KIOSK = 2002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val commands = NotificationChannel(
            CHANNEL_COMMANDS, "Comandos remotos", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Notificaciones enviadas desde Dexterh4ck Control" }
        val kiosk = NotificationChannel(
            CHANNEL_KIOSK, "Modo kiosk", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Estado del modo kiosk" }
        nm.createNotificationChannels(listOf(commands, kiosk))
    }

    /**
     * Muestra una notificación heads-up (aparece sobre la pantalla del TV Box / Fire Stick).
     */
    fun showCommandNotification(context: Context, title: String, body: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMMANDS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        nm.notify(NOTIF_ID_COMMAND, notification)
    }

    fun showKioskNotification(context: Context, title: String, body: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_KIOSK)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_KIOSK, notification)
    }
}