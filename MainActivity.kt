package com.postureguard.app

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }

    private var isParentUnlocked = false
    private lateinit var seekBar: SeekBar
    private lateinit var tvDistance: TextView
    private lateinit var btnToggleLock: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("PostureGuardPrefs", Context.MODE_PRIVATE)
        val savedDistance = prefs.getInt("target_distance_cm", 25)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "PostureGuard - הגדרות הורה"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        tvDistance = TextView(this).apply {
            text = "מרחק התרעה מינימלי: $savedDistance ס\"מ"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }

        seekBar = SeekBar(this).apply {
            max = 30 // טווח 0 עד 30, נוסיף 10 לקבלת 10-40 ס"מ
            progress = savedDistance - 10
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val cm = progress + 10
                    tvDistance.text = "מרחק התרעה מינימלי: $cm ס\"מ"
                    prefs.edit().putInt("target_distance_cm", cm).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        btnToggleLock = Button(this).apply {
            text = "🔓 פתח נעילת הורה"
            setOnClickListener {
                if (isParentUnlocked) {
                    lockSettings()
                } else {
                    val pin = prefs.getString("parent_pin", null)
                    if (pin == null) promptInitialPin(prefs) else promptForPin(pin)
                }
            }
        }

        val btnChangePin = Button(this).apply {
            text = "שנה קוד PIN להורה"
            setOnClickListener {
                if (!isParentUnlocked) {
                    Toast.makeText(this@MainActivity, "יש לפתוח נעילת הורה קודם", Toast.LENGTH_SHORT).show()
                } else {
                    promptChangePin(prefs)
                }
            }
        }

        btnStartService = Button(this).apply {
            text = "הפעל שירות ניטור"
            setOnClickListener { checkPermissionsAndStart() }
        }

        btnStopService = Button(this).apply {
            text = "עצור שירות (דורש קוד הורה)"
            isEnabled = false
            setOnClickListener {
                stopService(Intent(this@MainActivity, PostureMonitorService::class.java))
                Toast.makeText(this@MainActivity, "השירות הופסק", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(tvDistance)
        layout.addView(seekBar)
        layout.addView(btnToggleLock)
        layout.addView(btnChangePin)
        layout.addView(btnStartService)
        layout.addView(btnStopService)

        setContentView(layout)

        // First launch: parent must choose a PIN (no shared default like 1234)
        if (!prefs.contains("parent_pin")) {
            promptInitialPin(prefs)
        } else if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            checkPermissionsAndStart()
        }
    }

    private fun promptInitialPin(prefs: android.content.SharedPreferences) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "בחר קוד הורה (לפחות 4 ספרות)"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("הגדרת קוד הורה")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("שמור", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length >= 4) {
                    prefs.edit().putString("parent_pin", pin).apply()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "הקוד חייב להכיל לפחות 4 ספרות", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun promptForPin(correctPin: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("הזן קוד הורה")
            .setView(input)
            .setPositiveButton("אישור") { _, _ ->
                if (input.text.toString() == correctPin) {
                    isParentUnlocked = true
                    seekBar.isEnabled = true
                    btnStopService.isEnabled = true
                    btnToggleLock.text = "🔒 נעל הגדרות"
                } else {
                    Toast.makeText(this, "קוד שגוי", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun promptChangePin(prefs: android.content.SharedPreferences) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "הזן קוד חדש (לפחות 4 ספרות)"
        }
        AlertDialog.Builder(this)
            .setTitle("שינוי קוד הורה")
            .setView(input)
            .setPositiveButton("שמור") { _, _ ->
                val newPin = input.text.toString()
                if (newPin.length >= 4) {
                    prefs.edit().putString("parent_pin", newPin).apply()
                    Toast.makeText(this, "הקוד עודכן בהצלחה", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "הקוד חייב להכיל לפחות 4 ספרות", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun lockSettings() {
        isParentUnlocked = false
        seekBar.isEnabled = false
        btnStopService.isEnabled = false
        btnToggleLock.text = "🔓 פתח נעילת הורה"
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "אשר הצגה מעל אפליקציות אחרות", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!hasUsageStatsPermission()) {
            Toast.makeText(this, "אשר גישה לנתוני שימוש עבור PostureGuard", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        val serviceIntent = Intent(this, PostureMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        lockSettings()
        Toast.makeText(this, "השירות הופעל וההגדרות ננעלו", Toast.LENGTH_SHORT).show()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
