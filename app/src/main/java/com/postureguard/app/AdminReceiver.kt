package com.postureguard.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// While active, Android blocks uninstalling and "Force stop" for this app.
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "ביטול ההגנה דורש אישור הורה. ההורה יקבל התראה על כך."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("PostureGuardPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("parent_disabling", false)) {
            // Parent turned it off with the PIN - no alert
            prefs.edit().putBoolean("parent_disabling", false).apply()
            return
        }
        // Record the time so the parent sees it on the main screen
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        context.getSharedPreferences("PostureGuardPrefs", Context.MODE_PRIVATE)
            .edit().putString("tamper_log", time).apply()

        val channelId = "PostureGuardAlert"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "התראות הגנה", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("PostureGuard")
            .setContentText("הגנת ההסרה בוטלה ב-$time")
            .build()
        try { nm.notify(3, n) } catch (e: SecurityException) { e.printStackTrace() }
    }
}
