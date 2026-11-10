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
                    onManualConnect = { host, port, pin -> clientManager.connect(host, port, pin = pin) },
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
    clientManager: RemoteClientManager,
    cameraState: CameraStateSnapshot,
    liveFrame: Bitmap?,
    rttMs: Long,
    scaleFit: Boolean,
    framingGuide: FramingGuideMode,
    showRtt: Boolean,
    showBatteryStorage: Boolean,
    onToggleScale: () -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Live Video Viewfinder Canvas & Reticle Area
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height).coerceIn(0f, 1f)
                            clientManager.sendCommand(
                                CommandType.SET_FOCUS_POINT,
                                "{\"x\":$normX,\"y\":$normY}"
                            )
                            clientManager.sendCommand(
                                CommandType.SET_EXPOSURE_POINT,
                                "{\"x\":$normX,\"y\":$normY}"
                            )
                        },
                        onDoubleTap = {
                            clientManager.sendCommand(
                                CommandType.LOCK_FOCUS,
                                "{\"locked\":${!cameraState.isFocusLocked}}"
                            )
                        }
                    )
                }
        ) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight

            if (liveFrame != null) {
                Image(
                    bitmap = liveFrame.asImageBitmap(),
                    contentDescription = "Live Camera Stream",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (scaleFit) ContentScale.Fit else ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(36.dp))
                    Text(
                        "Waiting for live video feed...",
                        color = Color(0xFF90A4AE),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 56.dp)
                    )
                }
            }

            // Framing Guides Overlay
            if (framingGuide != FramingGuideMode.NONE) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    when (framingGuide) {
                        FramingGuideMode.THIRDS -> {
                            val wThird = size.width / 3f
                            val hThird = size.height / 3f
                            val guideColor = Color.White.copy(alpha = 0.28f)
                            val stroke = 1.dp.toPx()

                            drawLine(guideColor, Offset(wThird, 0f), Offset(wThird, size.height), stroke)
                            drawLine(guideColor, Offset(wThird * 2f, 0f), Offset(wThird * 2f, size.height), stroke)
                            drawLine(guideColor, Offset(0f, hThird), Offset(size.width, hThird), stroke)
                            drawLine(guideColor, Offset(0f, hThird * 2f), Offset(size.width, hThird * 2f), stroke)
                        }
                        FramingGuideMode.CROSSHAIR -> {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val arm = 24.dp.toPx()
                            val guideColor = Color(0xFF00E5FF).copy(alpha = 0.6f)
                            val stroke = 1.5.dp.toPx()

                            drawLine(guideColor, Offset(cx - arm, cy), Offset(cx + arm, cy), stroke)
                            drawLine(guideColor, Offset(cx, cy - arm), Offset(cx, cy + arm), stroke)
                        }
                        FramingGuideMode.SAFE_AREA_90 -> {
                            val padX = size.width * 0.05f
                            val padY = size.height * 0.05f
                            drawRect(
                                color = Color.White.copy(alpha = 0.25f),
                                topLeft = Offset(padX, padY),
                                size = Size(size.width - padX * 2f, size.height - padY * 2f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                            )
                        }
                        else -> {}
                    }
                }
            }

            // Responsive Draggable Focus Reticle
            cameraState.focusPoint?.let { pt ->
                val rx = (pt.x * containerWidth.value).dp - 30.dp
                val ry = (pt.y * containerHeight.value).dp - 30.dp
                Box(
                    modifier = Modifier
                        .offset(x = rx.coerceAtLeast(0.dp), y = ry.coerceAtLeast(0.dp))
                        .size(60.dp)
                        .border(
                            2.dp,
                            if (cameraState.isFocusLocked) Color(0xFFFFB300) else Color(0xFF00E5FF),
                            RoundedCornerShape(6.dp)
                        )
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val newX = (pt.x + dragAmount.x / 1000f).coerceIn(0f, 1f)
                                val newY = (pt.y + dragAmount.y / 1000f).coerceIn(0f, 1f)
                                clientManager.sendCommand(
                                    CommandType.SET_FOCUS_POINT,
                                    "{\"x\":$newX,\"y\":$newY}"
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (cameraState.isFocusLocked) {
                        Text("🔒", fontSize = 13.sp)
                    } else {
                        Text("+", color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Light)
                    }
                }
            }

            // Responsive Draggable Exposure Metering Reticle
            cameraState.exposurePoint?.let { pt ->
                val ex = ((pt.x * containerWidth.value) + 24).dp
                val ey = ((pt.y * containerHeight.value) + 24).dp
                Box(
                    modifier = Modifier
                        .offset(x = ex.coerceAtLeast(0.dp), y = ey.coerceAtLeast(0.dp))
                        .size(46.dp)
                        .border(1.5.dp, Color(0xFFFFD54F), CircleShape)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val newX = (pt.x + dragAmount.x / 1000f).coerceIn(0f, 1f)
                                val newY = (pt.y + dragAmount.y / 1000f).coerceIn(0f, 1f)
                                clientManager.sendCommand(
                                    CommandType.SET_EXPOSURE_POINT,
                                    "{\"x\":$newX,\"y\":$newY}"
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("☀", color = Color(0xFFFFD54F), fontSize = 12.sp)
                }
            }
        }

        // 2. Top Telemetry Bar (Blackmagic Camera Inspired)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color(0xCC0E1114))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Record status & elapsed duration
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_remote),
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                )
                Spacer(Modifier.width(8.dp))
                if (cameraState.isRecording) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF1744))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "REC ${formatTime(cameraState.recordingDurationSeconds)}",
                        color = Color(0xFFFF1744),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF78909C))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "STBY",
                        color = Color(0xFF90A4AE),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Right: Studio status indicators and settings entry
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showBatteryStorage) {
                    Text(
                        text = "${cameraState.batteryPercent}% 🔋",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }

                if (showRtt) {
                    Text(
                        text = "${rttMs}ms",
                        color = if (rttMs < 60) Color(0xFF00E5FF) else Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .clickable(onClick = onToggleScale)
                        .background(Color(0xFF242C36), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (scaleFit) "FIT" else "FILL",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Settings Cog Icon Button
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(34.dp)
                ) {
                    Text("⚙", color = Color(0xFF00E5FF), fontSize = 18.sp)
                }

                // Disconnect Button
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(34.dp)
                ) {
                    Text("✕", color = Color(0xFFFF5252), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 3. Bottom Camera Controls Area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color(0xD90E1114))
                .padding(bottom = 8.dp)
        ) {
            // 3D LUT Look Chips Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "none" to "None",
                    "identity" to "Identity",
                    "natural" to "Natural",
                    "travel" to "Travel",
                    "warm_sunset" to "Sunset",
                    "cool_cinema" to "Cinema",
                    "mono" to "Mono",
                    "vintage_80s" to "Vintage 80s"
                ).forEach { (lutId, label) ->
                    val isSelected = cameraState.activeLutName.equals(lutId, ignoreCase = true) ||
                            cameraState.activeLutName.equals(label, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            clientManager.sendCommand(
                                CommandType.SET_LUT,
                                "{\"lutId\":\"$lutId\",\"intensity\":1.0}"
                            )
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E5FF),
                            selectedLabelColor = Color.Black,
                            containerColor = Color(0xFF1E242C),
                            labelColor = Color.White
                        )
                    )
                }
            }

            // Zoom Slider Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ZOOM", color = Color(0xFF78909C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = cameraState.zoomRatio,
                    onValueChange = { zoom ->
                        clientManager.sendCommand(
                            CommandType.SET_ZOOM,
                            "{\"zoom\":$zoom}"
                        )
                    },
                    valueRange = 1.0f..8.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
                Text(
                    "${"%.1f".format(cameraState.zoomRatio)}x",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Exposure EV Slider Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("EV", color = Color(0xFF78909C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Slider(
                    value = cameraState.exposureCompensation.toFloat(),
                    onValueChange = { ev ->
                        clientManager.sendCommand(
                            CommandType.SET_EXPOSURE_COMPENSATION,
                            "{\"index\":${ev.toInt()}}"
                        )
                    },
                    valueRange = -12f..12f,
                    steps = 23,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD54F),
                        activeTrackColor = Color(0xFFFFD54F)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
                Text(
                    "${cameraState.exposureCompensation}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Primary Control Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Torch toggle
                IconButton(
                    onClick = {
                        clientManager.sendCommand(
                            CommandType.SET_TORCH,
                            "{\"enabled\":${!cameraState.isTorchOn}}"
                        )
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF1E242C), CircleShape)
                ) {
                    Text(if (cameraState.isTorchOn) "🔦" else "💡", fontSize = 20.sp)
                }

                // Explicit Master Record / Stop Button
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (cameraState.isRecording) {
                                clientManager.sendCommand(CommandType.STOP_RECORDING)
                            } else {
                                clientManager.sendCommand(CommandType.START_RECORDING)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(if (cameraState.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                                .background(Color(0xFFFF1744))
                        )
                    }
                }

                // Camera Switch (Front/Back)
                IconButton(
                    onClick = {
                        val next = if (cameraState.activeFacing == "BACK") "FRONT" else "BACK"
                        clientManager.sendCommand(
                            CommandType.SWITCH_CAMERA,
                            "{\"facing\":\"$next\"}"
                        )
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF1E242C), CircleShape)
                ) {
                    Text("🔄", fontSize = 20.sp)
                }
            }
        }
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
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_remote),
                    contentDescription = "CamPro Z Remote Logo",
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CamPro Z Remote",
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
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
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
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
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
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
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
                        "Point this device at the pairing QR code displayed on the CamPro Z camera screen for instant setup",
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
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
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
                        .fillMaxWidth()
                        .widthIn(max = 480.dp)
                        .border(1.dp, Color(0xFF222B36), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Searching local network...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ensure CamPro Z is open on the same Wi-Fi network or phone hotspot, or connect manually below.",
                            color = Color(0xFF78909C),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp),
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
                                    onClick = { onConnectCamera(cam) },
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
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
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
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
