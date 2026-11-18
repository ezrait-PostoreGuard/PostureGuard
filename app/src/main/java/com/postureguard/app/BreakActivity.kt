package com.postureguard.app

import android.app.ActivityManager
import android.content.Context
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

    private var isTimerFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        // חסימת כפתור חזור בזמן הפסקת ה-20 דקות
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        val tvTimer = findViewById<TextView>(R.id.tvCountdown)
        val tvStatus = findViewById<TextView>(R.id.tvStatusMessage)
        tvStatus.text = "עברו 20 דקות שימוש רצוף.\nאנא צפה בחפץ מרוחק עד שמסך זה ייעלם ויישמע צפצוף"

        object : CountDownTimer(25000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvTimer.text = (millisUntilFinished / 1000).toString()
            }

            override fun onFinish() {
                isTimerFinished = true
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
                    android.os.Handler(mainLooper).postDelayed({ tone.release() }, 1000)
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

    // מניעת מזעור מסך ההפסקה לרקע בלחיצה על כפתור הבית או יישומים אחרונים
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isTimerFinished) {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.moveTaskToFront(taskId, 0)
            val bringBackIntent = Intent(this, BreakActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(bringBackIntent)
        }
    }
}
