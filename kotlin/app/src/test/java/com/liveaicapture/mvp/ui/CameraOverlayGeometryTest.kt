package com.liveaicapture.mvp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOverlayGeometryTest {
    @Test
    fun landscapeFrameIsLetterboxedInPortraitPreview() {
        assertPoint(0f, 115.625f, mapNormPoint(0f, 0f, 300f, 400f, 16f / 9f))
        assertPoint(300f, 284.375f, mapNormPoint(1f, 1f, 300f, 400f, 16f / 9f))
        assertPoint(150f, 200f, mapNormPoint(0.5f, 0.5f, 300f, 400f, 16f / 9f))
    }

    @Test
    fun rotatedPortraitCropIsPillarboxedWithoutStretching() {
        // A 1600x900 viewport crop rotated by 90 or 270 degrees becomes 900x1600.
        assertPoint(37.5f, 0f, mapNormPoint(0f, 0f, 300f, 400f, 9f / 16f))
        assertPoint(262.5f, 400f, mapNormPoint(1f, 1f, 300f, 400f, 9f / 16f))
    }

    @Test
    fun frontCameraMirrorsHorizontallyWithinTheVisibleFrame() {
        assertPoint(217.5f, 120f, mapNormPoint(0.2f, 0.3f, 300f, 400f, 9f / 16f, true))
        assertPoint(82.5f, 120f, mapNormPoint(0.2f, 0.3f, 300f, 400f, 9f / 16f, false))
    }

    @Test
    fun mirroredBoundingBoxKeepsOrderedEdges() {
        val actual = mapRect(Rect(0.1f, 0.2f, 0.4f, 0.7f), 300f, 400f, 9f / 16f, true)
        assertEquals(172.5f, actual.left, 0.001f)
        assertEquals(80f, actual.top, 0.001f)
        assertEquals(240f, actual.right, 0.001f)
        assertEquals(280f, actual.bottom, 0.001f)
    }

    private fun assertPoint(x: Float, y: Float, actual: Offset) {
        assertEquals(x, actual.x, 0.001f)
        assertEquals(y, actual.y, 0.001f)
    }
}
