package org.ndsolotravel.camproz.protocol

import org.json.JSONObject

/**
 * Robust JSON serialization and deserialization for ProCam Z remote protocol.
 * Formatted with strict type-safety, versioning, and defensive error handling.
 */
object ProtocolCodec {

    fun encodeCommand(cmd: CommandMessage): String {
        return buildString {
            append("{")
            append("\"version\":\"").append(cmd.protocolVersion).append("\",")
            append("\"sessionId\":\"").append(escape(cmd.sessionId)).append("\",")
            append("\"commandId\":\"").append(escape(cmd.commandId)).append("\",")
            append("\"type\":\"").append(cmd.type.name).append("\",")
            append("\"timestamp\":").append(cmd.timestampMs).append(",")
            append("\"payload\":").append(cmd.payloadJson)
            append("}")
        }
    }

    fun decodeCommand(json: String): CommandMessage? {
        return try {
            val version = extractString(json, "version") ?: return null
            if (version != "1.0") return null
            val sessionId = extractString(json, "sessionId")?.takeIf { it.isNotBlank() } ?: return null
            val commandId = extractString(json, "commandId")?.takeIf { it.isNotBlank() } ?: return null
            val typeStr = extractString(json, "type") ?: return null
            val type = CommandType.valueOf(typeStr)
            val timestamp = extractLong(json, "timestamp") ?: 0L
            val payload = extractJsonObject(json, "payload") ?: return null
            CommandMessage(
                protocolVersion = version,
                sessionId = sessionId,
                commandId = commandId,
                type = type,
                payloadJson = payload,
                timestampMs = timestamp
            )
        } catch (_: Exception) {
            null
        }
    }

    fun encodeResult(result: CommandResult): String {
        return buildString {
            append("{")
            append("\"commandId\":\"").append(escape(result.commandId)).append("\",")
            append("\"success\":").append(result.success).append(",")
            append("\"stateRevision\":").append(result.appliedStateRevision)
            if (result.errorMessage != null) {
                append(",\"error\":\"").append(escape(result.errorMessage)).append("\"")
            }
            append("}")
        }
    }

    fun decodeResult(json: String): CommandResult? {
        return try {
            val commandId = extractString(json, "commandId") ?: return null
            val success = extractBoolean(json, "success") ?: false
            val revision = extractLong(json, "stateRevision") ?: 0L
            val error = extractString(json, "error")
            CommandResult(commandId, success, revision, error)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeState(state: CameraStateSnapshot): String {
        return buildString {
            append("{")
            append("\"revision\":").append(state.stateRevision).append(",")
            append("\"activeFacing\":\"").append(state.activeFacing).append("\",")
            append("\"isRecording\":").append(state.isRecording).append(",")
            append("\"recDuration\":").append(state.recordingDurationSeconds).append(",")
            append("\"recBytes\":").append(state.recordedBytes).append(",")
            if (state.outputFileName != null) {
                append("\"fileName\":\"").append(escape(state.outputFileName)).append("\",")
            }
            append("\"zoom\":").append(state.zoomRatio).append(",")
            if (state.focusPoint != null) {
                append("\"focusX\":").append(state.focusPoint.x).append(",")
                append("\"focusY\":").append(state.focusPoint.y).append(",")
            }
            append("\"focusLocked\":").append(state.isFocusLocked).append(",")
            if (state.manualFocusDistance != null) {
                append("\"focusDist\":").append(state.manualFocusDistance).append(",")
            }
            if (state.exposurePoint != null) {
                append("\"expX\":").append(state.exposurePoint.x).append(",")
                append("\"expY\":").append(state.exposurePoint.y).append(",")
            }
            append("\"expLocked\":").append(state.isExposureLocked).append(",")
            append("\"expComp\":").append(state.exposureCompensation).append(",")
            append("\"iso\":").append(state.currentIso).append(",")
            append("\"expTime\":").append(state.currentExposureTimeNs).append(",")
            append("\"wb\":\"").append(escape(state.whiteBalanceMode)).append("\",")
            append("\"torch\":").append(state.isTorchOn).append(",")
            append("\"lut\":\"").append(escape(state.activeLutName)).append("\",")
            append("\"lutIntensity\":").append(state.lutIntensity).append(",")
            append("\"previewQuality\":\"").append(escape(state.previewQuality)).append("\",")
            append("\"battery\":").append(state.batteryPercent).append(",")
            append("\"charging\":").append(state.isCharging).append(",")
            append("\"storageMb\":").append(state.availableStorageMb).append(",")
            append("\"estRecordMin\":").append(state.estimatedRecordMinutes).append(",")
            append("\"thermal\":\"").append(escape(state.thermalStatus)).append("\",")
            append("\"rtt\":").append(state.rttMs)
            append("}")
        }
    }

    fun decodeState(json: String): CameraStateSnapshot? {
        return try {
            val rev = extractLong(json, "revision") ?: return null
            val facing = extractString(json, "activeFacing") ?: return null
            val recording = extractBoolean(json, "isRecording") ?: false
            val duration = extractLong(json, "recDuration") ?: 0L
            val bytes = extractLong(json, "recBytes") ?: 0L
            val fileName = extractString(json, "fileName")
            val zoom = extractFloat(json, "zoom") ?: 1.0f
            val focusX = extractFloat(json, "focusX")
            val focusY = extractFloat(json, "focusY")
            val focusPoint = if (focusX != null && focusY != null) NormalizedPoint(focusX, focusY) else null
            val focusLocked = extractBoolean(json, "focusLocked") ?: false
            val focusDist = extractFloat(json, "focusDist")
            val expX = extractFloat(json, "expX")
            val expY = extractFloat(json, "expY")
            val expPoint = if (expX != null && expY != null) NormalizedPoint(expX, expY) else null
            val expLocked = extractBoolean(json, "expLocked") ?: false
            val expComp = extractInt(json, "expComp") ?: 0
            val iso = extractInt(json, "iso") ?: 100
            val expTime = extractLong(json, "expTime") ?: 20_000_000L
            val wb = extractString(json, "wb") ?: "AUTO"
            val torch = extractBoolean(json, "torch") ?: false
            val lut = extractString(json, "lut") ?: "None"
            val lutIntensity = extractFloat(json, "lutIntensity") ?: 1.0f
            val previewQuality = extractString(json, "previewQuality") ?: "720p"
            val battery = extractInt(json, "battery") ?: 100
            val charging = extractBoolean(json, "charging") ?: false
            val storageMb = extractLong(json, "storageMb") ?: 10240L
            val estRecordMin = extractLong(json, "estRecordMin") ?: 120L
            val thermal = extractString(json, "thermal") ?: "NORMAL"
            val rtt = extractLong(json, "rtt") ?: 0L

            CameraStateSnapshot(
                stateRevision = rev,
                activeFacing = facing,
                isRecording = recording,
                recordingDurationSeconds = duration,
                recordedBytes = bytes,
                outputFileName = fileName,
                zoomRatio = zoom,
                focusPoint = focusPoint,
                isFocusLocked = focusLocked,
                manualFocusDistance = focusDist,
                exposurePoint = expPoint,
                isExposureLocked = expLocked,
                exposureCompensation = expComp,
                currentIso = iso,
                currentExposureTimeNs = expTime,
                whiteBalanceMode = wb,
                isTorchOn = torch,
                activeLutName = lut,
                lutIntensity = lutIntensity,
                previewQuality = previewQuality,
                batteryPercent = battery,
                isCharging = charging,
                availableStorageMb = storageMb,
                estimatedRecordMinutes = estRecordMin,
                thermalStatus = thermal,
                rttMs = rtt
            )
        } catch (_: Exception) {
            null
        }
    }


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
