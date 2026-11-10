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

data class DiscoveredCamera(
    val name: String,
    val host: String,
    val port: Int
)

class RemoteClientManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        val cameras = mutableListOf<DiscoveredCamera>()

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
                cameras.removeAll { it.name == service.serviceName }
                _discoveredCameras.value = cameras.toList()
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
                    Log.i("RemoteClient", "Discovered CamPro Z camera at $host:$port ($name)")
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

    fun connect(info: SecurityHandshake.PairingInfo) {
        connect(
            host = info.hostAddress,
            cmdPort = info.commandPort,
            streamPort = info.streamPort,
            token = info.sessionToken,
            pin = info.pinCode
        )
    }

    fun connect(host: String, cmdPort: Int = 8989, streamPort: Int = 8990, token: String = "", pin: String = "") {
        disconnect()

        _errorMessage.value = null
        activeHost = host
        activeCmdPort = cmdPort
        activeStreamPort = streamPort
        activeToken = token
        activePin = pin
        _connectionStatus.value = ConnectionStatus.CONNECTING

        val uri = URI("ws://$host:$cmdPort")
        val clientName = "${Build.MANUFACTURER} ${Build.MODEL}"

        commandSocket = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i("RemoteClient", "Command socket connected to $uri")
                _connectionStatus.value = ConnectionStatus.WAITING_APPROVAL
                // Send pairing request
                val pairPayload = "{\"token\":\"$token\",\"pin\":\"$pin\",\"clientName\":\"$clientName\"}"
                send("PAIR:$pairPayload")
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                when {
                    message.startsWith("PAIR_RESULT:") -> {
                        val json = message.substringAfter("PAIR_RESULT:")
                        val success = ProtocolCodec.extractBoolean(json, "success") ?: false
                        if (success) {
                            activeSessionId = ProtocolCodec.extractString(json, "sessionId") ?: ""
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                            _errorMessage.value = null
                            Log.i("RemoteClient", "Pairing approved! SessionId=$activeSessionId")
                            // Start stream socket
                            connectStreamSocket(host, streamPort)
                            startHeartbeat()
                        } else {
                            val err = ProtocolCodec.extractString(json, "error") ?: "Declined by operator"
                            _connectionStatus.value = ConnectionStatus.REJECTED
                            _errorMessage.value = "Pairing request was declined: $err"
                            Log.w("RemoteClient", "Pairing rejected by camera host: $err")
                        }
                    }
                    message.startsWith("STATE:") -> {
                        val stateJson = message.substringAfter("STATE:")
                        val snapshot = ProtocolCodec.decodeState(stateJson)
                        if (snapshot != null) {
                            _cameraState.value = snapshot
                        }
                    }
                    message.startsWith("RESULT:") -> {
                        val resultJson = message.substringAfter("RESULT:")
                        val result = ProtocolCodec.decodeResult(resultJson)
                        Log.d("RemoteClient", "Command result received: $result")
                    }
                    message == "PONG" -> {
                        val rtt = SystemClock.elapsedRealtime() - pingStartTime
                        _rttMs.value = rtt
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.w("RemoteClient", "Command socket closed ($code): $reason")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                if (code == 1008) {
                    _errorMessage.value = "Camera access was revoked by the host operator."
                }
                disconnectStreamSocket()
            }

            override fun onError(ex: Exception?) {
                Log.e("RemoteClient", "Command socket error: ${ex?.message}")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _errorMessage.value = "Unable to connect to camera at $host:$cmdPort (${ex?.message ?: "Host unreachable"}). Ensure devices share the same Wi-Fi / hotspot."
            }
        }.apply {
            connect()
        }
    }

    private fun connectStreamSocket(host: String, streamPort: Int) {
        val uri = URI("ws://$host:$streamPort")
        streamSocket = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i("RemoteClient", "Stream socket connected to $uri")
            }

            override fun onMessage(message: String?) {}

            override fun onMessage(bytes: ByteBuffer?) {
                if (bytes == null) return
                val byteArray = ByteArray(bytes.remaining())
                bytes.get(byteArray)
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                if (bitmap != null) {
                    _liveFrame.value = bitmap
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("RemoteClient", "Stream socket closed")
            }

            override fun onError(ex: Exception?) {
                Log.e("RemoteClient", "Stream socket error: ${ex?.message}")
            }
        }.apply {
            connect()
        }
    }

    private fun startHeartbeat() {
        scope.launch {
            while (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(3000)
                pingStartTime = SystemClock.elapsedRealtime()
                commandSocket?.send("PING")
            }
        }
    }

    fun sendCommand(type: CommandType, payloadJson: String = "{}") {
        val socket = commandSocket ?: return
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return

        val cmd = CommandMessage(
            sessionId = activeSessionId,
            commandId = UUID.randomUUID().toString(),
            type = type,
            payloadJson = payloadJson,
            timestampMs = System.currentTimeMillis()
        )
        val json = ProtocolCodec.encodeCommand(cmd)
        socket.send("CMD:$json")
    }

    fun disconnect() {
        try {
            sendCommand(CommandType.DISCONNECT)
            commandSocket?.close()
        } catch (_: Exception) {}
        disconnectStreamSocket()
        commandSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _liveFrame.value = null
    }

    private fun disconnectStreamSocket() {
        try {
            streamSocket?.close()
        } catch (_: Exception) {}
        streamSocket = null
    }

    fun release() {
        stopDiscovery()
        disconnect()
        scope.cancel()
    }
}
