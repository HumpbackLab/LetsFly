package com.humpbacklab.letsfly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalJoystickCalibrationTest {
    @Test
    fun centerAndIndependentAxisTravelComeFromExtrema() {
        val bounds = JoystickTravelBounds(minX = 20f, maxX = 100f, minY = 40f, maxY = 200f)

        assertEquals(60f, bounds.centerX, 0f)
        assertEquals(120f, bounds.centerY, 0f)
        assertEquals(80f, bounds.width, 0f)
        assertEquals(160f, bounds.height, 0f)
    }

    @Test
    fun bothAxesMustHaveEnoughTravel() {
        assertTrue(JoystickTravelBounds(0f, 30f, 0f, 30f).isUsable(24f))
        assertFalse(JoystickTravelBounds(0f, 30f, 0f, 10f).isUsable(24f))
    }
}
