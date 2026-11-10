package org.ndsolotravel.camproz.protocol

import kotlin.test.*
import kotlin.math.abs

class CoordinateTransformerTest {

    @Test
    fun testLetterboxCalculationInPortrait() {
        // Phone screen portrait: 1080 x 2400 (aspect 9:20 = 0.45)
        // Video 16:9 landscape: 1920 x 1080 (aspect 1.777)
        // In portrait, video width fills screen (1080), height is 1080 / 1.777 = 607.5
        // Top and bottom have letterbox bars: (2400 - 607.5)/2 = 896.25
        val display = CoordinateTransformer.calculateVideoDisplayRect(
            viewWidth = 1080f,
            viewHeight = 2400f,
            videoWidth = 1920f,
            videoHeight = 1080f,
            scaleFit = true
        )

        assertEquals(0f, display.left)
        assertEquals(1080f, display.width)
        assertTrue(abs(display.height - 607.5f) < 1.0f)
        assertTrue(abs(display.top - 896.25f) < 1.0f)

        // Touch in letterbox bar (e.g. top = 100) should return null
        val outsideTouch = CoordinateTransformer.viewTouchToNormalized(540f, 100f, display)
        assertNull(outsideTouch)

        // Touch at exact center (540, 1200) should return (0.5, 0.5)
        val centerTouch = CoordinateTransformer.viewTouchToNormalized(540f, 1200f, display)
        assertNotNull(centerTouch)
        assertTrue(abs(centerTouch.x - 0.5f) < 0.01f)
        assertTrue(abs(centerTouch.y - 0.5f) < 0.01f)
    }

    @Test
    fun testCenterPointMappingToSensor() {
        val sensorArray = CoordinateTransformer.SensorBounds(0, 0, 4000, 3000)
        val zoomCrop = CoordinateTransformer.SensorBounds(0, 0, 4000, 3000) // 1.0x zoom

        val (sensorX, sensorY) = CoordinateTransformer.normalizedToSensorCoordinate(
            normPoint = NormalizedPoint(0.5f, 0.5f),
            sensorArray = sensorArray,
            zoomCrop = zoomCrop,
            sensorOrientation = 90,
            isFrontCamera = false
        )

        // Center should remain center regardless of orientation
        assertEquals(2000, sensorX)
        assertEquals(1500, sensorY)
    }

    @Test
    fun testFrontCameraMirroring() {
        val sensorArray = CoordinateTransformer.SensorBounds(0, 0, 4000, 3000)
        val zoomCrop = CoordinateTransformer.SensorBounds(0, 0, 4000, 3000)

        // Left tap (0.1, 0.5) on back camera vs front camera
        val (backX, backY) = CoordinateTransformer.normalizedToSensorCoordinate(
            normPoint = NormalizedPoint(0.1f, 0.5f),
            sensorArray = sensorArray,
            zoomCrop = zoomCrop,
            sensorOrientation = 0,
            isFrontCamera = false
        )

        val (frontX, frontY) = CoordinateTransformer.normalizedToSensorCoordinate(
            normPoint = NormalizedPoint(0.1f, 0.5f),
            sensorArray = sensorArray,
            zoomCrop = zoomCrop,
            sensorOrientation = 0,
            isFrontCamera = true
        )

        assertEquals(400, backX)
        assertEquals(3600, frontX) // mirrored across X
        assertEquals(backY, frontY)
    }

    @Test
    fun testRoundTripTransformationAccuracy() {
        val sensorArray = CoordinateTransformer.SensorBounds(0, 0, 3840, 2160)
        val zoomCrop = CoordinateTransformer.SensorBounds(480, 270, 2880, 1620) // digital zoom applied

        val testPoints = listOf(
            NormalizedPoint(0.5f, 0.5f),
            NormalizedPoint(0.1f, 0.1f),
            NormalizedPoint(0.9f, 0.1f),
            NormalizedPoint(0.1f, 0.9f),
            NormalizedPoint(0.9f, 0.9f)
        )

        for (orient in listOf(0, 90, 180, 270)) {
            for (front in listOf(false, true)) {
                for (pt in testPoints) {
                    val (sx, sy) = CoordinateTransformer.normalizedToSensorCoordinate(
                        normPoint = pt,
                        sensorArray = sensorArray,
                        zoomCrop = zoomCrop,
                        sensorOrientation = orient,
                        isFrontCamera = front
                    )

                    val reconstructed = CoordinateTransformer.sensorToNormalizedCoordinate(
                        sensorX = sx,
                        sensorY = sy,
                        zoomCrop = zoomCrop,
                        sensorOrientation = orient,
                        isFrontCamera = front
                    )

                    assertTrue(
                        abs(reconstructed.x - pt.x) < 0.02f,
                        "Failed X match for orient $orient front $front: expected ${pt.x}, got ${reconstructed.x}"
                    )
                    assertTrue(
                        abs(reconstructed.y - pt.y) < 0.02f,
                        "Failed Y match for orient $orient front $front: expected ${pt.y}, got ${reconstructed.y}"
                    )
                }
            }
        }
    }
}
