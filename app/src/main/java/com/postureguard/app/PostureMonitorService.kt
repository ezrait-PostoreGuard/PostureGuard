package com.postureguard.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PostureMonitorService : Service() {
    private var sessionSeconds = 0
    private var isBreakActive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        startUsageTimer()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "PostureGuardChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "ניטור יציבה פעיל", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PostureGuard פעיל ברקע")
            .setContentText("שומר על מרחק בריא וזמני הפסקה")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }

    private fun startUsageTimer() {
        Thread {
            while (true) {
                Thread.sleep(1000)
                if (!isBreakActive) {
                    sessionSeconds++
                    if (sessionSeconds >= 1200) {
                        triggerBreakScreen()
                        sessionSeconds = 0
                    }
                }
            }
        }.start()
    }

    private fun triggerBreakScreen() {
        isBreakActive = true
        val intent = Intent(this, BreakActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}