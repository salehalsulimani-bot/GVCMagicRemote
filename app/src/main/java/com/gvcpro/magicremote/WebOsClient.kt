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
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.math.truncate

class WebOsClient(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onLogMessage: (String) -> Unit
) {
    private val client: OkHttpClient = createTrustAllOkHttpClient()
    private var commandSocket: WebSocket? = null
    @Volatile private var pointerSocket: WebSocket? = null
    @Volatile private var isPointerLoopRunning = false
    @Volatile var isAirMouseDesired = false
    @Volatile private var commandSession = 0
    @Volatile private var pointerSession = 0
    @Volatile private var userDisconnected = false
    @Volatile private var isConnected = false
    @Volatile private var isReconnectScheduled = false

    private var lastIp = ""
    private var lastUseSecure = true
    private var msgId = 1
    private var isMuted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("webos_prefs", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val pointerLock = Any()
    private var pendingDx = 0f
    private var pendingDy = 0f

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
            mainHandler.postDelayed(this, 22L)
        }
    }

    private val pointerKeepAliveRunnable = object : Runnable {
        override fun run() {
            val s = pointerSocket
            if (isConnected && s != null) {
                s.send("type:move\ndx:0\ndy:0\ndown:0\n\n")
                log("Pointer KeepAlive TX")
                mainHandler.postDelayed(this, 8000L)
            }
        }
    }

    private fun log(msg: String) {
        Log.d("WebOS_Client", msg)
        onLogMessage("[${timeFormat.format(Date())}] $msg")
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createTrustAllOkHttpClient(): OkHttpClient {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, t: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, t: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    fun connect(ip: String, useSecureWss: Boolean = true) {
        cleanupSockets()
        userDisconnected = false
        isReconnectScheduled = false
        lastIp = ip
        lastUseSecure = useSecureWss
        val mySession = ++commandSession
        prefs.edit().putString("last_ip", ip).apply()

        val url = if (useSecureWss) "wss://$ip:3001" else "ws://$ip:3000"
        log("Connecting: $url")
        onStatusChanged("جاري الاتصال...")

        commandSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) {
                if (mySession != commandSession) return
                isConnected = true
                isReconnectScheduled = false
                sendHandshake()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (mySession != commandSession) return
                log("RX <<< $text")
                handleMessage(text)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
                if (mySession != commandSession) return
                log("Failure: ${t.javaClass.name}: ${t.message}")
                isConnected = false
                pointerSocket = null
                stopPointerLoop()
                mainHandler.removeCallbacks(pointerKeepAliveRunnable)
                onStatusChanged("انقطع الاتصال، جاري إعادة الربط...")
                scheduleMainReconnect()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (mySession != commandSession) return
                log("Closed: $code $reason")
                isConnected = false
                pointerSocket = null
                stopPointerLoop()
                mainHandler.removeCallbacks(pointerKeepAliveRunnable)
                onStatusChanged("غير متصل")
                if (!userDisconnected && code != 1000) scheduleMainReconnect()
            }
        })
    }

    fun reconnectIfNeeded() {
        if (!userDisconnected && !isConnected && !isReconnectScheduled && lastIp.isNotEmpty()) {
            connect(lastIp, lastUseSecure)
        }
    }

    private fun scheduleMainReconnect() {
        if (userDisconnected || lastIp.isEmpty() || isReconnectScheduled) return
        isReconnectScheduled = true
        mainHandler.postDelayed({
            isReconnectScheduled = false
            if (!userDisconnected && !isConnected) connect(lastIp, lastUseSecure)
        }, 2500L)
    }

    private fun sendHandshake() {
        val savedKey = prefs.getString("client_key", null)
        val perms = JSONArray(listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
            "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS",
            "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
            "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "CONTROL_MOUSE_AND_KEYBOARD"
        ))
        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            put("manifest", JSONObject().put("manifestVersion", 1).put("appVersion", "1.7").put("permissions", perms))
            if (savedKey != null) put("client-key", savedKey)
        }
        commandSocket?.send(JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString())
        onStatusChanged("بانتظار موافقة الشاشة...")
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val id = json.optString("id")
            if (type == "error") {
                onStatusChanged("خطأ: ${json.optString("error")}")
                return
            }
            if (type == "registered" && id == "register_0") {
                json.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotEmpty() }?.let {
                    prefs.edit().putString("client_key", it).apply()
                }
                onStatusChanged("متصل • جاري تفعيل المؤشر...")
                requestPointerSocket()
            } else if (id == "pointer_req") {
                json.optJSONObject("payload")?.optString("socketPath")?.takeIf { it.isNotEmpty() }?.let {
                    connectPointerSocket(it)
                }
            }
        } catch (e: Exception) {
            log("Parse Error: ${e.message}")
        }
    }

    private fun requestPointerSocket() {
        if (!isConnected) return
        val req = JSONObject().put("type", "request").put("id", "pointer_req")
            .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        commandSocket?.send(req.toString())
    }

    private fun connectPointerSocket(socketUrl: String) {
        mainHandler.removeCallbacks(pointerKeepAliveRunnable)
        pointerSocket?.close(1000, null)
        pointerSocket = null
        val myPtr = ++pointerSession
        pointerSocket = client.newWebSocket(Request.Builder().url(socketUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, res: Response) {
                if (myPtr != pointerSession) return
                log("Pointer Socket Connected")
                onStatusChanged("متصل وجاهز للتحكم")
                mainHandler.removeCallbacks(pointerKeepAliveRunnable)
                mainHandler.postDelayed(pointerKeepAliveRunnable, 8000L)
                if (isAirMouseDesired) mainHandler.post { startPointerLoop() }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, res: Response?) {
                if (myPtr != pointerSession) return
                log("Pointer Failure: ${t.javaClass.name}: ${t.message}")
                mainHandler.removeCallbacks(pointerKeepAliveRunnable)
                pointerSocket = null
                stopPointerLoop()
                mainHandler.postDelayed({ if (isConnected) requestPointerSocket() }, 1500)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (myPtr != pointerSession) return
                log("Pointer Closed: $code $reason")
                mainHandler.removeCallbacks(pointerKeepAliveRunnable)
                pointerSocket = null
                stopPointerLoop()
                if (isConnected && code != 1000) mainHandler.postDelayed({ if (isConnected) requestPointerSocket() }, 1500)
            }
        })
    }

    fun accumulateMouseDelta(dx: Float, dy: Float) {
        synchronized(pointerLock) { pendingDx += dx; pendingDy += dy }
    }

    fun startPointerLoop() {
        if (isPointerLoopRunning) return
        synchronized(pointerLock) { pendingDx = 0f; pendingDy = 0f }
        isPointerLoopRunning = true
        mainHandler.post(pointerSenderRunnable)
    }

    fun stopPointerLoop() {
        isPointerLoopRunning = false
        mainHandler.removeCallbacks(pointerSenderRunnable)
    }

    fun clickMouse() {
        pointerSocket?.send("type:click\n\n") ?: if (isConnected) requestPointerSocket() else reconnectIfNeeded()
    }

    fun sendButton(buttonName: String) {
        val s = pointerSocket ?: run { if (isConnected) requestPointerSocket() else reconnectIfNeeded(); return }
        log("Pointer TX >>> button:$buttonName")
        s.send("type:button\nname:$buttonName\n\n")
    }

    fun sendText(text: String) {
        sendSsapCommand("ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", 0))
    }

    fun sendDeleteCharacters(count: Int = 1) {
        sendSsapCommand("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", count))
    }

    fun sendEnterKey() {
        sendSsapCommand("ssap://com.webos.service.ime/sendEnterKey")
    }

    fun toggleMute() {
        isMuted = !isMuted
        sendSsapCommand("ssap://audio/setMute", JSONObject().put("mute", isMuted))
    }

    fun sendSsapCommand(uri: String, payload: JSONObject? = null) {
        if (!isConnected) { reconnectIfNeeded(); return }
        val req = JSONObject().put("type", "request").put("id", "cmd_${msgId++}").put("uri", uri)
        if (payload != null) req.put("payload", payload)
        commandSocket?.send(req.toString())
    }

    fun getLastIp(): String = prefs.getString("last_ip", "") ?: ""

    private fun cleanupSockets() {
        commandSession++; pointerSession++; isConnected = false; isReconnectScheduled = false
        stopPointerLoop()
        mainHandler.removeCallbacksAndMessages(null)
        pointerSocket?.close(1000, null); pointerSocket = null
        commandSocket?.close(1000, null); commandSocket = null
    }

    fun disconnect() {
        userDisconnected = true
        cleanupSockets()
    }
}
