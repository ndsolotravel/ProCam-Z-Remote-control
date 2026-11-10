package org.ndsolotravel.apps.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ndsolotravel.camproz.protocol.*
import java.io.File
import kotlin.math.roundToInt

/**
 * Single authoritative Camera2 engine for CamPro Z.
 * Serializes camera commands, manages preview and recording pipelines,
 * and publishes synchronized state snapshots to local UI and remote companion.
 */
class CameraEngine(
    private val context: Context,
    val glRenderer: CameraGlRenderer,
    val lutManager: LutManager
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val recordingManager = CameraRecordingManager(context)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private var activeCameraId: String = ""
    private var activeCharacteristics: CameraCharacteristics? = null

    private val _capabilities = MutableStateFlow(CameraCapabilities())
    val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    private val _state = MutableStateFlow(CameraStateSnapshot())
    val state: StateFlow<CameraStateSnapshot> = _state.asStateFlow()

    private var stateRevision = 1L
    private val appliedCommands = HashSet<String>() // Deduplication

    // Current settings
    private var currentFacing = "BACK"
    private var currentZoom = 1.0f
    private var currentFocusPoint: NormalizedPoint? = null
    private var isFocusLocked = false
    private var manualFocusDist: Float? = null
    private var currentExpPoint: NormalizedPoint? = null
    private var isExpLocked = false
    private var currentExpComp = 0
    private var currentIso = 100
    private var currentExpTimeNs = 20_000_000L
    private var currentWbMode = "AUTO"
    private var isTorchOn = false
    private var currentLutName = "None"
    private var currentLutIntensity = 1.0f
    private var previewQuality = "720p"

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun openCamera(facing: String = "BACK") {
        currentFacing = facing
        val cameraId = findCameraIdForFacing(facing) ?: return
        activeCameraId = cameraId
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        activeCharacteristics = chars

        discoverCapabilities(chars)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.i("CameraEngine", "Camera opened: $cameraId ($facing)")
                    startPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    Log.w("CameraEngine", "Camera disconnected: $cameraId")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e("CameraEngine", "Camera error: $error on $cameraId")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("CameraEngine", "Failed to open camera: ${e.message}", e)
        }
    }

    private fun findCameraIdForFacing(facing: String): String? {
        val targetLensFacing = if (facing == "FRONT") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing == targetLensFacing) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun discoverCapabilities(chars: CameraCharacteristics) {
        val availableFacings = mutableListOf<String>()
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK && !availableFacings.contains("BACK")) {
                availableFacings.add("BACK")
            }
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT && !availableFacings.contains("FRONT")) {
                availableFacings.add("FRONT")
            }
        }

        val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 8.0f
        } else {
            chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8.0f
        }

        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val supportsManualFocus = minFocusDist > 0f

        val expCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val expCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.333f

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        val caps = CameraCapabilities(
            activeFacing = currentFacing,
            availableFacings = availableFacings,
            minZoom = 1.0f,
            maxZoom = maxZoom,
            supportsManualFocus = supportsManualFocus,
            minFocusDistance = minFocusDist,
            supportsExposureLock = true,
            minExposureComp = expCompRange?.lower ?: -12,
            maxExposureComp = expCompRange?.upper ?: 12,
            exposureCompStep = expCompStep,
            minIso = isoRange?.lower ?: 50,
            maxIso = isoRange?.upper ?: 3200,
            minExposureTimeNs = expTimeRange?.lower ?: 100_000L,
            maxExposureTimeNs = expTimeRange?.upper ?: 1_000_000_000L,
            supportsTorch = flashAvailable,
            supportedLuts = lutManager.getAvailableLuts()
        )
        _capabilities.value = caps
        Log.i("CameraEngine", "Discovered capabilities for $currentFacing: zoom 1.0..$maxZoom, manualFocus=$supportsManualFocus")
    }

    private fun startPreviewSession(recordingSurface: Surface? = null) {
        val device = cameraDevice ?: return
        val st = glRenderer.surfaceTexture ?: return

        st.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(st)

        val surfaces = mutableListOf<Surface>(previewSurface)
        if (recordingSurface != null) {
            surfaces.add(recordingSurface)
        }

        try {
            previewRequestBuilder = device.createCaptureRequest(
                if (recordingSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)
                if (recordingSurface != null) {
                    addTarget(recordingSurface)
                }
                applyActiveSettings(this)
            }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val req = previewRequestBuilder?.build()
                    if (req != null) {
                        session.setRepeatingRequest(req, null, backgroundHandler)
                    }
                    updateSnapshot()
                    Log.d("CameraEngine", "Capture session configured with ${surfaces.size} surfaces")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("CameraEngine", "Capture session configuration failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("CameraEngine", "Error creating capture session: ${e.message}", e)
        }
    }

    private fun applyActiveSettings(builder: CaptureRequest.Builder) {
        val chars = activeCharacteristics ?: return

        // 1. Zoom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
        } else {
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensorRect != null) {
                val cropWidth = (sensorRect.width() / currentZoom).toInt()
                val cropHeight = (sensorRect.height() / currentZoom).toInt()
                val cropLeft = (sensorRect.width() - cropWidth) / 2
                val cropTop = (sensorRect.height() - cropHeight) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight))
            }
        }

        // 2. Focus
        if (manualFocusDist != null && _capabilities.value.supportsManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDist)
        } else if (isFocusLocked) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        } else if (currentFocusPoint != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            val rect = calculateMeteringRect(currentFocusPoint!!)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // 3. Exposure
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExpComp)
        if (isExpLocked) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            if (currentExpPoint != null) {
                val rect = calculateMeteringRect(currentExpPoint!!)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            }
        }

        // 4. White Balance
        val wbMode = when (currentWbMode) {
            "DAYLIGHT" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            "CLOUDY" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            "INCANDESCENT" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            "FLUORESCENT" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)

        // 5. Flash / Torch
        if (isTorchOn && _capabilities.value.supportsTorch) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    private fun calculateMeteringRect(norm: NormalizedPoint): MeteringRectangle {
        val chars = activeCharacteristics ?: return MeteringRectangle(0, 0, 100, 100, MeteringRectangle.METERING_WEIGHT_MAX)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 1920, 1080)
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val isFront = currentFacing == "FRONT"

        val sensorBounds = CoordinateTransformer.SensorBounds(activeArray.left, activeArray.top, activeArray.width(), activeArray.height())
        val (sx, sy) = CoordinateTransformer.normalizedToSensorCoordinate(
            normPoint = norm,
            sensorArray = sensorBounds,
            zoomCrop = sensorBounds,
            sensorOrientation = orientation,
            isFrontCamera = isFront
        )
        val box = CoordinateTransformer.createMeteringRect(sx, sy, 250, sensorBounds)
        return MeteringRectangle(box.left, box.top, box.width, box.height, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun submitActiveSettings() {
        backgroundHandler?.post {
            val session = captureSession ?: return@post
            val builder = previewRequestBuilder ?: return@post
            applyActiveSettings(builder)
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                updateSnapshot()
            } catch (e: Exception) {
                Log.e("CameraEngine", "Failed to update repeating request: ${e.message}")
            }
        }
    }

    // ----------------------------------------------------
    // Validated Control Layer (Local UI & Remote bridge)
    // ----------------------------------------------------

    fun executeCommand(command: CommandMessage): CommandResult {
        // 1. Deduplication
        if (appliedCommands.contains(command.commandId)) {
            Log.d("CameraEngine", "Ignoring duplicate command ${command.commandId}")
            return CommandResult(command.commandId, true, stateRevision)
        }

        return try {
            when (command.type) {
                CommandType.START_RECORDING -> {
                    startRecordingInternal()
                }
                CommandType.STOP_RECORDING -> {
                    stopRecordingInternal()
                }
                CommandType.SET_ZOOM -> {
                    val ratio = ProtocolCodec.extractFloat(command.payloadJson, "zoom") ?: 1.0f
                    setZoomInternal(ratio)
                }
                CommandType.SET_FOCUS_POINT -> {
                    val x = ProtocolCodec.extractFloat(command.payloadJson, "x") ?: 0.5f
                    val y = ProtocolCodec.extractFloat(command.payloadJson, "y") ?: 0.5f
                    setFocusPointInternal(NormalizedPoint(x, y))
                }
                CommandType.LOCK_FOCUS -> {
                    val locked = ProtocolCodec.extractBoolean(command.payloadJson, "locked") ?: false
                    lockFocusInternal(locked)
                }
                CommandType.SET_MANUAL_FOCUS -> {
                    val dist = ProtocolCodec.extractFloat(command.payloadJson, "distance")
                    setManualFocusInternal(dist)
                }
                CommandType.SET_EXPOSURE_POINT -> {
                    val x = ProtocolCodec.extractFloat(command.payloadJson, "x") ?: 0.5f
                    val y = ProtocolCodec.extractFloat(command.payloadJson, "y") ?: 0.5f
                    setExposurePointInternal(NormalizedPoint(x, y))
                }
                CommandType.LOCK_EXPOSURE -> {
                    val locked = ProtocolCodec.extractBoolean(command.payloadJson, "locked") ?: false
                    lockExposureInternal(locked)
                }
                CommandType.SET_EXPOSURE_COMPENSATION -> {
                    val ev = ProtocolCodec.extractInt(command.payloadJson, "ev") ?: 0
                    setExposureCompInternal(ev)
                }
                CommandType.SET_WHITE_BALANCE_MODE -> {
                    val mode = ProtocolCodec.extractString(command.payloadJson, "mode") ?: "AUTO"
                    setWhiteBalanceInternal(mode)
                }
                CommandType.SET_TORCH -> {
                    val on = ProtocolCodec.extractBoolean(command.payloadJson, "enabled") ?: false
                    setTorchInternal(on)
                }
                CommandType.SWITCH_CAMERA -> {
                    val facing = ProtocolCodec.extractString(command.payloadJson, "facing") ?: "BACK"
                    switchCameraInternal(facing)
                }
                CommandType.SET_LUT -> {
                    val name = ProtocolCodec.extractString(command.payloadJson, "name") ?: "None"
                    val intensity = ProtocolCodec.extractFloat(command.payloadJson, "intensity") ?: 1.0f
                    setLutInternal(name, intensity)
                }
                CommandType.SET_STREAM_PROFILE -> {
                    val profile = ProtocolCodec.extractString(command.payloadJson, "quality") ?: "720p"
                    setStreamProfileInternal(profile)
                }
                CommandType.REQUEST_SNAPSHOT -> {
                    updateSnapshot()
                }
                CommandType.DISCONNECT -> {
                    Log.i("CameraEngine", "Client requested disconnect")
                }
                else -> {
                    Log.w("CameraEngine", "Unhandled command: ${command.type}")
                }
            }
            appliedCommands.add(command.commandId)
            CommandResult(command.commandId, true, stateRevision)
        } catch (e: Exception) {
            Log.e("CameraEngine", "Command failed: ${e.message}", e)
            CommandResult(command.commandId, false, stateRevision, e.message)
        }
    }

    private fun startRecordingInternal() {
        if (recordingManager.isRecording) return
        val surface = recordingManager.prepareRecorder()
        startPreviewSession(surface)
        recordingManager.startRecording()
        updateSnapshot()
    }

    private fun stopRecordingInternal() {
        if (!recordingManager.isRecording) return
        recordingManager.stopRecording()
        startPreviewSession(null) // Return to preview only
        updateSnapshot()
    }

    fun setZoomInternal(ratio: Float) {
        val clamped = ratio.coerceIn(_capabilities.value.minZoom, _capabilities.value.maxZoom)
        currentZoom = clamped
        submitActiveSettings()
    }

    fun setFocusPointInternal(point: NormalizedPoint) {
        if (!point.isValid()) return
        currentFocusPoint = point
        manualFocusDist = null
        submitActiveSettings()
    }

    fun lockFocusInternal(locked: Boolean) {
        isFocusLocked = locked
        submitActiveSettings()
    }

    fun setManualFocusInternal(distance: Float?) {
        manualFocusDist = distance
        currentFocusPoint = null
        submitActiveSettings()
    }

    fun setExposurePointInternal(point: NormalizedPoint) {
        if (!point.isValid()) return
        currentExpPoint = point
        isExpLocked = false
        submitActiveSettings()
    }

    fun lockExposureInternal(locked: Boolean) {
        isExpLocked = locked
        submitActiveSettings()
    }

    fun setExposureCompInternal(ev: Int) {
        val clamped = ev.coerceIn(_capabilities.value.minExposureComp, _capabilities.value.maxExposureComp)
        currentExpComp = clamped
        submitActiveSettings()
    }

    fun setWhiteBalanceInternal(mode: String) {
        currentWbMode = mode
        submitActiveSettings()
    }

    fun setTorchInternal(on: Boolean) {
        isTorchOn = on
        submitActiveSettings()
    }

    fun switchCameraInternal(facing: String) {
        if (recordingManager.isRecording) {
            Log.w("CameraEngine", "Cannot switch cameras during recording")
            return
        }
        if (facing == currentFacing) return
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        openCamera(facing)
    }

    fun setLutInternal(lutName: String, intensity: Float) {
        currentLutName = lutName
        currentLutIntensity = intensity.coerceIn(0f, 1f)
        val lutData = lutManager.loadLut(lutName)
        glRenderer.setLut(lutData)
        glRenderer.lutIntensity = currentLutIntensity
        updateSnapshot()
    }

    fun setStreamProfileInternal(profile: String) {
        previewQuality = profile
        when (profile) {
            "360p" -> {
                glRenderer.streamQualityWidth = 640
                glRenderer.streamQualityHeight = 360
            }
            "540p" -> {
                glRenderer.streamQualityWidth = 960
                glRenderer.streamQualityHeight = 540
            }
            "720p" -> {
                glRenderer.streamQualityWidth = 1280
                glRenderer.streamQualityHeight = 720
            }
            else -> { // AUTO
                glRenderer.streamQualityWidth = 960
                glRenderer.streamQualityHeight = 540
            }
        }
        updateSnapshot()
    }

    fun updateSnapshot() {
        stateRevision++
        val snap = CameraStateSnapshot(
            stateRevision = stateRevision,
            activeFacing = currentFacing,
            isRecording = recordingManager.isRecording,
            recordingDurationSeconds = recordingManager.currentDurationSeconds,
            recordedBytes = recordingManager.currentRecordedBytes,
            outputFileName = recordingManager.currentFileName,
            zoomRatio = currentZoom,
            focusPoint = currentFocusPoint,
            isFocusLocked = isFocusLocked,
            manualFocusDistance = manualFocusDist,
            exposurePoint = currentExpPoint,
            isExposureLocked = isExpLocked,
            exposureCompensation = currentExpComp,
            currentIso = currentIso,
            currentExposureTimeNs = currentExpTimeNs,
            whiteBalanceMode = currentWbMode,
            isTorchOn = isTorchOn,
            activeLutName = currentLutName,
            lutIntensity = currentLutIntensity,
            previewQuality = previewQuality,
            batteryPercent = 95,
            isCharging = false,
            availableStorageMb = 25000L,
            estimatedRecordMinutes = 180L,
            thermalStatus = "NORMAL"
        )
        _state.value = snap
    }

    fun release() {
        recordingManager.stopRecording()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
    }
}
