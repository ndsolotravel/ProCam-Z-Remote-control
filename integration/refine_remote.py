from pathlib import Path
root=Path(__file__).resolve().parents[1]/'campro-z/remoteApp/src/main/kotlin/org/ndsolotravel/camproz/remote'
p=root/'network/RemoteClientManager.kt'
t=p.read_text(encoding='utf-8')
t=t.replace('    private var retries = 0', '    private var streamRetry: Job? = null\n    private var streamRetries = 0\n    private var retries = 0')
t=t.replace('        stopDiscovery()\n', '        stopDiscovery()\n        _discoveredCameras.value = emptyList()\n',1)
t=t.replace('_liveFrame.value = null; previewStatus.value = "Preview interrupted; reconnect to recover"', '_liveFrame.value = null; previewStatus.value = "Preview interrupted; recovering…"\n                                        lastFrame = 0; streamSocket?.close(1001, "Stale preview")')
t=t.replace('lastFrame = SystemClock.elapsedRealtime(); previewStatus.value = "Live preview"', 'lastFrame = SystemClock.elapsedRealtime(); streamRetries = 0; previewStatus.value = "Live preview"')
t=t.replace('if (epoch == generation) { _liveFrame.value = null; previewStatus.value = "Preview disconnected; reconnect to recover" }', '''if (epoch == generation) {
                    _liveFrame.value = null; previewStatus.value = "Preview disconnected"
                    if (code != 1008 && _connectionStatus.value == ConnectionStatus.CONNECTED && streamRetries < 3) {
                        val waitMs = 1000L shl streamRetries++
                        streamRetry?.cancel()
                        streamRetry = scope.launch { delay(waitMs); if (epoch == generation) connectStreamSocket(epoch) }
                    }
                }''')
t=t.replace('heartbeat?.cancel(); pairingTimeout?.cancel()', 'heartbeat?.cancel(); pairingTimeout?.cancel(); streamRetry?.cancel(); streamRetries = 0')
p.write_text(t,encoding='utf-8')
p=root/'ui/QrCameraScannerView.kt'
t=p.read_text(encoding='utf-8')
t=t.replace('private var imageReader: android.media.ImageReader? = null', 'private var imageReader: android.media.ImageReader? = null\n                    private val disposed = AtomicBoolean(false)\n                    private var ownedPreviewSurface: Surface? = null')
t=t.replace('backgroundThread = HandlerThread("ScannerBackground")', 'disposed.set(false)\n                        backgroundThread = HandlerThread("ScannerBackground")')
t=t.replace('val yBytes = ByteArray(yBuffer.remaining())\n                                    yBuffer.get(yBytes)', '''val yBytes = ByteArray(image.width * image.height)
                                    val rowStride = planes[0].rowStride
                                    val pixelStride = planes[0].pixelStride
                                    val base = yBuffer.position()
                                    for (y in 0 until image.height) for (x in 0 until image.width) {
                                        yBytes[y * image.width + x] = yBuffer.get(base + y * rowStride + x * pixelStride)
                                    }''')
t=t.replace('onScanSuccess(validation.info)', 'android.os.Handler(android.os.Looper.getMainLooper()).post { if (!disposed.get()) onScanSuccess(validation.info) }')
t=t.replace('val previewSurface = Surface(st)', 'val previewSurface = Surface(st)\n                        ownedPreviewSurface = previewSurface')
t=t.replace('override fun onOpened(camera: CameraDevice) {\n                                    cameraDevice = camera', 'override fun onOpened(camera: CameraDevice) {\n                                    if (disposed.get()) { camera.close(); return }\n                                    cameraDevice = camera')
t=t.replace('override fun onConfigured(session: CameraCaptureSession) {\n                                                    captureSession = session', 'override fun onConfigured(session: CameraCaptureSession) {\n                                                    if (disposed.get()) { session.close(); return }\n                                                    captureSession = session')
t=t.replace('override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {\n                        try {', 'override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {\n                        disposed.set(true)\n                        try {')
t=t.replace('backgroundThread?.quitSafely()', 'ownedPreviewSurface?.release(); ownedPreviewSurface = null\n                            backgroundThread?.quitSafely()')
p.write_text(t,encoding='utf-8')
print('Added bounded preview recovery and scanner resource cleanup')
