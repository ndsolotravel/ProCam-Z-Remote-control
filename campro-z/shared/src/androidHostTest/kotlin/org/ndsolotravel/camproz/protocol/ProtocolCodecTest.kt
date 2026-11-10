package org.ndsolotravel.camproz.protocol

import kotlin.test.*

class ProtocolCodecTest {

    @Test
    fun testCommandEncodingAndDecoding() {
        val originalCmd = CommandMessage(
            protocolVersion = "1.0",
            sessionId = "sess-12345",
            commandId = "cmd-9999",
            type = CommandType.START_RECORDING,
            payloadJson = "{\"profile\":\"1080p\"}",
            timestampMs = 1700000000L
        )

        val json = ProtocolCodec.encodeCommand(originalCmd)
        assertTrue(json.contains("\"type\":\"START_RECORDING\""))
        assertTrue(json.contains("\"commandId\":\"cmd-9999\""))

        val decoded = ProtocolCodec.decodeCommand(json)
        assertNotNull(decoded)
        assertEquals(originalCmd.protocolVersion, decoded.protocolVersion)
        assertEquals(originalCmd.sessionId, decoded.sessionId)
        assertEquals(originalCmd.commandId, decoded.commandId)
        assertEquals(originalCmd.type, decoded.type)
        assertEquals(originalCmd.timestampMs, decoded.timestampMs)
    }

    @Test
    fun testResultEncodingAndDecoding() {
        val result = CommandResult(
            commandId = "cmd-123",
            success = true,
            appliedStateRevision = 42L,
            errorMessage = null
        )

        val json = ProtocolCodec.encodeResult(result)
        val decoded = ProtocolCodec.decodeResult(json)

        assertNotNull(decoded)
        assertEquals("cmd-123", decoded.commandId)
        assertTrue(decoded.success)
        assertEquals(42L, decoded.appliedStateRevision)
        assertNull(decoded.errorMessage)
    }

    @Test
    fun testStateSnapshotEncodingAndDecoding() {
        val snapshot = CameraStateSnapshot(
            stateRevision = 15L,
            activeFacing = "BACK",
            isRecording = true,
            recordingDurationSeconds = 45L,
            recordedBytes = 15_000_000L,
            zoomRatio = 2.5f,
            focusPoint = NormalizedPoint(0.4f, 0.6f),
            isFocusLocked = true,
            manualFocusDistance = 3.5f,
            exposurePoint = NormalizedPoint(0.5f, 0.5f),
            isExposureLocked = false,
            exposureCompensation = -2,
            currentIso = 400,
            currentExposureTimeNs = 10_000_000L,
            whiteBalanceMode = "DAYLIGHT",
            isTorchOn = false,
            activeLutName = "Warm Sunset",
            lutIntensity = 0.85f,
            batteryPercent = 88,
            isCharging = true,
            availableStorageMb = 45000L,
            estimatedRecordMinutes = 320L,
            thermalStatus = "NORMAL",
            rttMs = 18L
        )

        val json = ProtocolCodec.encodeState(snapshot)
        val decoded = ProtocolCodec.decodeState(json)

        assertNotNull(decoded)
        assertEquals(15L, decoded.stateRevision)
        assertEquals("BACK", decoded.activeFacing)
        assertTrue(decoded.isRecording)
        assertEquals(45L, decoded.recordingDurationSeconds)
        assertEquals(15_000_000L, decoded.recordedBytes)
        assertEquals(2.5f, decoded.zoomRatio)
        assertEquals(0.4f, decoded.focusPoint?.x)
        assertEquals(0.6f, decoded.focusPoint?.y)
        assertTrue(decoded.isFocusLocked)
        assertEquals("Warm Sunset", decoded.activeLutName)
        assertEquals(0.85f, decoded.lutIntensity)
        assertEquals(88, decoded.batteryPercent)
        assertTrue(decoded.isCharging)
    }

    @Test
    fun testCorruptPayloadRejection() {
        val corruptJson = "{ malformed json content ... "
        val cmd = ProtocolCodec.decodeCommand(corruptJson)
        assertNull(cmd)

        val state = ProtocolCodec.decodeState(corruptJson)
        assertNull(state)
    }
}
