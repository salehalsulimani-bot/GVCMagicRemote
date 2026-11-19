package com.gvcpro.magicremote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.truncate

class WebOsClient(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onLogMessage: (String) -> Unit
) {
    companion object {
        private const val TAG = "WebOS_Client"
    }

    private val client: OkHttpClient = createTrustAllOkHttpClient()
    private var commandSocket: WebSocket? = null

    @Volatile private var pointerSocket: WebSocket? = null
    @Volatile private var isPointerLoopRunning = false
    @Volatile var isAirMouseDesired = false
    @Volatile private var commandSession = 0
    @Volatile private var pointerSession = 0

    private var msgId = 1
    private var isMuted = false
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("webos_prefs", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val pointerLock = Any()
    private var pendingDx = 0.0f
    private var pendingDy = 0.0f

    private val pointerSenderRunnable = object : Runnable {
        override fun run() {
            if (!isPointerLoopRunning) return

            var sendDx = 0
            var sendDy = 0

            synchronized(pointerLock) {
                sendDx = truncate(pendingDx).toInt()
                sendDy = truncate(pendingDy).toInt()
                if (sendDx != 0 || sendDy != 0) {
                    pendingDx -= sendDx
                    pendingDy -= sendDy
                }
            }

            if (sendDx != 0 || sendDy != 0) {
                pointerSocket?.send("type:move\ndx:$sendDx\ndy:$sendDy\ndown:0\n\n")
            }

            mainHandler.postDelayed(this, 16L)
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val ts = timeFormat.format(Date())
        onLogMessage("[$ts] $message")
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createTrustAllOkHttpClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun connect(ip: String, useSecureWss: Boolean = true) {
        disconnect()
        val mySession = ++commandSession
        prefs.edit().putString("last_ip", ip).apply()
        val url = if (useSecureWss) "wss://$ip:3001" else "ws://$ip:3000"
        log("Connecting to: $url (session=$mySession)")
        onStatusChanged("جاري الاتصال بـ $url ...")

        val request = Request.Builder().url(url).build()
        commandSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (mySession != commandSession) return
                log("Command WebSocket Opened: HTTP ${response.code}")
                isConnected = true
                sendHandshake()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (mySession != commandSession) return
                log("RX <<< $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (mySession != commandSession) return
                log("Failure: ${t.javaClass.simpleName}: ${t.message}")
                isConnected = false
                stopPointerLoop()
                onStatusChanged("فشل الاتصال: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (mySession != commandSession) return
                log("Command Closed: code=$code reason=$reason")
                isConnected = false
                stopPointerLoop()
                onStatusChanged("تم قطع الاتصال ($code)")
            }
        })
    }

    private fun sendHandshake() {
        val savedKey = prefs.getString("client_key", null)
        val permissions = JSONArray(
            listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN",
                "TEST_PROTECTED", "CONTROL_AUDIO", "CONTROL_DISPLAY",
                "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
                "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
                "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
                "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST",
                "CONTROL_MOUSE_AND_KEYBOARD"
            )
        )

        val manifest = JSONObject().apply {
            put("manifestVersion", 1)
            put("appVersion", "1.4")
            put("permissions", permissions)
        }

        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            put("manifest", manifest)
            if (savedKey != null) put("client-key", savedKey)
        }

        val handshake = JSONObject().apply {
            put("type", "register")
            put("id", "register_0")
            put("payload", payload)
        }

        val msg = handshake.toString()
        log("TX >>> register_0 (savedKey=${savedKey != null})")
        commandSocket?.send(msg)
        onStatusChanged("يرجى قبول طلب الاقتران على شاشة التلفزيون...")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val id = json.optString("id")

            if (type == "error") {
                val errorMsg = json.optString("error", "خطأ غير معروف")
                log("ERROR [$id]: $errorMsg")
                onStatusChanged("خطأ من الشاشة ($id): $errorMsg")
                return
            }

            if (type == "registered" && id == "register_0") {
                val clientKey = json.optJSONObject("payload")?.optString("client-key")
                if (!clientKey.isNullOrEmpty()) {
                    prefs.edit().putString("client_key", clientKey).apply()
                    log("Saved client-key: $clientKey")
                }
                onStatusChanged("متصل بنجاح! جاري طلب مقبس المؤشر...")
                requestPointerSocket()
            } else if (id == "pointer_req") {
                val socketPath = json.optJSONObject("payload")?.optString("socketPath")
                if (!socketPath.isNullOrEmpty()) {
                    log("Pointer socketPath: $socketPath")
                    connectPointerSocket(socketPath)
                } else {
                    log("WARNING: pointer_req missing socketPath!")
                    onStatusChanged("الشاشة لم ترسل مسار المؤشر")
                }
            }
        } catch (e: Exception) {
            log("JSON Parse Error: ${e.message}")
            onStatusChanged("خطأ في تحليل رد الشاشة")
        }
    }

    private fun requestPointerSocket() {
        if (!isConnected) return
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "pointer_req")
            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        }
        val msg = req.toString()
        log("TX >>> pointer_req")
        commandSocket?.send(msg)
    }

    private fun connectPointerSocket(socketUrl: String) {
        pointerSocket?.close(1000, null)
        pointerSocket = null
        val myPointerSession = ++pointerSession

        val request = Request.Builder().url(socketUrl).build()
        pointerSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myPointerSession != pointerSession) return
                log("Pointer Socket Opened (session=$myPointerSession)")
                onStatusChanged("متصل + الماوس الهوائي جاهز")
                if (isAirMouseDesired) {
                    mainHandler.post { startPointerLoop() }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myPointerSession != pointerSession) return
                log("Pointer RX <<< $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myPointerSession != pointerSession) return
                log("Pointer Failure: ${t.message}")
                pointerSocket = null
                stopPointerLoop()
                onStatusChanged("انقطع مقبس الماوس، جاري إعادة الربط...")
                schedulePointerReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myPointerSession != pointerSession) return
                log("Pointer Closed: code=$code reason=$reason")
                pointerSocket = null
                stopPointerLoop()
                if (isConnected && code != 1000) {
                    schedulePointerReconnect()
                }
            }
        })
    }

    private fun schedulePointerReconnect() {
        mainHandler.postDelayed({
            if (isConnected) requestPointerSocket()
        }, 2000)
    }

    fun accumulateMouseDelta(dx: Float, dy: Float) {
        synchronized(pointerLock) {
            pendingDx += dx
            pendingDy += dy
        }
    }

    fun startPointerLoop() {
        if (isPointerLoopRunning) return
        synchronized(pointerLock) {
            pendingDx = 0.0f
            pendingDy = 0.0f
        }
        isPointerLoopRunning = true
        mainHandler.post(pointerSenderRunnable)
    }

    fun stopPointerLoop() {
        isPointerLoopRunning = false
        mainHandler.removeCallbacks(pointerSenderRunnable)
    }

    fun clickMouse() {
        val socket = pointerSocket ?: run {
            onStatusChanged("المؤشر غير متصل")
            return
        }
        socket.send("type:click\n\n")
    }

    fun sendButton(buttonName: String) {
        val socket = pointerSocket ?: run {
            onStatusChanged("المؤشر غير متصل")
            return
        }
        log("Pointer TX >>> button:$buttonName")
        socket.send("type:button\nname:$buttonName\n\n")
    }

    fun toggleMute() {
        isMuted = !isMuted
        sendSsapCommand("ssap://audio/setMute", JSONObject().put("mute", isMuted))
    }

    fun sendSsapCommand(uri: String, payload: JSONObject? = null) {
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "cmd_${msgId++}")
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }
        val msg = req.toString()
        log("TX >>> $msg")
        commandSocket?.send(msg)
    }

    fun getLastIp(): String = prefs.getString("last_ip", "") ?: ""

    fun disconnect() {
        commandSession++
        pointerSession++
        isConnected = false
        stopPointerLoop()
        mainHandler.removeCallbacksAndMessages(null)
        pointerSocket?.close(1000, null)
        pointerSocket = null
        commandSocket?.close(1000, null)
        commandSocket = null
    }
}
