package com.postureguard.app

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class PostureMonitorService : LifecycleService() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var tooCloseStartTime: Long = 0L
    private var isBreakActive = false
    private val TRIGGER_DELAY_MS = 3000L

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        isBreakActive = false
        tooCloseStartTime = 0L
        startForegroundNotification()
        startFrontCameraAnalysis()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "PostureGuardChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "ניטור מרחק פעיל", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PostureGuard פעיל ברקע")
            .setContentText("ניטור אוטומטי בזמן שימוש באפליקציות")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // המרת המרחק שנבחר (10-40 ס"מ) ליחס רוחב פנים במצלמה קדמית סטנדרטית
    private fun getFaceRatioThreshold(): Float {
        val prefs = getSharedPreferences("PostureGuardPrefs", Context.MODE_PRIVATE)
        val distanceCm = prefs.getInt("target_distance_cm", 25).coerceIn(10, 40)
        // נוסחה אמפירית לעדשת סלפי: ב-10 ס"מ היחס ~0.85, ב-40 ס"מ היחס ~0.30
        return (12.0f / distanceCm).coerceIn(0.28f, 0.85f)
    }

    // בדיקה האם המסך דלוק, לא נעול, ופתוחה אפליקציה שאינה מסך הבית או PostureGuard
    private fun shouldMonitorNow(): Boolean {
        if (isBreakActive) return false

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) return false

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) return false

        val foregroundPkg = getForegroundPackageName() ?: return false
        if (foregroundPkg == packageName) return false

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultLauncher = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (foregroundPkg == defaultLauncher) return false

        return true
    }

    private fun getForegroundPackageName(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startFrontCameraAnalysis() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (shouldMonitorNow()) {
                            processFrame(imageProxy)
                        } else {
                            tooCloseStartTime = 0L
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalyzer)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val frameWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width.toFloat() else imageProxy.height.toFloat()
            val threshold = getFaceRatioThreshold()

            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    val largestFace = faces.maxByOrNull { it.boundingBox.width() }
                    if (largestFace != null) {
                        val faceRatio = largestFace.boundingBox.width() / frameWidth
                        if (faceRatio >= threshold) {
                            val now = System.currentTimeMillis()
                            if (tooCloseStartTime == 0L) {
                                tooCloseStartTime = now
                            } else if (now - tooCloseStartTime >= TRIGGER_DELAY_MS) {
                                triggerBreakScreen()
                            }
                        } else {
                            tooCloseStartTime = 0L
                        }
                    } else {
                        tooCloseStartTime = 0L
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun triggerBreakScreen() {
        isBreakActive = true
        tooCloseStartTime = 0L
        val intent = Intent(this, BreakActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }

    // הפעלה מחדש אוטומטית אם הילד מחליק את האפליקציה מרשימת היישומים האחרונים
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, PostureMonitorService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
