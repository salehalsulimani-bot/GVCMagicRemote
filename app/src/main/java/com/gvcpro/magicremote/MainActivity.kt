package com.gvcpro.magicremote

import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.*
import android.os.*
import android.view.*
import android.view.inputmethod.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.hypot
import kotlin.math.pow

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var webOsClient: WebOsClient
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var isAirMouseEnabled = false

    private var lastTimestampNs = 0L
    private val baseSensitivity = 1150.0f
    private val deadzone = 0.015f
    private var filteredYaw = 0.0f
    private var filteredPitch = 0.0f

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
        val btnOkCircle = findViewById<MaterialCardView>(R.id.btnOkCircle)

        val tabMenu = findViewById<MaterialButton>(R.id.tabMenu)
        val tabNumpad = findViewById<MaterialButton>(R.id.tabNumpad)
        val tabKeyboard = findViewById<MaterialButton>(R.id.tabKeyboard)
        val viewMenuTab = findViewById<LinearLayout>(R.id.viewMenuTab)
        val viewNumpadTab = findViewById<LinearLayout>(R.id.viewNumpadTab)
        val viewKeyboardTab = findViewById<LinearLayout>(R.id.viewKeyboardTab)

        val etKeyboardInput = findViewById<EditText>(R.id.etKeyboardInput)
        val btnSendText = findViewById<Button>(R.id.btnSendText)

        val tvLogOutput = findViewById<TextView>(R.id.tvLogOutput)
        val logScrollView = findViewById<ScrollView>(R.id.logScrollView)
        val logContainer = findViewById<LinearLayout>(R.id.logContainer)
        val btnToggleLog = findViewById<Button>(R.id.btnToggleLog)
        val btnCopyLog = findViewById<Button>(R.id.btnCopyLog)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)

        switchSecure.isChecked = true

        webOsClient = WebOsClient(
            context = this,
            onStatusChanged = { status -> runOnUiThread { tvStatus.text = status } },
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

        etTvIp.setText(webOsClient.getLastIp())
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        btnConnect.setOnClickListener {
            val ip = etTvIp.text.toString().trim()
            if (ip.isNotEmpty()) webOsClient.connect(ip, switchSecure.isChecked)
        }

        fun selectTab(index: Int) {
            vibrateClick()
            val activeBg = ColorStateList.valueOf(Color.parseColor("#6D28D9"))
            val inactiveBg = ColorStateList.valueOf(Color.TRANSPARENT)
            val activeText = Color.WHITE
            val inactiveText = Color.parseColor("#9CA3AF")

            tabMenu.backgroundTintList = if (index == 0) activeBg else inactiveBg
            tabMenu.setTextColor(if (index == 0) activeText else inactiveText)
            tabNumpad.backgroundTintList = if (index == 1) activeBg else inactiveBg
            tabNumpad.setTextColor(if (index == 1) activeText else inactiveText)
            tabKeyboard.backgroundTintList = if (index == 2) activeBg else inactiveBg
            tabKeyboard.setTextColor(if (index == 2) activeText else inactiveText)

            viewMenuTab.visibility = if (index == 0) View.VISIBLE else View.GONE
            viewNumpadTab.visibility = if (index == 1) View.VISIBLE else View.GONE
            viewKeyboardTab.visibility = if (index == 2) View.VISIBLE else View.GONE

            if (index == 2) {
                etKeyboardInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etKeyboardInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        tabMenu.setOnClickListener { selectTab(0) }
        tabNumpad.setOnClickListener { selectTab(1) }
        tabKeyboard.setOnClickListener { selectTab(2) }

        val sendCurrentInputText = {
            val text = etKeyboardInput.text.toString()
            if (text.isNotEmpty()) {
                vibrateClick()
                webOsClient.sendText(text)
                etKeyboardInput.setText("")
            }
        }

        btnSendText.setOnClickListener { sendCurrentInputText() }
        etKeyboardInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendCurrentInputText(); true
            } else false
        }

        findViewById<Button>(R.id.btnKeyDelete).setOnClickListener { vibrateClick(); webOsClient.sendDeleteCharacters(1) }
        findViewById<Button>(R.id.btnKeyEnter).setOnClickListener { vibrateClick(); webOsClient.sendEnterKey() }

        mapOf(
            R.id.btnNum0 to "0", R.id.btnNum1 to "1", R.id.btnNum2 to "2",
            R.id.btnNum3 to "3", R.id.btnNum4 to "4", R.id.btnNum5 to "5",
            R.id.btnNum6 to "6", R.id.btnNum7 to "7", R.id.btnNum8 to "8",
            R.id.btnNum9 to "9", R.id.btnNumDash to "DASH", R.id.btnNumBack to "BACK"
        ).forEach { (id, key) ->
            findViewById<Button>(id).setOnClickListener { vibrateClick(); webOsClient.sendButton(key) }
        }
        switchAirMouse.setOnCheckedChangeListener { _, isChecked ->
            isAirMouseEnabled = isChecked
            webOsClient.isAirMouseDesired = isChecked
            if (isChecked) startAirMouse() else stopAirMouse()
        }

        val clickAction = View.OnClickListener {
            vibrateClick()
            if (isAirMouseEnabled) webOsClient.clickMouse() else webOsClient.sendButton("ENTER")
        }
        airMousePad.setOnClickListener(clickAction)
        btnOkCircle.setOnClickListener(clickAction)

        findViewById<Button>(R.id.btnUp).setOnClickListener { vibrateClick(); webOsClient.sendButton("UP") }
        findViewById<Button>(R.id.btnDown).setOnClickListener { vibrateClick(); webOsClient.sendButton("DOWN") }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { vibrateClick(); webOsClient.sendButton("LEFT") }
        findViewById<Button>(R.id.btnRight).setOnClickListener { vibrateClick(); webOsClient.sendButton("RIGHT") }

        findViewById<Button>(R.id.btnPower).setOnClickListener { webOsClient.sendSsapCommand("ssap://system/turnOff") }
        findViewById<Button>(R.id.btnInput).setOnClickListener { vibrateClick(); webOsClient.sendButton("INFO") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { vibrateClick(); webOsClient.sendButton("HOME") }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { vibrateClick(); webOsClient.sendButton("MENU") }
        findViewById<Button>(R.id.btnExit).setOnClickListener { vibrateClick(); webOsClient.sendButton("EXIT") }
        findViewById<Button>(R.id.btnBack).setOnClickListener { vibrateClick(); webOsClient.sendButton("BACK") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { vibrateClick(); webOsClient.toggleMute() }

        findViewById<Button>(R.id.btnVolUp).setOnClickListener { webOsClient.sendSsapCommand("ssap://audio/volumeUp") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { webOsClient.sendSsapCommand("ssap://audio/volumeDown") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { webOsClient.sendSsapCommand("ssap://tv/channelUp") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { webOsClient.sendSsapCommand("ssap://tv/channelDown") }

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
            clipboard.setPrimaryClip(ClipData.newPlainText("WebOS Log", tvLogOutput.text.toString()))
            Toast.makeText(this, "تم نسخ السجل", Toast.LENGTH_SHORT).show()
        }

        btnClearLog.setOnClickListener { tvLogOutput.text = "" }
    }

    private fun startAirMouse() {
        lastTimestampNs = 0L; filteredYaw = 0.0f; filteredPitch = 0.0f
        gyroscope?.let {
            try {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                webOsClient.startPointerLoop()
            } catch (e: Exception) {
                isAirMouseEnabled = false
                webOsClient.isAirMouseDesired = false
                findViewById<SwitchCompat>(R.id.switchAirMouse).isChecked = false
                val err = "AirMouse error: ${e.javaClass.simpleName}: ${e.message}"
                findViewById<TextView>(R.id.tvLogOutput).append("$err\n")
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopAirMouse() {
        sensorManager.unregisterListener(this)
        webOsClient.stopPointerLoop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isAirMouseEnabled || event?.sensor?.type != Sensor.TYPE_GYROSCOPE) return
        if (lastTimestampNs == 0L) { lastTimestampNs = event.timestamp; return }

        val dt = (event.timestamp - lastTimestampNs) * 1e-9f
        lastTimestampNs = event.timestamp
        if (dt <= 0f || dt > 0.1f) return

        val rawYaw = event.values[2] + (event.values[1] * 0.35f)
        val rawPitch = event.values[0]

        val alpha = 0.72f
        filteredYaw += alpha * (rawYaw - filteredYaw)
        filteredPitch += alpha * (rawPitch - filteredPitch)

        val speed = hypot(filteredYaw, filteredPitch)
        if (speed < deadzone) return

        val accelGain = (1.0f + (speed * 1.4f).pow(1.35f)).coerceAtMost(3.2f)
        webOsClient.accumulateMouseDelta(-filteredYaw * dt * baseSensitivity * accelGain, -filteredPitch * dt * baseSensitivity * accelGain)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibrateClick() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onPause() { super.onPause(); stopAirMouse() }
    override fun onResume() { super.onResume(); webOsClient.reconnectIfNeeded(); if (isAirMouseEnabled) startAirMouse() }
    override fun onDestroy() { super.onDestroy(); webOsClient.disconnect() }
}
