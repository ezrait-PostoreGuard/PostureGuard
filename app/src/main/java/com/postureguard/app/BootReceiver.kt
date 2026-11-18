package com.postureguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("PostureGuardPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("parent_stopped", false)) return

        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ blocks starting a camera service at boot.
            // Show a notification; one tap opens the app and resumes monitoring.
            showResumeNotification(context)
            return
        }

        val serviceIntent = Intent(context, PostureMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            showResumeNotification(context)
        }
    }

    companion object {
    fun showResumeNotification(context: Context) {
        val channelId = "PostureGuardBoot"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "הפעלה מחדש", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openApp = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTO_START, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("PostureGuard")
            .setContentText("הקש כדי להפעיל מחדש את הניטור")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { nm.notify(2, n) } catch (e: SecurityException) { e.printStackTrace() }
    }
    }
}
