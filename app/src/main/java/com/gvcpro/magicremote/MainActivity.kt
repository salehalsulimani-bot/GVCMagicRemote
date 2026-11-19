package com.gvcpro.magicremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var webOsClient: WebOsClient
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var isAirMouseEnabled = false

    private val sensitivity = 65.0f
    private val deadzone = 0.012f
    private var smoothX = 0.0f
    private var smoothY = 0.0f
    private val smoothingFactor = 0.45f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        val etTvIp = findViewById<EditText>(R.id.etTvIp)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val switchSecure = findViewById<SwitchCompat>(R.id.switchSecure)
        val switchAirMouse = findViewById<SwitchCompat>(R.id.switchAirMouse)
        val airMousePad = findViewById<MaterialCardView>(R.id.airMousePad)
        val tvLogOutput = findViewById<TextView>(R.id.tvLogOutput)
        val logScrollView = findViewById<ScrollView>(R.id.logScrollView)
        val logContainer = findViewById<LinearLayout>(R.id.logContainer)
        val btnToggleLog = findViewById<Button>(R.id.btnToggleLog)
        val btnCopyLog = findViewById<Button>(R.id.btnCopyLog)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)

        webOsClient = WebOsClient(
            context = this,
            onStatusChanged = { status ->
                runOnUiThread { tvStatus.text = status }
            },
            onLogMessage = { line ->
                runOnUiThread {
                    tvLogOutput.append("$line\n")
                    if (tvLogOutput.lineCount > 300) {
                        val t = tvLogOutput.text.toString()
                        val idx = t.indexOf('\n', t.length / 2)
                        tvLogOutput.text = if (idx != -1) t.substring(idx + 1) else t.substring(t.length / 2)
                    }
                    logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        )

        val savedIp = webOsClient.getLastIp()
        etTvIp.setText(savedIp)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        btnConnect.setOnClickListener {
            val ip = etTvIp.text.toString().trim()
            if (ip.isNotEmpty()) {
                webOsClient.connect(ip, switchSecure.isChecked)
            }
        }

        btnToggleLog.setOnClickListener {
            if (logContainer.visibility == View.GONE) {
                logContainer.visibility = View.VISIBLE
                btnToggleLog.text = "إخفاء السجل التقني ▴"
            } else {
                logContainer.visibility = View.GONE
                btnToggleLog.text = "عرض السجل التقني ▾"
            }
        }

        btnCopyLog.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WebOS Log", tvLogOutput.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "تم نسخ السجل", Toast.LENGTH_SHORT).show()
        }

        btnClearLog.setOnClickListener {
            tvLogOutput.text = ""
        }

        switchAirMouse.setOnCheckedChangeListener { _, isChecked ->
            isAirMouseEnabled = isChecked
            webOsClient.isAirMouseDesired = isChecked
            if (isChecked) startAirMouse() else stopAirMouse()
        }

        airMousePad.setOnClickListener {
            vibrateClick()
            webOsClient.clickMouse()
        }

        findViewById<Button>(R.id.btnUp).setOnClickListener { vibrateClick(); webOsClient.sendButton("UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { vibrateClick(); webOsClient.sendButton("DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { vibrateClick(); webOsClient.sendButton("LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { vibrateClick(); webOsClient.sendButton("RIGHT") }

        findViewById<Button>(R.id.btnPower).setOnClickListener { webOsClient.sendSsapCommand("ssap://system/turnOff") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { vibrateClick(); webOsClient.sendButton("HOME") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { vibrateClick(); webOsClient.sendButton("BACK") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { vibrateClick(); webOsClient.toggleMute() }
        findViewById<Button>(R.id.btnVolUp).setOnClickListener { webOsClient.sendSsapCommand("ssap://audio/volumeUp") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { webOsClient.sendSsapCommand("ssap://audio/volumeDown") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { webOsClient.sendSsapCommand("ssap://tv/channelUp") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { webOsClient.sendSsapCommand("ssap://tv/channelDown") }
    }

    private fun startAirMouse() {
        smoothX = 0.0f
        smoothY = 0.0f
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            webOsClient.startPointerLoop()
        }
    }

    private fun stopAirMouse() {
        sensorManager.unregisterListener(this)
        webOsClient.stopPointerLoop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isAirMouseEnabled || event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return

        val rawYaw = event.values[2]
        val rawPitch = event.values[0]

        smoothX = smoothX + smoothingFactor * (rawYaw - smoothX)
        smoothY = smoothY + smoothingFactor * (rawPitch - smoothY)

        val dx = if (abs(smoothX) > deadzone) -smoothX * sensitivity else 0.0f
        val dy = if (abs(smoothY) > deadzone) -smoothY * sensitivity else 0.0f

        if (dx != 0.0f || dy != 0.0f) {
            webOsClient.accumulateMouseDelta(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibrateClick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onPause() {
        super.onPause()
        stopAirMouse()
    }

    override fun onResume() {
        super.onResume()
        webOsClient.reconnectIfNeeded()
        if (isAirMouseEnabled) startAirMouse()
    }

    override fun onDestroy() {
        super.onDestroy()
        webOsClient.disconnect()
    }
}
