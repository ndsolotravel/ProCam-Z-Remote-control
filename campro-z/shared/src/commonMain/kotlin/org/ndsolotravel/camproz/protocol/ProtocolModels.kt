package org.ndsolotravel.camproz.protocol

enum class CommandType {
    START_RECORDING,
    STOP_RECORDING,
    SET_ZOOM,
    SET_FOCUS_POINT,
    LOCK_FOCUS,
    SET_MANUAL_FOCUS,
    SET_EXPOSURE_POINT,
    LOCK_EXPOSURE,
    SET_EXPOSURE_COMPENSATION,
    SET_MANUAL_EXPOSURE,
    SET_WHITE_BALANCE_MODE,
    SET_TORCH,
    SWITCH_CAMERA,
    SET_LUT,
    SET_STREAM_PROFILE,
    REQUEST_SNAPSHOT,
    DISCONNECT
}

enum class ConnectionStatus {
    DISCONNECTED,
    DISCOVERED,
    CONNECTING,
    PAIRING_REQUESTED,
    WAITING_APPROVAL,
    CONNECTED,
    REJECTED,
    REVOKED
}

data class NormalizedPoint(
    val x: Float, // 0.0 to 1.0
    val y: Float  // 0.0 to 1.0
) {
    fun isValid(): Boolean = x in 0f..1f && y in 0f..1f
}

data class CommandMessage(
    val protocolVersion: String = "1.0",
    val sessionId: String,
    val commandId: String,
    val type: CommandType,
    val payloadJson: String = "{}",
    val timestampMs: Long = 0L
)

data class CommandResult(
    val commandId: String,
    val success: Boolean,
    val appliedStateRevision: Long,
    val errorMessage: String? = null
)

data class CameraCapabilities(
    val activeFacing: String = "BACK",
    val availableFacings: List<String> = listOf("BACK", "FRONT"),
    val minZoom: Float = 1.0f,
    val maxZoom: Float = 8.0f,
    val supportsManualFocus: Boolean = true,
    val minFocusDistance: Float = 10.0f, // diopters
    val supportsExposureLock: Boolean = true,
    val minExposureComp: Int = -12,
    val maxExposureComp: Int = 12,
    val exposureCompStep: Float = 0.333f,
    val minIso: Int = 50,
    val maxIso: Int = 3200,
    val minExposureTimeNs: Long = 100_000L,       // 1/10000s
    val maxExposureTimeNs: Long = 1_000_000_000L, // 1s
    val supportsTorch: Boolean = true,
    val supportedResolutions: List<String> = listOf("1920x1080@30", "1920x1080@60", "3840x2160@30"),
    val supportedLuts: List<String> = listOf(
        "None",
        "Identity",
        "Natural",
        "Travel",
        "Warm Sunset",
        "Cool Cinema",
        "Mono",
        "Green Pastures",
        "Vintage 80s"
    )
)

data class CameraStateSnapshot(
    val stateRevision: Long = 1L,
    val activeFacing: String = "BACK",
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val recordedBytes: Long = 0L,
    val outputFileName: String? = null,
    val zoomRatio: Float = 1.0f,
    val focusPoint: NormalizedPoint? = null,
    val isFocusLocked: Boolean = false,
    val manualFocusDistance: Float? = null,
    val exposurePoint: NormalizedPoint? = null,
    val isExposureLocked: Boolean = false,
    val exposureCompensation: Int = 0,
    val currentIso: Int = 100,
    val currentExposureTimeNs: Long = 20_000_000L, // 1/50s
    val whiteBalanceMode: String = "AUTO",
    val isTorchOn: Boolean = false,
    val activeLutName: String = "None",
    val lutIntensity: Float = 1.0f,
    val previewQuality: String = "720p",
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val availableStorageMb: Long = 10240L,
    val estimatedRecordMinutes: Long = 120L,
    val thermalStatus: String = "NORMAL",
    val rttMs: Long = 0L
)
