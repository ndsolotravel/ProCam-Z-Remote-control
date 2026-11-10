package org.ndsolotravel.camproz.remote.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.ndsolotravel.camproz.protocol.*
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.flow.update

data class DiscoveredCamera(
    val name: String,
    val host: String,
    val port: Int
)

class RemoteClientManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() {
        _errorMessage.value = null
    }

    fun setCustomError(msg: String) {
        _errorMessage.value = msg
    }

    private val _discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    val discoveredCameras: StateFlow<List<DiscoveredCamera>> = _discoveredCameras.asStateFlow()

    private val _cameraState = MutableStateFlow(CameraStateSnapshot())
    val cameraState: StateFlow<CameraStateSnapshot> = _cameraState.asStateFlow()

    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var commandSocket: WebSocketClient? = null
    private var streamSocket: WebSocketClient? = null

    private var activeHost: String = ""
    private var activeCmdPort: Int = 8989
    private var activeStreamPort: Int = 8990
    private var activeToken: String = ""
    private var activePin: String = ""
    private var activeSessionId: String = ""

    private var pingStartTime = 0L

    fun startDiscovery() {
        stopDiscovery()
        _discoveredCameras.value = emptyList()


        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("RemoteClient", "NSD Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("RemoteClient", "NSD Service found: ${service.serviceName}")
                if (service.serviceType.contains("_camproz._tcp")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _discoveredCameras.update { list -> list.filterNot { it.name == service.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("RemoteClient", "NSD Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("RemoteClient", "NSD Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("RemoteClient", "NSD Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices("_camproz._tcp.", NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        } catch (e: Exception) {
            Log.e("RemoteClient", "Error starting discovery: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("RemoteClient", "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host.hostAddress ?: return
                val port = resolvedInfo.port
                val name = resolvedInfo.serviceName

                val current = _discoveredCameras.value.toMutableList()
                if (current.none { it.host == host && it.port == port }) {
                    current.add(DiscoveredCamera(name, host, port))
                    _discoveredCameras.value = current
                    Log.i("RemoteClient", "Discovered ProCam Z camera at $host:$port ($name)")
                }
            }
        })
    }

    fun stopDiscovery() {
        try {
            nsdDiscoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        nsdDiscoveryListener = null
    }


    var autoReconnect = true
    val confirmedCommand = kotlinx.coroutines.flow.MutableSharedFlow<CommandType>(extraBufferCapacity = 8)
    val capabilities = MutableStateFlow<JSONObject?>(null)
    val pendingCommands = MutableStateFlow<Set<CommandType>>(emptySet())
    val previewStatus = MutableStateFlow("Waiting for preview")
    private var generation = 0
    private var heartbeat: Job? = null
    private var pairingTimeout: Job? = null
    private var retryJob: Job? = null
    private var streamRetry: Job? = null
    private var streamRetries = 0
    private var retries = 0
    private var lastPong = 0L
    private var lastFrame = 0L
    private val pendingResults = mutableMapOf<String, Pair<CommandType, Job>>()

    fun supports(type: CommandType): Boolean {
        val commands = capabilities.value?.optJSONArray("commands") ?: return false
        return (0 until commands.length()).any { commands.optString(it) == type.name }
    }

    fun connect(info: SecurityHandshake.PairingInfo) {
        if (info.isExpired()) { setCustomError("Pairing expired. Refresh the QR on the camera."); return }
        connect(info.hostAddress, info.commandPort, info.streamPort, info.sessionToken, info.pinCode)
    }

    fun connect(host: String, cmdPort: Int = 8989, streamPort: Int = 8990, token: String = "", pin: String = "") {
        if (!SecurityHandshake.validEndpoint(host, cmdPort, streamPort) ||
            (token.isEmpty() && !pin.matches(Regex("[0-9]{6}")))) {
            setCustomError("Enter a valid host, ports 1–65535, and the camera's six-digit PIN."); return
        }
        disconnect()
        retries = 0
        activeHost = host; activeCmdPort = cmdPort; activeStreamPort = streamPort
        activeToken = token; activePin = pin
        openCommandSocket()
    }

    private fun openCommandSocket() {
        val epoch = ++generation
        _errorMessage.value = null
        _connectionStatus.value = ConnectionStatus.CONNECTING
        val uri = URI("ws", null, activeHost, activeCmdPort, "/", null, null)
        val socket = object : WebSocketClient(uri, org.java_websocket.drafts.Draft_6455(), java.util.Collections.emptyMap<String, String>(), 5000) {
            override fun onOpen(handshakedata: ServerHandshake?) { scope.launch {
                if (epoch != generation) return@launch
                _connectionStatus.value = ConnectionStatus.WAITING_APPROVAL
                send("PAIR:" + JSONObject().put("token", activeToken).put("pin", activePin)
                    .put("clientName", "${Build.MANUFACTURER} ${Build.MODEL}"))
            } }
            override fun onMessage(message: String?) { scope.launch {
                if (epoch != generation || message == null || message.length > 65536) return@launch
                try {
                    when {
                        message.startsWith("PAIR_RESULT:") -> {
                            val result = JSONObject(message.substringAfter(':'))
                            if (!result.optBoolean("success")) {
                                _connectionStatus.value = ConnectionStatus.REJECTED
                                _errorMessage.value = result.optString("error", "Pairing declined")
                                pairingTimeout?.cancel(); close(); return@launch
                            }
                            activeSessionId = result.getString("sessionId")
                            require(activeSessionId.isNotBlank())
                            pairingTimeout?.cancel()
                            // Connected means authenticated. Controls still wait for CAPS and STATE.
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                            lastPong = SystemClock.elapsedRealtime()
                            connectStreamSocket(epoch)
                            heartbeat?.cancel()
                            heartbeat = scope.launch {
                                while (epoch == generation && isActive) {
                                    delay(2000)
                                    if (SystemClock.elapsedRealtime() - lastPong > 10000) { close(1001, "Heartbeat timeout"); break }
                                    if (lastFrame > 0 && SystemClock.elapsedRealtime() - lastFrame > 3000) {
                                        _liveFrame.value = null; previewStatus.value = "Preview interrupted; recovering…"
                                        lastFrame = 0; streamSocket?.close(1001, "Stale preview")
                                    }
                                    pingStartTime = SystemClock.elapsedRealtime()
                                    runCatching { send("PING") }
                                }
                            }
                        }
                        message.startsWith("CAPS:") -> capabilities.value = JSONObject(message.substringAfter(':'))
                        message.startsWith("STATE:") -> {
                            val snapshot = ProtocolCodec.decodeState(message.substringAfter(':')) ?: error("Invalid camera state")
                            if (snapshot.stateRevision >= _cameraState.value.stateRevision) _cameraState.value = snapshot
                        }
                        message.startsWith("RESULT:") -> {
                            val result = ProtocolCodec.decodeResult(message.substringAfter(':')) ?: error("Invalid command result")
                            pendingResults.remove(result.commandId)?.let { (type, job) ->
                                job.cancel(); pendingCommands.value = pendingCommands.value - type
                                if (result.success) confirmedCommand.tryEmit(type)
                                if (!result.success) _errorMessage.value = result.errorMessage ?: "Camera rejected ${type.name}"
                            }
                        }
                        message.startsWith("ERROR:") -> _errorMessage.value = message.substringAfter(':')
                        message == "PONG" -> { lastPong = SystemClock.elapsedRealtime(); _rttMs.value = lastPong - pingStartTime }
                    }
                } catch (_: Exception) { _errorMessage.value = "Camera sent an invalid protocol message." }
            } }
            override fun onClose(code: Int, reason: String?, remote: Boolean) { scope.launch {
                if (epoch != generation) return@launch
                val rejected = _connectionStatus.value == ConnectionStatus.REJECTED
                clearSession()
                if (rejected) _connectionStatus.value = ConnectionStatus.REJECTED
                else if (code == 1008) {
                    _connectionStatus.value = ConnectionStatus.REVOKED
                    _errorMessage.value = "Access revoked. Pair again on the camera."
                } else {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    _errorMessage.value = "Connection lost. Recording state is unknown until reconnected."
                    if (autoReconnect && retries < 3) {
                        val waitMs = 1000L shl retries++
                        retryJob = scope.launch { delay(waitMs); if (epoch == generation) openCommandSocket() }
                    }
                }
            } }
            override fun onError(ex: Exception?) { scope.launch {
                if (epoch == generation) _errorMessage.value = "Cannot reach camera. Check Wi-Fi/hotspot, IP, ports and host approval."
            } }
        }
        commandSocket = socket
        socket.connectionLostTimeout = 10
        socket.connect()
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch {
            delay(35000)
            if (epoch == generation && _connectionStatus.value != ConnectionStatus.CONNECTED) {
                disconnect(); _errorMessage.value = "Connection or host approval timed out. Try pairing again."
            }
        }
    }

    private fun connectStreamSocket(epoch: Int) {
        val uri = URI("ws", null, activeHost, activeStreamPort, "/", "session=$activeSessionId", null)
        previewStatus.value = "Connecting preview"
        streamSocket = object : WebSocketClient(uri, org.java_websocket.drafts.Draft_6455(), java.util.Collections.emptyMap<String, String>(), 5000) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String?) {}
            override fun onMessage(bytes: ByteBuffer?) {
                if (epoch != generation || bytes == null || bytes.remaining() > 2_000_000) return
                val data = ByteArray(bytes.remaining()); bytes.get(data)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..4096) return
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
                scope.launch {
                    if (epoch == generation) { _liveFrame.value = bitmap; lastFrame = SystemClock.elapsedRealtime(); streamRetries = 0; previewStatus.value = "Live preview" }
                    else bitmap.recycle()
                }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) { scope.launch {
                if (epoch == generation) {
                    _liveFrame.value = null; previewStatus.value = "Preview disconnected"
                    if (code != 1008 && _connectionStatus.value == ConnectionStatus.CONNECTED && streamRetries < 3) {
                        val waitMs = 1000L shl streamRetries++
                        streamRetry?.cancel()
                        streamRetry = scope.launch { delay(waitMs); if (epoch == generation) connectStreamSocket(epoch) }
                    }
                }
            } }
            override fun onError(ex: Exception?) { scope.launch {
                if (epoch == generation) previewStatus.value = "Preview unavailable; check port $activeStreamPort"
            } }
        }.apply { connect() }
    }

    fun sendCommand(type: CommandType, payloadJson: String = "{}") {
        val socket = commandSocket ?: return
        if (_connectionStatus.value != ConnectionStatus.CONNECTED || type in pendingCommands.value) return
        if (!supports(type)) { _errorMessage.value = "${type.name}: unsupported by this camera. ${capabilities.value?.optString("unsupportedReason").orEmpty()}"; return }
        val id = UUID.randomUUID().toString()
        val cmd = CommandMessage(sessionId = activeSessionId, commandId = id, type = type,
            payloadJson = payloadJson, timestampMs = System.currentTimeMillis())
        try {
            pendingCommands.value = pendingCommands.value + type
            val timeout = scope.launch {
                delay(16000)
                pendingResults.remove(id)
                pendingCommands.value = pendingCommands.value - type
                _errorMessage.value = "${type.name} timed out. Check camera state before retrying; no automatic command retry was sent."
            }
            pendingResults[id] = type to timeout
            socket.send("CMD:${ProtocolCodec.encodeCommand(cmd)}")
        } catch (_: Exception) {
            pendingResults.remove(id)?.second?.cancel(); pendingCommands.value = pendingCommands.value - type
            _errorMessage.value = "Command could not be sent. Reconnect to check camera state."
        }
    }

    private fun clearSession() {
        heartbeat?.cancel(); pairingTimeout?.cancel(); streamRetry?.cancel(); streamRetries = 0
        pendingResults.values.forEach { it.second.cancel() }; pendingResults.clear(); pendingCommands.value = emptySet()
        streamSocket?.close(); streamSocket = null
        activeSessionId = ""; capabilities.value = null; _liveFrame.value = null; _rttMs.value = 0
        _cameraState.value = CameraStateSnapshot(); lastFrame = 0
        previewStatus.value = "Disconnected"
    }
    fun disconnect() {
        ++generation; retryJob?.cancel(); retryJob = null
        val socket = commandSocket; commandSocket = null
        clearSession(); socket?.close()
        activeToken = ""; activePin = ""
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
    fun release() { stopDiscovery(); disconnect(); scope.cancel() }
}
