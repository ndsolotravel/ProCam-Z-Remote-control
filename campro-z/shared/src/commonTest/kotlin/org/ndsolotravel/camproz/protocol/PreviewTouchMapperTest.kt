package org.ndsolotravel.camproz.protocol

import kotlin.test.*

class PreviewTouchMapperTest {
    @Test fun fitRejectsLetterboxAndMapsCenter() {
        assertNull(PreviewTouchMapper.map(500f, 100f, 1000f, 2000f, 1000f, 1000f, true))
        assertEquals(NormalizedPoint(.5f, .5f), PreviewTouchMapper.map(500f, 1000f, 1000f, 2000f, 1000f, 1000f, true))
    }
    @Test fun fillMapsVisibleCropAndRejectsInvalidGeometry() {
        assertEquals(NormalizedPoint(.25f, .5f), PreviewTouchMapper.map(0f, 1000f, 1000f, 2000f, 1000f, 1000f, false))
        assertNull(PreviewTouchMapper.map(0f, 0f, 0f, 100f, 100f, 100f, true))
        assertNull(PreviewTouchMapper.map(Float.NaN, 0f, 100f, 100f, 100f, 100f, true))
    }
}
