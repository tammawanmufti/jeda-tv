package com.sirkala.jedatv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object LockNotification {
    private const val CHANNEL = "tvlock"

    fun makeChannel(context: Context) {
        val m = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Jeda", NotificationManager.IMPORTANCE_MIN)
        m.createNotificationChannel(ch)
    }

    fun build(context: Context): Notification =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.jeda_symbol)
            .setContentTitle("Jeda")
            .setContentText(if (Prefs(context).english()) "App protection active" else "Perlindungan aplikasi aktif")
            .setOngoing(true)
            .build()
}
