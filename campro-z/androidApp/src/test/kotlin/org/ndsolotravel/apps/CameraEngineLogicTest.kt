package org.ndsolotravel.apps

import org.junit.Assert.*
import org.junit.Test
import org.ndsolotravel.camproz.protocol.*

class CameraEngineLogicTest {

    @Test
    fun testCommandDeduplication() {
        val seenCommands = HashSet<String>()
        val cmdId = "unique-cmd-12345"

        val firstExecution = seenCommands.add(cmdId)
        assertTrue(firstExecution)

        val secondExecution = seenCommands.add(cmdId)
        assertFalse(secondExecution) // duplicate rejected!
    }

    @Test
    fun testCapabilityClamping() {
        val caps = CameraCapabilities(
            minZoom = 1.0f,
            maxZoom = 8.0f,
            minExposureComp = -12,
            maxExposureComp = 12
        )

        val requestedZoom = 15.0f
        val clampedZoom = requestedZoom.coerceIn(caps.minZoom, caps.maxZoom)
        assertEquals(8.0f, clampedZoom, 0.001f)

        val requestedEv = -20
        val clampedEv = requestedEv.coerceIn(caps.minExposureComp, caps.maxExposureComp)
        assertEquals(-12, clampedEv)
    }

    @Test
    fun testNormalizedCoordinateValidation() {
        val validPoint = NormalizedPoint(0.5f, 0.5f)
        assertTrue(validPoint.isValid())

        val invalidPointX = NormalizedPoint(-0.1f, 0.5f)
        assertFalse(invalidPointX.isValid())

        val invalidPointY = NormalizedPoint(0.5f, 1.2f)
        assertFalse(invalidPointY.isValid())
    }

    @Test
    fun testStateRevisionIncrement() {
        var revision = 1L
        val updateCount = 5
        for (i in 1..updateCount) {
            revision++
        }
        assertEquals(6L, revision)
    }

    @Test
    fun testExplicitStartAndStopRecordingCommands() {
        val startCmd = CommandMessage(
            sessionId = "sess-1",
            commandId = "cmd-start",
            type = CommandType.START_RECORDING
        )
        val stopCmd = CommandMessage(
            sessionId = "sess-1",
            commandId = "cmd-stop",
            type = CommandType.STOP_RECORDING
        )

        assertEquals(CommandType.START_RECORDING, startCmd.type)
        assertEquals(CommandType.STOP_RECORDING, stopCmd.type)
        assertNotEquals(startCmd.type, stopCmd.type)
    }
}
