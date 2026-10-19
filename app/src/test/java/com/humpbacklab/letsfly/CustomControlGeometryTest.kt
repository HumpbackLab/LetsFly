package com.humpbacklab.letsfly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomControlGeometryTest {
    private fun area(id: Int, type: CustomControlType, x: Float, y: Float,
        width: Float = 150f, height: Float = 150f) =
        CustomControlGeometry.area(id, type, x, y, width, height, 1f)

    @Test fun transparentJoystickCornersDoNotOverlap() {
        val first = area(1, CustomControlType.JOYSTICK, 100f, 100f)
        val second = area(2, CustomControlType.JOYSTICK, 210f, 210f)
        assertTrue(first.bounds.right > second.bounds.left)
        assertTrue(first.bounds.bottom > second.bounds.top)
        assertFalse(CustomControlGeometry.intersects(first.outline, second.outline))
        val button = area(3, CustomControlType.BUTTON, 172f, 172f, 20f, 20f)
        assertFalse(CustomControlGeometry.intersects(first.outline, button.outline))
        assertFalse(CustomControlGeometry.contains(first.outline, 170f, 170f))
        assertTrue(CustomControlGeometry.contains(first.outline, 100f, 100f))
    }

    @Test fun realOverlapIsDetectedAndTouchingEdgesAreAllowed() {
        val first = area(1, CustomControlType.JOYSTICK, 100f, 100f)
        val overlapping = area(2, CustomControlType.JOYSTICK, 190f, 100f)
        val touching = area(3, CustomControlType.JOYSTICK, 212.5f, 100f)
        assertTrue(CustomControlGeometry.intersects(first.outline, overlapping.outline))
        assertFalse(CustomControlGeometry.intersects(first.outline, touching.outline))
    }

    @Test fun stretchedJoystickUsesEllipticalOutline() {
        val first = area(1, CustomControlType.JOYSTICK, 100f, 100f, 200f, 80f)
        val separated = area(2, CustomControlType.JOYSTICK, 100f, 162f, 200f, 80f)
        val overlapping = area(3, CustomControlType.JOYSTICK, 100f, 158f, 200f, 80f)
        assertFalse(CustomControlGeometry.intersects(first.outline, separated.outline))
        assertTrue(CustomControlGeometry.intersects(first.outline, overlapping.outline))
    }

    @Test fun singleAxisTrackLeavesTransparentSpaceAvailable() {
        val horizontal = area(1, CustomControlType.HORIZONTAL, 100f, 100f, 200f, 80f)
        val above = area(2, CustomControlType.BUTTON, 100f, 60f, 20f, 20f)
        assertFalse(CustomControlGeometry.intersects(horizontal.outline, above.outline))
        val vertical = area(3, CustomControlType.VERTICAL, 100f, 100f, 80f, 200f)
        val beside = area(4, CustomControlType.BUTTON, 60f, 100f, 20f, 20f)
        assertFalse(CustomControlGeometry.intersects(vertical.outline, beside.outline))
    }

    @Test fun onlyActualFixedButtonsBlockTheTopRegion() {
        val buttons = listOf(CustomBounds(0f, 0f, 120f, 96f),
            CustomBounds(500f, 0f, 640f, 48f))
        val free = area(1, CustomControlType.JOYSTICK, 250f, 45f, 80f, 80f)
        assertTrue(CustomControlGeometry.issues(listOf(free), 640, 360, buttons, .5f).isEmpty())
        val blocked = area(2, CustomControlType.JOYSTICK, 100f, 45f, 80f, 80f)
        assertEquals(CustomLayoutIssueKind.FIXED_OVERLAP,
            CustomControlGeometry.issues(listOf(blocked), 640, 360, buttons, .5f).single().kind)
        val outside = area(3, CustomControlType.JOYSTICK, 250f, 10f, 80f, 80f)
        assertEquals(CustomLayoutIssueKind.OUTSIDE_SCREEN,
            CustomControlGeometry.issues(listOf(outside), 640, 360, buttons, .5f).single().kind)
    }
}
