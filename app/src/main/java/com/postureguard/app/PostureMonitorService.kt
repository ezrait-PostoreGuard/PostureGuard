package com.postureguard.app

import android.app.*
import android.content.Intent
import android.os.Build
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

    // יחס רוחב הפנים לרוחב התמונה (0.48 = קרוב מדי, כ-25-30 ס"מ). הקטן ל-0.42 לרגישות גבוהה יותר
    private val FACE_WIDTH_RATIO_THRESHOLD = 0.48f
    // זמן שהייה רצוף במרחק קרוב מדי לפני הקפצת התרעה (במילישניות)
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
            .setContentTitle("PostureGuard מנטר מרחק")
            .setContentText("יתריע רק אם תתקרב מדי למסך")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
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
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isBreakActive) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val frameWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) {
                imageProxy.width.toFloat()
            } else {
                imageProxy.height.toFloat()
            }

            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    val largestFace = faces.maxByOrNull { it.boundingBox.width() }
                    if (largestFace != null) {
                        val faceRatio = largestFace.boundingBox.width() / frameWidth
                        if (faceRatio >= FACE_WIDTH_RATIO_THRESHOLD) {
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
                .addOnCompleteListener {
                    imageProxy.close()
                }
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

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
