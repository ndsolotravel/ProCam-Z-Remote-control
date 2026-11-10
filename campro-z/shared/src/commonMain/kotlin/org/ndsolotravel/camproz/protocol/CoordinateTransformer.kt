package org.ndsolotravel.camproz.protocol

import kotlin.math.max
import kotlin.math.min

/**
 * High-precision coordinate transformation pipeline.
 * Maps remote normalized touches to camera sensor active array coordinates,
 * resolving view aspect ratio, letterbox/pillarbox offsets, sensor orientation,
 * front-camera mirroring, and digital zoom crop.
 */
object CoordinateTransformer {

    data class ViewRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    data class SensorBounds(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Calculates the displayed video frame within a container view (letterbox / pillarbox math).
     */
    fun calculateVideoDisplayRect(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float,
        scaleFit: Boolean = true
    ): ViewRect {
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0f || videoHeight <= 0f) {
            return ViewRect(0f, 0f, viewWidth, viewHeight)
        }

        val viewAspect = viewWidth / viewHeight
        val videoAspect = videoWidth / videoHeight

        val displayWidth: Float
        val displayHeight: Float

        if (scaleFit) {
            // Letterbox / Pillarbox
            if (viewAspect > videoAspect) {
                // Pillarbox: bars on left and right
                displayHeight = viewHeight
                displayWidth = viewHeight * videoAspect
            } else {
                // Letterbox: bars on top and bottom
                displayWidth = viewWidth
                displayHeight = viewWidth / videoAspect
            }
        } else {
            // Fill / Crop
            if (viewAspect > videoAspect) {
                displayWidth = viewWidth
                displayHeight = viewWidth / videoAspect
            } else {
                displayHeight = viewHeight
                displayWidth = viewHeight * videoAspect
            }
        }

        val left = (viewWidth - displayWidth) / 2f
        val top = (viewHeight - displayHeight) / 2f
        return ViewRect(left, top, displayWidth, displayHeight)
    }

    /**
     * Converts a raw touch point inside the remote view to normalized video coordinates (0.0 to 1.0).
     * Returns null if the touch is completely outside the letterboxed video display.
     */
    fun viewTouchToNormalized(
        touchX: Float,
        touchY: Float,
        viewRect: ViewRect
    ): NormalizedPoint? {
        if (touchX < viewRect.left || touchX > viewRect.right ||
            touchY < viewRect.top || touchY > viewRect.bottom
        ) {
            // Touch fell outside the active video frame (in letterbox bar)
            return null
        }

        val normX = ((touchX - viewRect.left) / viewRect.width).coerceIn(0f, 1f)
        val normY = ((touchY - viewRect.top) / viewRect.height).coerceIn(0f, 1f)
        return NormalizedPoint(normX, normY)
    }

    /**
     * Maps normalized video coordinates (0.0 to 1.0) into Camera2 Sensor Active Array coordinates,
     * taking into account sensor rotation, front-facing mirroring, and active zoom crop.
     */
    fun normalizedToSensorCoordinate(
        normPoint: NormalizedPoint,
        sensorArray: SensorBounds,
        zoomCrop: SensorBounds,
        sensorOrientation: Int,
        isFrontCamera: Boolean
    ): Pair<Int, Int> {
        var x = normPoint.x.coerceIn(0f, 1f)
        var y = normPoint.y.coerceIn(0f, 1f)

        // 1. Handle front-camera horizontal mirroring
        if (isFrontCamera) {
            x = 1.0f - x
        }

        // 2. Rotate normalized point into sensor coordinate space
        val rotatedPoint = when (sensorOrientation % 360) {
            90 -> NormalizedPoint(y, 1.0f - x)
            180 -> NormalizedPoint(1.0f - x, 1.0f - y)
            270 -> NormalizedPoint(1.0f - y, x)
            else -> NormalizedPoint(x, y) // 0
        }

        // 3. Map within the active digital zoom crop region
        val sensorX = (zoomCrop.left + rotatedPoint.x * zoomCrop.width).toInt()
        val sensorY = (zoomCrop.top + rotatedPoint.y * zoomCrop.height).toInt()

        // 4. Clamp strictly within sensor bounds
        val clampedX = sensorX.coerceIn(sensorArray.left, sensorArray.left + sensorArray.width - 1)
        val clampedY = sensorY.coerceIn(sensorArray.top, sensorArray.top + sensorArray.height - 1)

        return Pair(clampedX, clampedY)
    }

    /**
     * Builds a metering rectangle centered at (sensorX, sensorY) with size (boxSize x boxSize),
     * clamped to sensor boundaries.
     */
    fun createMeteringRect(
        sensorX: Int,
        sensorY: Int,
        boxSize: Int,
        sensorBounds: SensorBounds
    ): SensorBounds {
        val half = boxSize / 2
        val left = (sensorX - half).coerceIn(sensorBounds.left, sensorBounds.left + sensorBounds.width - boxSize)
        val top = (sensorY - half).coerceIn(sensorBounds.top, sensorBounds.top + sensorBounds.height - boxSize)
        val width = min(boxSize, sensorBounds.width)
        val height = min(boxSize, sensorBounds.height)
        return SensorBounds(left, top, width, height)
    }

    /**
     * Inverse mapping: converts sensor coordinate back to normalized video frame (0.0 to 1.0)
     * so that the remote companion can render the confirmed overlay in the exact correct spot.
     */
    fun sensorToNormalizedCoordinate(
        sensorX: Int,
        sensorY: Int,
        zoomCrop: SensorBounds,
        sensorOrientation: Int,
        isFrontCamera: Boolean
    ): NormalizedPoint {
        // 1. Normalized inside zoom crop
        val cropNormX = ((sensorX - zoomCrop.left).toFloat() / zoomCrop.width).coerceIn(0f, 1f)
        val cropNormY = ((sensorY - zoomCrop.top).toFloat() / zoomCrop.height).coerceIn(0f, 1f)

        // 2. Inverse rotation
        val unrotated = when (sensorOrientation % 360) {
            90 -> NormalizedPoint(1.0f - cropNormY, cropNormX)
            180 -> NormalizedPoint(1.0f - cropNormX, 1.0f - cropNormY)
            270 -> NormalizedPoint(cropNormY, 1.0f - cropNormX)
            else -> NormalizedPoint(cropNormX, cropNormY)
        }

        // 3. Mirroring
        val finalX = if (isFrontCamera) 1.0f - unrotated.x else unrotated.x
        return NormalizedPoint(finalX.coerceIn(0f, 1f), unrotated.y.coerceIn(0f, 1f))
    }
}
