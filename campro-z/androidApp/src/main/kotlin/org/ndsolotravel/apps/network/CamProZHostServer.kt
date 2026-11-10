package org.ndsolotravel.apps.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.ndsolotravel.apps.camera.CameraEngine
import org.ndsolotravel.camproz.protocol.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Host server running on the ProCam Z camera device.
 * Exposes NSD discovery, WebSocket command channel with authentication,
 * and low-latency monitoring frame streaming.
 */
class CamProZHostServer(
    private val context: Context,
    private val cameraEngine: CameraEngine
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    var commandPort = 8989
    var streamPort = 8990

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var commandServer: CommandWebSocketServer? = null
    private var streamServer: StreamWebSocketServer? = null

    // Pairing & Authentication state
    var currentPairingInfo: SecurityHandshake.PairingInfo? = null
        private set

    @Volatile var activeClientSocket: WebSocket? = null
    @Volatile var activeClientSessionId: String? = null
    @Volatile var isClientApproved: Boolean = false

    var onClientApprovalRequest: ((clientName: String, onDecision: (Boolean) -> Unit) -> Unit)? = null
    var onClientConnectionChanged: ((Boolean, String?) -> Unit)? = null

    fun start() {
        refreshPairingInfo()

        // 1. Start Command WebSocket Server
        commandServer = CommandWebSocketServer(InetSocketAddress(commandPort)).apply {
            isReuseAddr = true
            start()
        }

        // 2. Start Live Frame Streaming Server
        streamServer = StreamWebSocketServer(InetSocketAddress(streamPort)).apply {
            isReuseAddr = true
            start()
        }

        // 3. Register GL Renderer Frame Callback
        cameraEngine.glRenderer.setStreamingCallback { jpegBytes ->
            streamServer?.broadcastFrame(jpegBytes)
        }

        // 4. Register NSD mDNS Discovery Service
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        registerNsdService(deviceName)

        // 5. Monitor and broadcast Camera State changes
        scope.launch {
            cameraEngine.state.collect { snapshot ->
                broadcastState(snapshot)
            }
        }

        Log.i("HostServer", "ProCam Z Host Server running. PIN=${currentPairingInfo?.pinCode}, Token=${currentPairingInfo?.sessionToken}, CmdPort=$commandPort, StreamPort=$streamPort")
    }

    fun isHostNetworkReady(): Boolean = getLocalIpAddress() != null

    fun refreshPairingInfo(): SecurityHandshake.PairingInfo {
        val token = SecurityHandshake.generateSessionToken()
        val pin = SecurityHandshake.generatePinCode()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val ip = getLocalIpAddress() ?: "0.0.0.0"

        val info = SecurityHandshake.PairingInfo(
            hostAddress = ip,
            commandPort = commandPort,
            streamPort = streamPort,
            sessionToken = token,
            pinCode = pin,
            deviceName = deviceName
        )
        currentPairingInfo = info
        return info
    }

    private fun registerNsdService(deviceName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "CamProZ-$deviceName"
            serviceType = "_camproz._tcp."
            port = commandPort
        }

        nsdRegistrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.i("HostServer", "NSD Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("HostServer", "NSD Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i("HostServer", "NSD Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("HostServer", "NSD Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            Log.e("HostServer", "Failed to register NSD service: ${e.message}")
        }
    }

    fun broadcastState(snapshot: CameraStateSnapshot) {
        val client = activeClientSocket ?: return
        if (!isClientApproved) return
        val json = ProtocolCodec.encodeState(snapshot)
        try {
            client.send("STATE:$json")
        } catch (e: Exception) {
            Log.w("HostServer", "Failed to send state update: ${e.message}")
        }
    }

    fun revokeCurrentClient() {
        activeClientSocket?.close(1000, "Access Revoked by Camera Operator")
        activeClientSocket = null
        activeClientSessionId = null
        isClientApproved = false
        onClientConnectionChanged?.invoke(false, null)
        Log.i("HostServer", "Revoked client connection")
    }

    private inner class CommandWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.i("HostServer", "New client socket opened from ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            Log.i("HostServer", "Client socket closed: $reason")
            if (conn == activeClientSocket) {
                activeClientSocket = null
                activeClientSessionId = null
                isClientApproved = false
                onClientConnectionChanged?.invoke(false, null)
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            if (message.startsWith("PAIR:")) {
                handlePairingRequest(conn, message.substringAfter("PAIR:"))
            } else if (message.startsWith("CMD:")) {
                handleCommandRequest(conn, message.substringAfter("CMD:"))
            } else if (message == "PING") {
                conn.send("PONG")
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e("HostServer", "WebSocket error: ${ex.message}")
        }

        override fun onStart() {
            Log.d("HostServer", "Command server listening on port $commandPort")
        }

        private fun handlePairingRequest(conn: WebSocket, payload: String) {
            val token = ProtocolCodec.extractString(payload, "token") ?: ""
            val pin = ProtocolCodec.extractString(payload, "pin") ?: ""
            val clientName = ProtocolCodec.extractString(payload, "clientName") ?: "Remote Client"
            val pairing = currentPairingInfo ?: return

            // Verify pairing token or manual PIN
            val valid = (token == pairing.sessionToken) || (pin == pairing.pinCode)
            if (!valid) {
                conn.send("PAIR_RESULT:{\"success\":false,\"error\":\"Invalid pairing token or PIN\"}")
                conn.close(1008, "Invalid credentials")
                return
            }

            // Request host operator approval on camera device
            onClientApprovalRequest?.invoke(clientName) { approved ->
                if (approved) {
                    activeClientSocket = conn
                    activeClientSessionId = pairing.sessionToken
                    isClientApproved = true
                    conn.send("PAIR_RESULT:{\"success\":true,\"sessionId\":\"${pairing.sessionToken}\"}")
                    onClientConnectionChanged?.invoke(true, clientName)
                    // Immediately push initial state
                    broadcastState(cameraEngine.state.value)
                } else {
                    conn.send("PAIR_RESULT:{\"success\":false,\"error\":\"Pairing declined by camera operator\"}")
                    conn.close(1008, "Declined")
                }
            }
        }

        private fun handleCommandRequest(conn: WebSocket, cmdJson: String) {
            if (conn != activeClientSocket || !isClientApproved) {
                conn.send("ERROR:Unauthorized")
                return
            }

            val cmd = ProtocolCodec.decodeCommand(cmdJson)
            if (cmd == null) {
                conn.send("ERROR:Malformed command")
                return
            }

            // Execute on authoritative camera engine
            val result = cameraEngine.executeCommand(cmd)
            val resultJson = ProtocolCodec.encodeResult(result)
            conn.send("RESULT:$resultJson")
        }
    }

    private inner class StreamWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val clientIp = conn.remoteSocketAddress?.address?.hostAddress
            val approvedCmdIp = activeClientSocket?.remoteSocketAddress?.address?.hostAddress
            if (approvedCmdIp != null && clientIp != null && clientIp != approvedCmdIp) {
                Log.w("StreamServer", "Rejecting stream from $clientIp (expected $approvedCmdIp)")
                conn.close(1008, "Unauthorized stream client")
                return
            }
            Log.d("StreamServer", "Video stream client connected: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            Log.d("StreamServer", "Video stream client disconnected")
        }

        override fun onMessage(conn: WebSocket, message: String) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
        override fun onStart() {
            Log.d("StreamServer", "Stream server listening on port $streamPort")
        }

        fun broadcastFrame(jpegBytes: ByteArray) {
            if (connections.isEmpty() || !isClientApproved) return
            val buffer = ByteBuffer.wrap(jpegBytes)
            for (conn in connections) {
                if (conn.isOpen) {
                    try {
                        conn.send(buffer.duplicate())
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<Pair<String, String>>()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("p2p")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != "127.0.0.1" && ip != "10.0.2.2" && ip != "0.0.0.0") {
                            candidates.add(name to ip)
                        }
                    }
                }
            }

            // Priority 1: Wi-Fi, Hotspot, and USB/Ethernet interfaces
            val wifiOrAp = candidates.firstOrNull { (name, _) ->
                name.contains("wlan") || name.contains("ap") || name.contains("softap") ||
                        name.contains("eth") || name.contains("rndis") || name.contains("swlan")
            }
            if (wifiOrAp != null) return wifiOrAp.second

            // Priority 2: Private subnets
            val privateSubnet = candidates.firstOrNull { (_, ip) ->
                ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")
            }
            if (privateSubnet != null) return privateSubnet.second

            val nonCellular = candidates.firstOrNull { (name, _) -> !name.startsWith("rmnet") && !name.startsWith("ccmni") }
            if (nonCellular != null) return nonCellular.second

            return candidates.firstOrNull()?.second
        } catch (_: Exception) {}
        return null
    }

    fun stop() {
        scope.cancel()
        cameraEngine.glRenderer.setStreamingCallback(null)
        try {
            nsdRegistrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {}
        try {
            commandServer?.stop()
            streamServer?.stop()
        } catch (_: Exception) {}
    }
}
