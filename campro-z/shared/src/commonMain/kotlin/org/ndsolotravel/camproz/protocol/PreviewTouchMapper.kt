package org.ndsolotravel.camproz.protocol

/** Maps a remote FIT/FILL image touch into the host preview's pixel-normalized space. */
object PreviewTouchMapper {
    fun map(x: Float, y: Float, width: Float, height: Float, frameWidth: Float, frameHeight: Float, fit: Boolean): NormalizedPoint? {
        if (listOf(x, y, width, height, frameWidth, frameHeight).any { !it.isFinite() } ||
            width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val scale = if (fit) minOf(width / frameWidth, height / frameHeight) else maxOf(width / frameWidth, height / frameHeight)
        val displayedWidth = frameWidth * scale
        val displayedHeight = frameHeight * scale
        val nx = (x - (width - displayedWidth) / 2) / displayedWidth
        val ny = (y - (height - displayedHeight) / 2) / displayedHeight
        return if (nx in 0f..1f && ny in 0f..1f) NormalizedPoint(nx, ny) else null
    }
}
