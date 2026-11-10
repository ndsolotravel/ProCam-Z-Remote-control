package com.rearviewpro.camera.remote

import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.TextureView
import com.rearviewpro.camera.ui.CameraViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID

data class LanStatus(val enabled: Boolean = false, val ready: Boolean = false,
    val address: String = "", val pin: String = "", val qr: String = "",
    val client: String? = null, val error: String? = null)
data class Approval(val name: String, val decide: (Boolean) -> Unit)

/** Owns both sockets for one explicitly approved controller; all state is confined to Main. */
class LanRemoteHost(private val context: Context, private val camera: CameraViewModel) {
    val status = MutableStateFlow(LanStatus())
    val approval = MutableStateFlow<Approval?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var commands: Server? = null
    private var streams: Server? = null
    private var controller: WebSocket? = null
    private var pending: WebSocket? = null
    private var stream: WebSocket? = null
    private var token = ""
    private var session = ""
    private var expiresAt = 0L
    private var generation = 0
    private var readyCount = 0
    private var monitor: Job? = null
    private var lastPing = 0L
    private var frameAt = 0L
    private var compressing = false
    private var registration: NsdManager.RegistrationListener? = null
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val results = LinkedHashMap<String, String>()
    private val inFlight = mutableSetOf<String>()
    private var lastAttempt = 0L

    fun start() {
        if (status.value.enabled) return
        val addresses = NetworkInterface.getNetworkInterfaces().toList().filter {
            it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("rmnet")
        }.sortedBy { if (it.name.contains("wlan") || it.name.contains("ap")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
        val ip = addresses.firstOrNull()?.hostAddress
        if (ip == null) { status.value = LanStatus(error = "Join Wi-Fi or enable a hotspot first."); return }
        generation++
        val epoch = generation
        token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val pin = (SecureRandom().nextInt(900000) + 100000).toString()
        val now = System.currentTimeMillis()
        expiresAt = SystemClock.elapsedRealtime() + 300000
        val name = URLEncoder.encode(android.os.Build.MODEL, "UTF-8")
        val qr = "camproz://pair?v=1.0&host=$ip&cmd=8989&stream=8990&token=$token&pin=$pin&name=$name&ts=$now&exp=300000"
        status.value = LanStatus(enabled = true, address = ip, pin = pin, qr = qr)
        readyCount = 0
        commands = Server(8989, false, epoch).also { it.start() }
        streams = Server(8990, true, epoch).also { it.start() }
        monitor = scope.launch {
            while (isActive) {
                delay(500)
                if (controller != null && SystemClock.elapsedRealtime() - lastPing > 12000) revoke()
                if (controller == null && SystemClock.elapsedRealtime() > expiresAt) {
                    stop(); status.value = LanStatus(error = "Pairing expired. Enable LAN remote again."); break
                }
                if (controller != null) publish()
            }
        }
    }

    private fun register() {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                scope.launch { status.value = status.value.copy(error = "Discovery unavailable ($code). Use QR or manual IP.") }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(NsdServiceInfo().apply {
            serviceName = "CamProZ-${android.os.Build.MODEL}"; serviceType = "_camproz._tcp."; port = 8989
        }, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private inner class Server(port: Int, val video: Boolean, val epoch: Int) : WebSocketServer(InetSocketAddress(port)) {
        init { isReuseAddr = true; setMaxPendingConnections(4); connectionLostTimeout = 10 }
        override fun onStart() { scope.launch {
            if (epoch != generation) return@launch
            readyCount++
            if (readyCount == 2) { status.value = status.value.copy(ready = true); register() }
        } }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            scope.launch {
                if (epoch != generation) conn.close(1008, "Session ended")
                else if (video) {
                    if (session.isBlank() || controller?.isOpen != true || handshake.resourceDescriptor != "/?session=$session" || stream != null)
                        conn.close(1008, "Unauthorized preview")
                    else stream = conn
                }
            }
        }
        override fun onMessage(conn: WebSocket, message: String) { scope.launch {
            if (epoch != generation || video) return@launch
            if (message.length > 16384) { conn.close(1009, "Message too large"); return@launch }
            try {
                when {
                    message.startsWith("PAIR:") -> pair(conn, JSONObject(message.removePrefix("PAIR:")))
                    message == "PING" && conn == controller -> { lastPing = SystemClock.elapsedRealtime(); send(conn, "PONG") }
                    message.startsWith("CMD:") -> command(conn, JSONObject(message.removePrefix("CMD:")))
                    else -> send(conn, "ERROR:Unrecognized or unauthorized message")
                }
            } catch (_: Exception) { send(conn, "ERROR:Malformed message") }
        } }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) { scope.launch {
            if (conn == controller) revoke()
            if (conn == pending) { pending = null; approval.value = null }
            if (conn == stream) stream = null
        } }
        override fun onError(conn: WebSocket?, ex: Exception) { scope.launch {
            if (epoch == generation && conn == null) { stop(); status.value = LanStatus(error = "LAN server failed: ${ex.javaClass.simpleName}. Check ports 8989/8990.") }
        } }
    }

    private fun send(conn: WebSocket?, message: String) { if (conn?.isOpen == true) runCatching { conn.send(message) } }
    private fun reject(conn: WebSocket, reason: String) {
        send(conn, "PAIR_RESULT:" + JSONObject().put("success", false).put("error", reason)); conn.close(1008, reason)
    }
    private fun pair(conn: WebSocket, payload: JSONObject) {
        if (!status.value.ready) { reject(conn, "Host starting"); return }
        if (controller != null || pending != null) { reject(conn, "BUSY_ANOTHER_CLIENT_CONNECTED"); return }
        val now = SystemClock.elapsedRealtime()
        if (now > expiresAt) { reject(conn, "Pairing expired"); return }
        if (now - lastAttempt < 1000) { reject(conn, "Wait before retrying"); return }; lastAttempt = now
        val providedToken = payload.optString("token")
        val valid = if (providedToken.isNotEmpty()) providedToken == token else payload.optString("pin") == status.value.pin
        if (!valid) { reject(conn, "Invalid pairing credentials"); return }
        pending = conn
        val epoch = generation
        approval.value = Approval(payload.optString("clientName", "Remote").take(80)) { allow -> scope.launch {
            if (epoch != generation || pending != conn) return@launch
            pending = null; approval.value = null
            if (!allow || !conn.isOpen || SystemClock.elapsedRealtime() > expiresAt) { reject(conn, "Pairing declined or expired"); return@launch }
            controller = conn; session = UUID.randomUUID().toString(); lastPing = SystemClock.elapsedRealtime()
            status.value = status.value.copy(client = payload.optString("clientName", "Remote").take(80))
            send(conn, "PAIR_RESULT:" + JSONObject().put("success", true).put("sessionId", session))
            publish()
        } }
        scope.launch { delay(30000); if (pending == conn) { pending = null; approval.value = null; reject(conn, "Approval timed out") } }
    }

    private suspend fun command(conn: WebSocket, json: JSONObject) {
        if (conn != controller || session.isBlank() || json.optString("sessionId") != session) { send(conn, "ERROR:Unauthorized"); return }
        if (json.optString("version") != "1.0") { send(conn, "ERROR:Unsupported protocol version"); return }
        val id = json.getString("commandId")
        require(id.isNotBlank() && id.length <= 128)
        results[id]?.let { send(conn, it); return }
        if (!inFlight.add(id)) return
        val epoch = generation
        val activeSession = session
        val outcome = runCatching {
            val type = json.getString("type")
            if (type == "DISCONNECT") { revoke(); return }
            withTimeout(15000) { camera.executeLanCommand(type, json.getJSONObject("payload")) }
        }
        inFlight.remove(id)
        if (epoch != generation || activeSession != session || conn != controller) return
        val result = "RESULT:" + JSONObject().put("commandId", id).put("success", outcome.isSuccess)
            .put("stateRevision", camera.lanRevision).apply { outcome.exceptionOrNull()?.let { put("error", it.message ?: "Camera action failed") } }
        results[id] = result
        while (results.size > 128) results.remove(results.keys.first())
        send(conn, result); publish()
    }

    fun publish() {
        send(controller, "CAPS:${camera.lanCapabilities()}")
        send(controller, "STATE:${camera.lanSnapshot()}")
    }
    fun capture(texture: TextureView) {
        if (stream?.isOpen != true || compressing || SystemClock.elapsedRealtime() - frameAt < 200) return
        frameAt = SystemClock.elapsedRealtime(); compressing = true
        val epoch = generation
        val width = minOf(640, texture.width)
        val height = (texture.height.toFloat() * width / texture.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val bitmap = runCatching { texture.getBitmap(width, height) }.getOrNull()
        if (bitmap == null) { compressing = false; return }
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out); out.toByteArray() } }
                if (epoch == generation && controller?.isOpen == true && stream?.isOpen == true && stream?.hasBufferedData() == false) stream?.send(bytes)
            } finally { bitmap.recycle(); compressing = false }
        }
    }
    fun revoke() {
        val old = controller; controller = null; session = ""
        stream?.close(1008, "Access revoked"); stream = null
        old?.close(1008, "Access revoked")
        pending?.close(1008, "Access revoked"); pending = null; approval.value = null
        results.clear(); inFlight.clear(); status.value = status.value.copy(client = null)
    }
    fun stop() {
        generation++; revoke(); monitor?.cancel(); monitor = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        val oldCommands = commands; val oldStreams = streams; commands = null; streams = null
        scope.launch(Dispatchers.IO) { runCatching { oldCommands?.stop(1000) }; runCatching { oldStreams?.stop(1000) } }
        status.value = LanStatus(); token = ""
    }
}
