from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'campro-z'
base = root / 'remoteApp/src/main/kotlin/org/ndsolotravel/camproz/remote'
p = base / 'network/RemoteClientManager.kt'
old = p.read_text(encoding='utf-8')
prefix = old[:old.index('    fun connect(info:')]
prefix = prefix.replace('CoroutineScope(Dispatchers.IO + SupervisorJob())', 'CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())')
prefix = prefix.replace('import java.util.UUID', 'import java.util.UUID\nimport org.json.JSONObject\nimport kotlinx.coroutines.flow.update')
prefix = prefix.replace('        val cameras = mutableListOf<DiscoveredCamera>()', '')
prefix = prefix.replace('cameras.removeAll { it.name == service.serviceName }\n                _discoveredCameras.value = cameras.toList()', '_discoveredCameras.update { list -> list.filterNot { it.name == service.serviceName } }')
suffix = r'''
    val capabilities = MutableStateFlow<JSONObject?>(null)
    val pendingCommands = MutableStateFlow<Set<CommandType>>(emptySet())
    val previewStatus = MutableStateFlow("Waiting for preview")
    private var generation = 0
    private var heartbeat: Job? = null
    private var pairingTimeout: Job? = null
    private var retryJob: Job? = null
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
        val socket = object : WebSocketClient(uri) {
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
                                        _liveFrame.value = null; previewStatus.value = "Preview interrupted; reconnect to recover"
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
                    if (retries < 3) {
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
        socket.connectTimeout = 5000
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
        streamSocket = object : WebSocketClient(uri) {
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
                    if (epoch == generation) { _liveFrame.value = bitmap; lastFrame = SystemClock.elapsedRealtime(); previewStatus.value = "Live preview" }
                    else bitmap.recycle()
                }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) { scope.launch {
                if (epoch == generation) { _liveFrame.value = null; previewStatus.value = "Preview disconnected; reconnect to recover" }
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
        heartbeat?.cancel(); pairingTimeout?.cancel()
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
'''
p.write_text(prefix+suffix, encoding='utf-8')

# Android-only networking/crypto belongs in Android sources, not iOS commonMain.
protocol = 'kotlin/org/ndsolotravel/camproz/protocol'
for file in ('SecurityHandshake.kt', 'ProtocolCodec.kt'):
    src = root / 'shared/src/commonMain' / protocol / file
    dst = root / 'shared/src/androidMain' / protocol / file
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.exists(): src.rename(dst)
for file in ('SecurityHandshakeTest.kt', 'ProtocolCodecTest.kt'):
    src = root / 'shared/src/commonTest' / protocol / file
    dst = root / 'shared/src/androidHostTest' / protocol / file
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.exists(): src.rename(dst)

p = root / 'shared/src/androidMain' / protocol / 'ProtocolCodec.kt'
t = p.read_text(encoding='utf-8')
t = t.replace('package org.ndsolotravel.camproz.protocol', 'package org.ndsolotravel.camproz.protocol\n\nimport org.json.JSONObject')
t = t[:t.index('    // Helper utilities')] + r'''
    private fun escape(s: String): String = JSONObject.quote(s).removeSurrounding("\"")
    private fun value(json: String, key: String): Any? = try { JSONObject(json).opt(key) } catch (_: Exception) { null }
    fun extractString(json: String, key: String): String? = value(json, key) as? String
    fun extractLong(json: String, key: String): Long? = (value(json, key) as? Number)?.let {
        val n = it.toLong(); if (it.toDouble() == n.toDouble()) n else null
    }
    fun extractInt(json: String, key: String): Int? = extractLong(json, key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    fun extractFloat(json: String, key: String): Float? = (value(json, key) as? Number)?.toFloat()?.takeIf { it.isFinite() }
    fun extractBoolean(json: String, key: String): Boolean? = value(json, key) as? Boolean
    fun extractJsonObject(json: String, key: String): String? = (value(json, key) as? JSONObject)?.toString()
}
'''
t = t.replace('val version = extractString(json, "version") ?: "1.0"', 'val version = extractString(json, "version") ?: return null\n            if (version != "1.0") return null')
t = t.replace('val sessionId = extractString(json, "sessionId") ?: ""', 'val sessionId = extractString(json, "sessionId")?.takeIf { it.isNotBlank() } ?: return null')
t = t.replace('val commandId = extractString(json, "commandId") ?: ""', 'val commandId = extractString(json, "commandId")?.takeIf { it.isNotBlank() } ?: return null')
t = t.replace('val payload = extractJsonObject(json, "payload") ?: "{}"', 'val payload = extractJsonObject(json, "payload") ?: return null')
p.write_text(t, encoding='utf-8')

p = root / 'shared/src/androidMain' / protocol / 'SecurityHandshake.kt'
t = p.read_text(encoding='utf-8')
t = t.replace('    const val CURRENT_PROTOCOL_VERSION', '''    fun validEndpoint(host: String, commandPort: Int, streamPort: Int): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && (p.toIntOrNull() ?: -1) in 0..255 } &&
            parts[0] != "127" && host != "0.0.0.0" && host != "255.255.255.255" &&
            commandPort in 1..65535 && streamPort in 1..65535 && commandPort != streamPort
    }

    const val CURRENT_PROTOCOL_VERSION''')
t = t.replace('val params = query.split("&").associate', '''val fields = query.split("&")
                if (fields.map { it.substringBefore('=') }.distinct().size != fields.size) {
                    return QrValidationResult.InvalidPayload("Duplicate query parameters")
                }
                val params = fields.associate''')
t = t.replace('val rawName = params["name"]', '''if (!validEndpoint(host, cmd, stream) || token.isBlank() || token.length > 256 || !pin.matches(Regex("[0-9]{6}")) ||
                    exp !in 1..DEFAULT_PAIRING_VALIDITY_MS || (params.containsKey("ts") && ts <= 0) || ts > nowMs + 60000) {
                    return QrValidationResult.InvalidPayload("Invalid endpoint, credentials or expiry")
                }
                val rawName = params["name"]''')
t = t.replace('return expected.equals(signature, ignoreCase = true)', 'return java.security.MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())')
p.write_text(t, encoding='utf-8')

p = root / 'shared/build.gradle.kts'
t = p.read_text(encoding='utf-8').replace('        commonTest.dependencies {', '''        getByName("androidHostTest").dependencies {
            implementation("org.json:json:20240303")
            implementation(libs.kotlin.testJunit)
        }
        commonTest.dependencies {''')
p.write_text(t, encoding='utf-8')
p = root / 'remoteApp/src/main/AndroidManifest.xml'
t = p.read_text(encoding='utf-8').replace('<application', '<application\n        android:usesCleartextTraffic="true"')
t = t.replace('    <application', '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n\n    <application')
p.write_text(t, encoding='utf-8')
print('Updated remote transport and Android protocol boundaries')
