package com.postureguard.app

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class BreakActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        // חסימת כפתור חזור כדי שהילד לא יוכל לדלג על ההפסקה
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // לא מבצעים כלום בזמן ההפסקה
            }
        })

        val tvTimer = findViewById<TextView>(R.id.tvCountdown)
        val tvStatus = findViewById<TextView>(R.id.tvStatusMessage)
        tvStatus.text = "אנא צפה בחפץ מרוחק עד שמסך זה ייעלם ויישמע צפצוף"

        object : CountDownTimer(25000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvTimer.text = (millisUntilFinished / 1000).toString()
            }

            override fun onFinish() {
                try {
                    ToneGenerator(AudioManager.STREAM_ALARM, 100)
                        .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // איפוס והפעלה מחדש של שירות הניטור לקראת הסבב הבא
                val serviceIntent = Intent(this@BreakActivity, PostureMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                finish()
            }
        }.start()
    }
}
