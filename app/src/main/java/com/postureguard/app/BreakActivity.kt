package com.postureguard.app

import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BreakActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_break)

        val tvTimer = findViewById<TextView>(R.id.tvCountdown)
        val tvStatus = findViewById<TextView>(R.id.tvStatusMessage)
        tvStatus.text = "אנא צפה בחפץ מרוחק עד שמסך זה ייעלם ויישמע צפצוף"

        object : CountDownTimer(25000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvTimer.text = (millisUntilFinished / 1000).toString()
            }
            override fun onFinish() {
                try {
                    ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
                } catch(e: Exception) {}
                finish()
            }
        }.start()
    }
    override fun onBackPressed() {}
}