package org.ndsolotravel.apps.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.ndsolotravel.apps.R
import org.ndsolotravel.apps.camera.CameraEngine
import org.ndsolotravel.apps.network.CamProZHostServer
import org.ndsolotravel.camproz.protocol.NormalizedPoint
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
fun CamProZCameraScreen(
    cameraEngine: CameraEngine,
    hostServer: CamProZHostServer
) {
    val state by cameraEngine.state.collectAsState()
    val caps by cameraEngine.capabilities.collectAsState()

    var showPairingDialog by remember { mutableStateOf(false) }
    var pendingClientName by remember { mutableStateOf<String?>(null) }
    var pendingApprovalCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        hostServer.onClientApprovalRequest = { clientName, onDecision ->
            pendingClientName = clientName
            pendingApprovalCallback = onDecision
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. OpenGL ES Viewfinder
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(object : GLSurfaceView.Renderer {
                        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                            cameraEngine.glRenderer.initGl()
                            cameraEngine.openCamera("BACK")
                        }

                        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {}

                        override fun onDrawFrame(gl: GL10?) {
                            cameraEngine.glRenderer.drawFrame(width, height)
                        }
                    })
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val normX = (offset.x / size.width).coerceIn(0f, 1f)
                            val normY = (offset.y / size.height).coerceIn(0f, 1f)
                            cameraEngine.setFocusPointInternal(NormalizedPoint(normX, normY))
                            cameraEngine.setExposurePointInternal(NormalizedPoint(normX, normY))
                        },
                        onDoubleTap = {
                            cameraEngine.lockFocusInternal(!state.isFocusLocked)
                        }
                    )
                }
        )

        // 2. Focus and Exposure Overlay Box
        state.focusPoint?.let { pt ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pt.x * 1000).toInt(), // Visual marker
                                (pt.y * 1000).toInt()
                            )
                        }
                        .size(64.dp)
                        .border(
                            2.dp,
                            if (state.isFocusLocked) Color(0xFFFFB300) else Color(0xFF00E676),
                            RoundedCornerShape(4.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isFocusLocked) {
                        Text("🔒", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Top Telemetry Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App branding
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo_camproz),
                    contentDescription = "CamPro Z Logo",
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "CamPro Z",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.isRecording) "REC  ${formatTime(state.recordingDurationSeconds)}" else "STANDBY 1080p30",
                        color = if (state.isRecording) Color(0xFFFF1744) else Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                }
            }

            // Remote status & pairing button
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clientConnected = hostServer.activeClientSocket != null
                Button(
                    onClick = { showPairingDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (clientConnected) Color(0xFF1B5E20) else Color(0xFF263238)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (clientConnected) "Remote Connected" else "Pair Remote",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 4. Bottom Controls Area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color(0xCC000000))
                .padding(bottom = 16.dp)
        ) {
            // Manual Quick Strip: LUT Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                caps.supportedLuts.forEach { lut ->
                    val isSelected = state.activeLutName == lut
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            cameraEngine.setLutInternal(lut, 1.0f)
                        },
                        label = { Text(lut, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00E676),
                            selectedLabelColor = Color.Black,
                            containerColor = Color(0xFF212121),
                            labelColor = Color.White
                        )
                    )
                }
            }

            // Zoom Slider
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("1x", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = state.zoomRatio,
                    onValueChange = { cameraEngine.setZoomInternal(it) },
                    valueRange = caps.minZoom..caps.maxZoom,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text("${caps.maxZoom.toInt()}x", color = Color.White, fontSize = 12.sp)
            }

            // Primary Record / Stop Button Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Torch toggle
                IconButton(
                    onClick = { cameraEngine.setTorchInternal(!state.isTorchOn) }
                ) {
                    Text(if (state.isTorchOn) "🔦" else "💡", fontSize = 24.sp)
                }

                // Master Record Button
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .background(Color.Transparent)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            if (state.isRecording) {
                                cameraEngine.executeCommand(
                                    org.ndsolotravel.camproz.protocol.CommandMessage(
                                        sessionId = "local",
                                        commandId = java.util.UUID.randomUUID().toString(),
                                        type = org.ndsolotravel.camproz.protocol.CommandType.STOP_RECORDING
                                    )
                                )
                            } else {
                                cameraEngine.executeCommand(
                                    org.ndsolotravel.camproz.protocol.CommandMessage(
                                        sessionId = "local",
                                        commandId = java.util.UUID.randomUUID().toString(),
                                        type = org.ndsolotravel.camproz.protocol.CommandType.START_RECORDING
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(if (state.isRecording) RoundedCornerShape(8.dp) else CircleShape)
                                .background(Color(0xFFFF1744))
                        )
                    }
                }

                // Camera Switch (Front/Back)
                IconButton(
                    onClick = {
                        val next = if (state.activeFacing == "BACK") "FRONT" else "BACK"
                        cameraEngine.switchCameraInternal(next)
                    }
                ) {
                    Text("🔄", fontSize = 24.sp)
                }
            }
        }

        // 5. Pairing Dialog (Host Side)
        if (showPairingDialog) {
            var currentPairing by remember { mutableStateOf(hostServer.currentPairingInfo) }
            AlertDialog(
                onDismissRequest = { showPairingDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo_camproz),
                            contentDescription = "CamPro Z Logo",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Pair CamPro Z Remote", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!hostServer.isHostNetworkReady() || currentPairing?.hostAddress == "0.0.0.0") {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Text(
                                    "⚠️ No active Wi-Fi or Hotspot network detected. Please connect to Wi-Fi or enable Mobile Hotspot to connect.",
                                    color = Color(0xFFE65100),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Text("Scan this QR code from CamPro Z Remote:", fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        currentPairing?.let { p ->
                            // High-contrast QR Container with dedicated white quiet zone
                            val qrPayload = remember(p) { p.toQrPayload() }
                            val qrBitmap = remember(qrPayload) {
                                generateQrBitmap(qrPayload, 512)?.asImageBitmap()
                            }
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .size(210.dp)
                                        .background(Color.White, RoundedCornerShape(12.dp))
                                        .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "Pairing QR Code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            // Host Details
                            Text("Host IP: ${p.hostAddress}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp)
                            Text("Command Port: ${p.commandPort} | Stream: ${p.streamPort}", fontSize = 12.sp, color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFECEFF1)),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("6-DIGIT PAIRING PIN", fontSize = 11.sp, color = Color.Gray)
                                    Text(
                                        p.pinCode,
                                        fontSize = 26.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                                        letterSpacing = 4.sp
                                    )
                                    Text("Valid for 5 mins • One-time secure session", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }

                        if (hostServer.activeClientSocket != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    hostServer.revokeCurrentClient()
                                    showPairingDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                modifier = Modifier.fillMaxWidth().height(44.dp)
                            ) {
                                Text("Revoke Remote Access", color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            currentPairing = hostServer.refreshPairingInfo()
                        }) {
                            Text("Refresh Code", color = Color(0xFF0288D1))
                        }
                        TextButton(onClick = { showPairingDialog = false }) {
                            Text("Close")
                        }
                    }
                }
            )
        }

        // 6. Incoming Approval Prompt Dialog
        pendingClientName?.let { client ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Remote Control Request") },
                text = {
                    Text("Allow \"$client\" to view live preview and control this camera?")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingApprovalCallback?.invoke(true)
                            pendingClientName = null
                            pendingApprovalCallback = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("Approve")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            pendingApprovalCallback?.invoke(false)
                            pendingClientName = null
                            pendingApprovalCallback = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        Text("Deny")
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

private fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
    return try {
        val hints = java.util.EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java).apply {
            put(com.google.zxing.EncodeHintType.MARGIN, 2)
            put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        }
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
