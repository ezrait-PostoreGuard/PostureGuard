package com.postureguard.app

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
    private val mainHandler = Handler(Looper.getMainLooper())

    // ניטור מרחק (חלונית צפה מעל האפליקציה)
    private var tooCloseStartTime: Long = 0L
    private var safeDistanceStartTime: Long = 0L
    private var isProximityOverlayShowing = false
    private var proximityOverlayView: View? = null
    private lateinit var windowManager: WindowManager

    private val PROXIMITY_TRIGGER_DELAY_MS = 2000L // 2 שניות קרבה להצגת האזהרה
    private val PROXIMITY_DISMISS_DELAY_MS = 800L  // 0.8 שניות התרחקות להעלמת האזהרה

    // טיימר 20 דקות (מסך הפסקה מלא ונעול)
    private var activeUsageSeconds = 0
    private var isLongBreakActive = false
    private var isUsageTimerRunning = false
    private val LONG_BREAK_INTERVAL_SECONDS = 1200 // 20 דקות

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        isLongBreakActive = false
        activeUsageSeconds = 0
        tooCloseStartTime = 0L
        safeDistanceStartTime = 0L
        hideProximityOverlay()

        startForegroundNotification()
        startFrontCameraAnalysis()
        if (!isUsageTimerRunning) {
            startUsageTimer()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "PostureGuardChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "ניטור מרחק והפסקות פעיל", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PostureGuard פעיל ברקע")
            .setContentText("מנטר מרחק מהמסך וזמני הפסקות")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun getFaceRatioThreshold(): Float {
        val prefs = getSharedPreferences("PostureGuardPrefs", Context.MODE_PRIVATE)
        val distanceCm = prefs.getInt("target_distance_cm", 25).coerceIn(10, 40)
        return (12.0f / distanceCm).coerceIn(0.28f, 0.85f)
    }

    private fun isExternalAppInForeground(): Boolean {
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

    private fun startUsageTimer() {
        isUsageTimerRunning = true
        Thread {
            while (isUsageTimerRunning) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }

                if (isLongBreakActive) continue

                if (isExternalAppInForeground()) {
                    activeUsageSeconds++
                    if (activeUsageSeconds >= LONG_BREAK_INTERVAL_SECONDS) {
                        activeUsageSeconds = 0
                        triggerLongBreakScreen()
                    }
                } else {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
                        activeUsageSeconds = 0
                    }
                }
            }
        }.start()
    }

    private fun triggerLongBreakScreen() {
        isLongBreakActive = true
        hideProximityOverlay()
        val intent = Intent(this, BreakActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
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
                        if (!isLongBreakActive && isExternalAppInForeground()) {
                            processFrame(imageProxy)
                        } else {
                            tooCloseStartTime = 0L
                            safeDistanceStartTime = 0L
                            hideProximityOverlay()
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
                    val now = System.currentTimeMillis()

                    if (largestFace != null) {
                        val faceRatio = largestFace.boundingBox.width() / frameWidth
                        if (faceRatio >= threshold) {
                            safeDistanceStartTime = 0L
                            if (!isProximityOverlayShowing) {
                                if (tooCloseStartTime == 0L) {
                                    tooCloseStartTime = now
                                } else if (now - tooCloseStartTime >= PROXIMITY_TRIGGER_DELAY_MS) {
                                    showProximityOverlay()
                                }
                            }
                        } else {
                            tooCloseStartTime = 0L
                            if (isProximityOverlayShowing) {
                                if (safeDistanceStartTime == 0L) {
                                    safeDistanceStartTime = now
                                } else if (now - safeDistanceStartTime >= PROXIMITY_DISMISS_DELAY_MS) {
                                    hideProximityOverlay()
                                }
                            }
                        }
                    } else {
                        tooCloseStartTime = 0L
                        if (isProximityOverlayShowing) {
                            if (safeDistanceStartTime == 0L) {
                                safeDistanceStartTime = now
                            } else if (now - safeDistanceStartTime >= PROXIMITY_DISMISS_DELAY_MS) {
                                hideProximityOverlay()
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun showProximityOverlay() {
        mainHandler.post {
            if (isProximityOverlayShowing || isLongBreakActive) return@post

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#D9111827"))
                setPadding(60, 60, 60, 60)
            }

            val iconText = TextView(this).apply {
                text = "👀"
                textSize = 54f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 24)
            }

            val titleText = TextView(this).apply {
                text = "קרוב מדי למסך!"
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }

            val descText = TextView(this).apply {
                text = "הרחק מעט את הטלפון מהפנים.\nהמסך יחזור לפעול אוטומטית ברגע שתתרחק."
                textSize = 18f
                setTextColor(Color.parseColor("#E5E7EB"))
                gravity = Gravity.CENTER
            }

            container.addView(iconText)
            container.addView(titleText)
            container.addView(descText)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(container, params)
                proximityOverlayView = container
                isProximityOverlayShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideProximityOverlay() {
        mainHandler.post {
            if (!isProximityOverlayShowing || proximityOverlayView == null) return@post
            try {
                windowManager.removeView(proximityOverlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                proximityOverlayView = null
                isProximityOverlayShowing = false
                safeDistanceStartTime = 0L
            }
        }
    }

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
        isUsageTimerRunning = false
        hideProximityOverlay()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
