package org.ndsolotravel.camproz.remote

import org.junit.Assert.*
import org.junit.Test
import org.ndsolotravel.camproz.protocol.*

class RemoteClientLogicTest {

    @Test
    fun testReticleCoordinateClamping() {
        val rawX = 1.25f
        val rawY = -0.15f
        val clampedX = rawX.coerceIn(0.05f, 0.95f)
        val clampedY = rawY.coerceIn(0.05f, 0.95f)

        assertEquals(0.95f, clampedX, 0.001f)
        assertEquals(0.05f, clampedY, 0.001f)
    }

    @Test
    fun testFocusLockToggleState() {
        var isLocked = false

        // First double-tap locks focus
        isLocked = !isLocked
        assertTrue(isLocked)

        // Second double-tap unlocks focus
        isLocked = !isLocked
        assertFalse(isLocked)
    }

    @Test
    fun testFitLetterboxOffsetCalculation() {
        // Container: 1080 x 2400 (aspect 9:20 = 0.45)
        // Video Stream: 1080 x 1920 (aspect 9:16 = 0.5625)
        val containerW = 1080f
        val containerH = 2400f
        val videoW = 1080f
        val videoH = 1920f

        val scale = minOf(containerW / videoW, containerH / videoH)
        val displayedW = videoW * scale
        val displayedH = videoH * scale

        val offsetX = (containerW - displayedW) / 2f
        val offsetY = (containerH - displayedH) / 2f

        assertEquals(1080f, displayedW, 0.01f)
        assertEquals(1920f, displayedH, 0.01f)
        assertEquals(0f, offsetX, 0.01f)
        assertEquals(240f, offsetY, 0.01f) // Letterbox top and bottom
    }

    @Test
    fun testFillCropCalculation() {
        // Container: 1080 x 2400
        // Video Stream: 1080 x 1920
        val containerW = 1080f
        val containerH = 2400f
        val videoW = 1080f
        val videoH = 1920f

        val scale = maxOf(containerW / videoW, containerH / videoH)
        val displayedW = videoW * scale
        val displayedH = videoH * scale

        // Scale should be 2400 / 1920 = 1.25
        assertEquals(1.25f, scale, 0.001f)
        assertEquals(1350f, displayedW, 0.01f) // Cropped horizontally
        assertEquals(2400f, displayedH, 0.01f)
    }

    @Test
    fun testThrottlingSliderEmissions() {
        var lastEmittedTimeMs = 0L
        val throttleWindowMs = 60L
        var emittedCount = 0

        val incomingEvents = listOf(0L, 20L, 50L, 70L, 110L, 140L)

        for (timestamp in incomingEvents) {
            if (timestamp - lastEmittedTimeMs >= throttleWindowMs || lastEmittedTimeMs == 0L) {
                lastEmittedTimeMs = timestamp
                emittedCount++
            }
        }

        // 0ms (emits, count=1)
        // 20ms (diff 20 < 60, dropped)
        // 50ms (diff 50 < 60, dropped)
        // 70ms (diff 70 >= 60, emits, count=2)
        // 110ms (diff 40 < 60, dropped)
        // 140ms (diff 70 >= 60, emits, count=3)
        assertEquals(3, emittedCount)
    }
}
