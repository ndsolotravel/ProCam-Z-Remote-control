package org.ndsolotravel.camproz.remote.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ndsolotravel.camproz.protocol.*
import org.ndsolotravel.camproz.remote.R
import org.ndsolotravel.camproz.remote.network.DiscoveredCamera
import org.ndsolotravel.camproz.remote.network.RemoteClientManager
import org.ndsolotravel.camproz.remote.settings.FramingGuideMode
import org.ndsolotravel.camproz.remote.settings.RemoteSettingsRepository

@Composable
fun RemoteMainScreen(clientManager: RemoteClientManager) {
    val context = LocalContext.current
    val settingsRepo = remember { RemoteSettingsRepository(context) }
    val settings by settingsRepo.settings.collectAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val latestHapticEnabled by rememberUpdatedState(settings.hapticFeedback)
    LaunchedEffect(settings.autoReconnect) { clientManager.autoReconnect = settings.autoReconnect }
    LaunchedEffect(clientManager) { clientManager.confirmedCommand.collect {
        if (latestHapticEnabled) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    } }
    DisposableEffect(settings.keepScreenAwake) {
        val window = (context as? android.app.Activity)?.window
        if (settings.keepScreenAwake) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val connectionStatus by clientManager.connectionStatus.collectAsState()
    val discoveredCameras by clientManager.discoveredCameras.collectAsState()
    val cameraState by clientManager.cameraState.collectAsState()
    val liveFrame by clientManager.liveFrame.collectAsState()
    val rttMs by clientManager.rttMs.collectAsState()
    val errorMessage by clientManager.errorMessage.collectAsState()

    var showSettingsScreen by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0C0E12)
    ) {
        when {
            showSettingsScreen -> {
                // Blackmagic Camera Style Professional Settings Screen
                SettingsScreen(
                    clientManager = clientManager,
                    settingsRepo = settingsRepo,
                    onBack = { showSettingsScreen = false }
                )
            }

            showQrScanner -> {
                // Real Live Camera QR Scanner
                QrCameraScannerView(
                    onScanSuccess = { info ->
                        showQrScanner = false
                        clientManager.connect(info)
                    },
                    onError = { err ->
                        clientManager.setCustomError(err)
                    },
                    onClose = { showQrScanner = false }
                )
            }

            connectionStatus == ConnectionStatus.CONNECTED -> {
                // Live Viewfinder & Field Monitor Screen
                LiveMonitorView(
                    clientManager = clientManager,
                    cameraState = cameraState,
                    liveFrame = liveFrame,
                    rttMs = rttMs,
                    scaleFit = settings.scaleModeFit,
                    framingGuide = settings.framingGuide,
                    showRtt = settings.showRttTelemetry,
                    showBatteryStorage = settings.showBatteryStorageHud,
                    onToggleScale = { settingsRepo.updateScaleModeFit(!settings.scaleModeFit) },
                    onOpenSettings = { showSettingsScreen = true },
                    onDisconnect = { clientManager.disconnect() }
                )
            }

            else -> {
                // Connection Hub
                ConnectionHubView(
                    connectionStatus = connectionStatus,
                    discoveredCameras = discoveredCameras,
                    errorMessage = errorMessage,
                    onConnectCamera = { cam -> clientManager.connect(cam.host, cam.port) },
                    onManualConnect = { host, port, pin -> clientManager.connect(host, port, streamPort = port + 1, pin = pin) },
                    onStartQrScan = { showQrScanner = true },
                    onRefreshDiscovery = { clientManager.startDiscovery() },
                    onOpenSettings = { showSettingsScreen = true },
                    onDismissError = { clientManager.clearError() },
                    onCancelConnection = { clientManager.disconnect() }
                )
            }
        }
    }
}

@Composable
fun LiveMonitorView(
    clientManager: RemoteClientManager, cameraState: CameraStateSnapshot, liveFrame: Bitmap?, rttMs: Long,
    scaleFit: Boolean, framingGuide: FramingGuideMode, showRtt: Boolean, showBatteryStorage: Boolean,
    onToggleScale: () -> Unit, onOpenSettings: () -> Unit, onDisconnect: () -> Unit
) {
    val caps by clientManager.capabilities.collectAsState()
    val pending by clientManager.pendingCommands.collectAsState()
    val previewStatus by clientManager.previewStatus.collectAsState()
    val error by clientManager.errorMessage.collectAsState()
    fun allowed(type: CommandType) = clientManager.supports(type) && type !in pending
    fun send(type: CommandType, payload: String = "{}") = clientManager.sendCommand(type, payload)
    val minZoom = caps?.optDouble("minZoom", 1.0)?.toFloat() ?: 1f
    val maxZoom = caps?.optDouble("maxZoom", 1.0)?.toFloat()?.coerceAtLeast(minZoom) ?: minZoom
    val minEv = caps?.optInt("minExposureComp", 0) ?: 0
    val maxEv = caps?.optInt("maxExposureComp", 0)?.coerceAtLeast(minEv) ?: minEv
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        val landscape = maxWidth > maxHeight
        val preview: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                if (liveFrame != null) Image(liveFrame.asImageBitmap(), "Live camera preview", Modifier.fillMaxSize(),
                    contentScale = if (scaleFit) ContentScale.Fit else ContentScale.Crop)
                else Text(previewStatus, color = Color.White, modifier = Modifier.padding(16.dp))
                // JPEG pixels already carry host rotation/mirroring. Map FIT/FILL back to that same image.
                if (liveFrame != null) Box(Modifier.fillMaxSize().pointerInput(liveFrame.width, liveFrame.height, scaleFit, cameraState.isFocusLocked) {
                    detectTapGestures(onTap = { offset ->
                        val point = PreviewTouchMapper.map(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(),
                            liveFrame.width.toFloat(), liveFrame.height.toFloat(), scaleFit)
                        point?.let { p ->
                            if (allowed(CommandType.SET_FOCUS_POINT)) send(CommandType.SET_FOCUS_POINT, "{\"x\":${p.x},\"y\":${p.y}}")
                        }
                    }, onLongPress = { offset ->
                        PreviewTouchMapper.map(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(),
                            liveFrame.width.toFloat(), liveFrame.height.toFloat(), scaleFit)?.let { p ->
                            if (allowed(CommandType.SET_EXPOSURE_POINT)) send(CommandType.SET_EXPOSURE_POINT, "{\"x\":${p.x},\"y\":${p.y}}")
                        }
                    }, onDoubleTap = {
                        if (allowed(CommandType.LOCK_FOCUS)) send(CommandType.LOCK_FOCUS, "{\"locked\":${!cameraState.isFocusLocked}}")
                    })
                })
                if (liveFrame != null && framingGuide != FramingGuideMode.NONE) Canvas(Modifier.fillMaxSize()) {
                    val scale = if (scaleFit) minOf(size.width / liveFrame.width, size.height / liveFrame.height) else maxOf(size.width / liveFrame.width, size.height / liveFrame.height)
                    val w = liveFrame.width * scale; val h = liveFrame.height * scale
                    val left = (size.width - w) / 2; val top = (size.height - h) / 2
                    val color = Color.White.copy(alpha = .5f)
                    when (framingGuide) {
                        FramingGuideMode.THIRDS -> for (fraction in listOf(1f/3, 2f/3)) {
                            drawLine(color, Offset(left + w*fraction, maxOf(0f, top)), Offset(left+w*fraction, minOf(size.height, top+h)))
                            drawLine(color, Offset(maxOf(0f, left), top+h*fraction), Offset(minOf(size.width,left+w), top+h*fraction))
                        }
                        FramingGuideMode.CROSSHAIR -> { drawLine(color, Offset(size.width/2-16, size.height/2), Offset(size.width/2+16,size.height/2)); drawLine(color, Offset(size.width/2,size.height/2-16),Offset(size.width/2,size.height/2+16)) }
                        FramingGuideMode.SAFE_AREA_90 -> drawRect(color, Offset(left+w*.05f,top+h*.05f), Size(w*.9f,h*.9f), style=androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        else -> Unit
                    }
                }
                Text(previewStatus, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(6.dp))
            }
        }
        val controls: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.background(Color(0xFF161A20)).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (cameraState.isRecording) "REC ${formatTime(cameraState.recordingDurationSeconds)}" else "STBY",
                    color = if (cameraState.isRecording) Color.Red else Color.White, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onToggleScale) { Text(if (scaleFit) "FIT" else "FILL") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                if (showRtt || showBatteryStorage) Text(
                    listOfNotNull(if (showRtt) "$rttMs ms" else null, if (showBatteryStorage) "${cameraState.batteryPercent}% · ${cameraState.availableStorageMb} MB" else null).joinToString(" · "), color = Color.LightGray)
                error?.let { Text(it, color = Color(0xFFFF8A80)); TextButton(onClick = clientManager::clearError) { Text("Dismiss") } }
                if (caps == null) Text("Waiting for camera capabilities. Controls remain unavailable.", color = Color.LightGray)
                Button(onClick = { send(if (cameraState.isRecording) CommandType.STOP_RECORDING else CommandType.START_RECORDING) },
                    enabled = allowed(if (cameraState.isRecording) CommandType.STOP_RECORDING else CommandType.START_RECORDING) &&
                        CommandType.START_RECORDING !in pending && CommandType.STOP_RECORDING !in pending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (CommandType.START_RECORDING in pending || CommandType.STOP_RECORDING in pending) "Waiting for camera…" else if (cameraState.isRecording) "Stop recording" else "Start recording")
                }
                OutlinedButton(onClick = { send(CommandType.SWITCH_CAMERA, "{\"facing\":\"${if (cameraState.activeFacing == "BACK") "FRONT" else "BACK"}\"}") },
                    enabled = allowed(CommandType.SWITCH_CAMERA) && !cameraState.isRecording) { Text("Switch camera · ${cameraState.activeFacing}") }
                var zoom by remember(cameraState.zoomRatio) { mutableStateOf(cameraState.zoomRatio.coerceIn(minZoom, maxZoom)) }
                Text("Zoom ${"%.1f".format(zoom)}×", color = Color.White)
                Slider(value = zoom.coerceIn(minZoom, maxZoom), onValueChange = { zoom = it },
                    onValueChangeFinished = { send(CommandType.SET_ZOOM, "{\"zoom\":$zoom}") },
                    valueRange = minZoom..maxOf(maxZoom, minZoom + 0.001f), enabled = allowed(CommandType.SET_ZOOM) && maxZoom > minZoom)
                var ev by remember(cameraState.exposureCompensation) { mutableStateOf(cameraState.exposureCompensation.toFloat()) }
                Text("Exposure ${"%.2f".format(ev * (caps?.optDouble("exposureCompStep", 0.0) ?: 0.0))} EV", color = Color.White)
                Slider(ev.coerceIn(minEv.toFloat(), maxEv.toFloat()), { ev = it },
                    onValueChangeFinished = { send(CommandType.SET_EXPOSURE_COMPENSATION, "{\"index\":${ev.toInt()}}") },
                    valueRange = minEv.toFloat()..maxOf(maxEv.toFloat(), minEv + 0.001f), enabled = allowed(CommandType.SET_EXPOSURE_COMPENSATION) && maxEv > minEv)
                Row {
                    TextButton(onClick = { send(CommandType.LOCK_FOCUS, "{\"locked\":${!cameraState.isFocusLocked}}") }, enabled = allowed(CommandType.LOCK_FOCUS)) { Text(if (cameraState.isFocusLocked) "Unlock focus" else "Lock focus") }
                    TextButton(onClick = { send(CommandType.LOCK_EXPOSURE, "{\"locked\":${!cameraState.isExposureLocked}}") }, enabled = allowed(CommandType.LOCK_EXPOSURE)) { Text(if (cameraState.isExposureLocked) "Unlock AE" else "Lock AE") }
                }
                var iso by remember { mutableStateOf("400") }
                var shutter by remember { mutableStateOf("20000000") }
                OutlinedTextField(iso, { iso = it }, label = { Text("ISO · ${cameraState.currentIso} reported") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = allowed(CommandType.SET_MANUAL_EXPOSURE))
                OutlinedTextField(shutter, { shutter = it }, label = { Text("Shutter in ns · ${cameraState.currentExposureTimeNs}") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = allowed(CommandType.SET_MANUAL_EXPOSURE))
                TextButton(onClick = {
                    val i = iso.toIntOrNull(); val ns = shutter.toLongOrNull()
                    if (i == null || ns == null) clientManager.setCustomError("Enter whole numbers for ISO and shutter nanoseconds.")
                    else send(CommandType.SET_MANUAL_EXPOSURE, "{\"iso\":$i,\"exposureTimeNs\":$ns}")
                }, enabled = allowed(CommandType.SET_MANUAL_EXPOSURE)) { Text("Apply manual exposure") }
                var focus by remember { mutableStateOf(0f) }
                Text("Manual focus ${"%.2f".format(focus)} diopters", color = Color.White)
                Slider(focus, { focus = it }, onValueChangeFinished = { send(CommandType.SET_MANUAL_FOCUS, "{\"distance\":$focus}") },
                    valueRange = 0f..maxOf(0.001f, caps?.optDouble("maxFocusDiopters", 0.0)?.toFloat() ?: 0f), enabled = allowed(CommandType.SET_MANUAL_FOCUS))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("AUTO", "DAYLIGHT", "CLOUDY", "SHADE", "TUNGSTEN", "FLUORESCENT").forEach { mode ->
                        FilterChip(cameraState.whiteBalanceMode == mode, { send(CommandType.SET_WHITE_BALANCE_MODE, "{\"mode\":\"$mode\"}") },
                            label = { Text(mode) }, enabled = allowed(CommandType.SET_WHITE_BALANCE_MODE))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    val luts = caps?.optJSONArray("luts")
                    if (luts != null) for (index in 0 until luts.length()) {
                        val lut = luts.getJSONObject(index)
                        FilterChip(cameraState.activeLutName == lut.getString("id"), {
                            send(CommandType.SET_LUT, org.json.JSONObject().put("lutId", lut.getString("id")).put("intensity", 1.0).toString())
                        }, label = { Text(lut.getString("name")) }, enabled = allowed(CommandType.SET_LUT) && !cameraState.isRecording)
                    }
                }
                OutlinedButton(onClick = { send(CommandType.SET_TORCH, "{\"enabled\":${!cameraState.isTorchOn}}") }, enabled = allowed(CommandType.SET_TORCH)) { Text("Torch") }
                Text("Unavailable controls are disabled. Frame rate, resolution and presets are selected on the camera. Preview profile is fixed by the host.", color = Color.LightGray, fontSize = 12.sp)
                Text(caps?.optString("unsupportedReason").orEmpty(), color = Color.LightGray, fontSize = 12.sp)
            }
        }
        if (landscape) Row(Modifier.fillMaxSize()) { preview(Modifier.weight(1f).fillMaxHeight()); controls(Modifier.width(320.dp).fillMaxHeight().imePadding()) }
        else Column(Modifier.fillMaxSize()) { preview(Modifier.weight(1f).fillMaxWidth()); controls(Modifier.fillMaxWidth().heightIn(max = 340.dp).imePadding()) }
    }
}

@Composable
fun ConnectionHubView(
    connectionStatus: ConnectionStatus,
    discoveredCameras: List<DiscoveredCamera>,
    errorMessage: String?,
    onConnectCamera: (DiscoveredCamera) -> Unit,
    onManualConnect: (host: String, port: Int, pin: String) -> Unit,
    onStartQrScan: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onCancelConnection: () -> Unit
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("192.168.1.") }
    var manualPort by remember { mutableStateOf("8989") }
    var manualPin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_remote),
                    contentDescription = "ProCam Z Remote Logo",
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ProCam Z Remote",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF))
                        )
                    }
                    Text(
                        text = "Wireless Monitor & Control Console",
                        fontSize = 12.sp,
                        color = Color(0xFF90A4AE)
                    )
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF1E242C), CircleShape)
            ) {
                Text("⚙", color = Color(0xFF00E5FF), fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Error Banner
        if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1E20)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connection Issue", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(errorMessage, color = Color(0xFFECEFF1), fontSize = 13.sp)
                    }
                    IconButton(onClick = onDismissError) {
                        Text("✕", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }

        // Connecting / Approval Status Card
        if (connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.WAITING_APPROVAL) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2129)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = if (connectionStatus == ConnectionStatus.WAITING_APPROVAL)
                            "Waiting for camera approval on host..."
                        else
                            "Connecting to camera host...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onCancelConnection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        } else {
            // Hero Action: Scan QR Code Button
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF26323E), RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onStartQrScan,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("📷", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Scan QR Code",
                                color = Color.Black,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Point this device at the pairing QR code displayed on the ProCam Z camera screen for instant setup",
                        color = Color(0xFF78909C),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Discovered Cameras List Section
            Row(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DISCOVERED CAMERAS (${discoveredCameras.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF),
                    letterSpacing = 1.sp
                )
                TextButton(
                    onClick = onRefreshDiscovery,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Refresh", color = Color(0xFF00E5FF), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (discoveredCameras.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF222B36), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Searching local network...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ensure ProCam Z is open on the same Wi-Fi network or phone hotspot, or connect manually below.",
                            color = Color(0xFF78909C),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    discoveredCameras.forEach { cam ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2028)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF283340), RoundedCornerShape(10.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cam.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text("${cam.host}:${cam.port}", color = Color(0xFF00E5FF), fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { manualHost = cam.host; manualPort = cam.port.toString(); showManualDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .width(100.dp)
                                ) {
                                    Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Manual Address Button
            OutlinedButton(
                onClick = { showManualDialog = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Enter IP Address Manually", color = Color.White, fontSize = 13.sp)
            }
        }

        // Manual Connection Dialog
        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                containerColor = Color(0xFF161A20),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
                title = { Text("Manual Address Entry", fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("Host IP Address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text("Command Port (Default 8989)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = manualPin,
                            onValueChange = { manualPin = it },
                            label = { Text("6-Digit PIN (Shown on Camera)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val port = manualPort.toIntOrNull() ?: 8989
                            onManualConnect(manualHost.trim(), port, manualPin.trim())
                            showManualDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun formatTime(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
