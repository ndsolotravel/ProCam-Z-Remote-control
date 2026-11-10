package org.ndsolotravel.camproz.remote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.ndsolotravel.camproz.protocol.SecurityHandshake
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCameraScannerView(
    onScanSuccess: (SecurityHandshake.PairingInfo) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedPermanently by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            permissionDeniedPermanently = true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            Camera2QrScanner(
                onScanSuccess = onScanSuccess,
                onError = onError
            )

            // Viewfinder Reticle Overlay & Laser Animation
            ScannerHudOverlay(onClose = onClose)
        } else {
            // Permission explanation UI
            PermissionExplanationView(
                isPermanentlyDenied = permissionDeniedPermanently,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                onClose = onClose
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun Camera2QrScanner(
    onScanSuccess: (SecurityHandshake.PairingInfo) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val hasScanned = remember { AtomicBoolean(false) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var cameraDevice: CameraDevice? = null
                    private var captureSession: CameraCaptureSession? = null
                    private var backgroundThread: HandlerThread? = null
                    private var backgroundHandler: Handler? = null
                    private var imageReader: android.media.ImageReader? = null

                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        backgroundThread = HandlerThread("ScannerBackground").apply { start() }
                        backgroundHandler = Handler(backgroundThread!!.looper)

                        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        val cameraId = manager.cameraIdList.firstOrNull { id ->
                            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                        } ?: manager.cameraIdList.firstOrNull()

                        if (cameraId == null) {
                            onError("No camera available on this device")
                            return
                        }

                        // Configure 640x480 YUV frame stream for QR decoding
                        val scanWidth = 640
                        val scanHeight = 480
                        imageReader = android.media.ImageReader.newInstance(
                            scanWidth, scanHeight, ImageFormat.YUV_420_888, 2
                        ).apply {
                            setOnImageAvailableListener({ reader ->
                                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                                if (hasScanned.get()) {
                                    image.close()
                                    return@setOnImageAvailableListener
                                }

                                try {
                                    val planes = image.planes
                                    val yBuffer = planes[0].buffer
                                    val yBytes = ByteArray(yBuffer.remaining())
                                    yBuffer.get(yBytes)

                                    val source = PlanarYUVLuminanceSource(
                                        yBytes, image.width, image.height,
                                        0, 0, image.width, image.height, false
                                    )
                                    val bitmap = BinaryBitmap(HybridBinarizer(source))

                                    val hints = mapOf(
                                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                        DecodeHintType.CHARACTER_SET to "UTF-8"
                                    )
                                    val readerZ = MultiFormatReader()
                                    val result = readerZ.decode(bitmap, hints)

                                    if (result != null && result.text.isNotEmpty() && !hasScanned.getAndSet(true)) {
                                        val payload = result.text
                                        when (val validation = SecurityHandshake.PairingInfo.validateQrPayload(payload)) {
                                            is SecurityHandshake.QrValidationResult.Success -> {
                                                onScanSuccess(validation.info)
                                            }
                                            is SecurityHandshake.QrValidationResult.Expired -> {
                                                hasScanned.set(false) // allow rescan after error
                                                onError("QR code expired. Please generate a fresh QR code on the camera.")
                                            }
                                            is SecurityHandshake.QrValidationResult.UnsupportedVersion -> {
                                                hasScanned.set(false)
                                                onError("Unsupported protocol version: ${validation.foundVersion}. Please update both apps.")
                                            }
                                            is SecurityHandshake.QrValidationResult.InvalidPayload -> {
                                                hasScanned.set(false)
                                                onError("Invalid QR code: ${validation.reason}")
                                            }
                                        }
                                    }
                                } catch (_: NotFoundException) {
                                    // Normal frame without QR
                                } catch (e: Exception) {
                                    // Decoding frame error
                                } finally {
                                    image.close()
                                }
                            }, backgroundHandler)
                        }

                        st.setDefaultBufferSize(scanWidth, scanHeight)
                        val previewSurface = Surface(st)
                        val readerSurface = imageReader!!.surface

                        try {
                            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                                override fun onOpened(camera: CameraDevice) {
                                    cameraDevice = camera
                                    try {
                                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(previewSurface)
                                            addTarget(readerSurface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }

                                        camera.createCaptureSession(
                                            listOf(previewSurface, readerSurface),
                                            object : CameraCaptureSession.StateCallback() {
                                                override fun onConfigured(session: CameraCaptureSession) {
                                                    captureSession = session
                                                    try {
                                                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                                    } catch (_: Exception) {}
                                                }
                                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                                    onError("Failed to configure QR scanner camera session")
                                                }
                                            },
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        onError("Camera preview start error: ${e.message}")
                                    }
                                }

                                override fun onDisconnected(camera: CameraDevice) {
                                    camera.close()
                                    cameraDevice = null
                                }

                                override fun onError(camera: CameraDevice, error: Int) {
                                    camera.close()
                                    cameraDevice = null
                                    onError("Camera error code: $error")
                                }
                            }, backgroundHandler)
                        } catch (e: Exception) {
                            onError("Failed to open camera: ${e.message}")
                        }
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        try {
                            captureSession?.close()
                            cameraDevice?.close()
                            imageReader?.close()
                            backgroundThread?.quitSafely()
                        } catch (_: Exception) {}
                        return true
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ScannerHudOverlay(onClose: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "Laser")
    val laserY by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LaserY"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Darkened vignette and target box cutout
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSize = size.minDimension * 0.65f
            val left = (size.width - boxSize) / 2f
            val top = (size.height - boxSize) / 2f

            // Shaded border around target
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                size = size
            )

            // Clear target window
            drawRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(boxSize, boxSize),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )

            // Animated Laser Line
            val currentLaserY = top + (boxSize * laserY)
            drawLine(
                color = Color(0xFF00E5FF),
                start = Offset(left + 8f, currentLaserY),
                end = Offset(left + boxSize - 8f, currentLaserY),
                strokeWidth = 3.dp.toPx()
            )
        }

        // Center reticle border corners
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .border(2.dp, Color(0xFF00E5FF).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
        )

        // Top bar with close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Scan CamPro Z QR Code",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Bottom instruction card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xDD1E262C)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Point camera at the QR code displayed on the CamPro Z camera screen",
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Keep within the cyan frame for instant pairing",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PermissionExplanationView(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📷", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Camera Permission Needed",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (isPermanentlyDenied)
                "Camera access was denied. Please open system settings and allow camera access to scan the pairing QR code."
            else
                "CamPro Z Remote needs camera permission to scan the host QR code and establish a zero-trust connection.",
            color = Color(0xFFB0BEC5),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))

        if (isPermanentlyDenied) {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Open App Settings", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Grant Camera Permission", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Cancel", color = Color.White)
        }
    }
}
