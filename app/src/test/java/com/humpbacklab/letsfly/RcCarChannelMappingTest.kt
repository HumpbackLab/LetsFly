package com.humpbacklab.letsfly

import org.junit.Assert.assertEquals
import org.junit.Test

class RcCarChannelMappingTest {
    @Test
    fun duty_centersBothAxes() {
        assertEquals(0.5f, RcCarChannelMapping.duty(0f, 100), 0.0001f)
        assertEquals(0.5f, RcCarChannelMapping.duty(0f, 50), 0.0001f)
    }

    @Test
    fun duty_scalesBothDirectionsAroundCenter() {
        assertEquals(0.25f, RcCarChannelMapping.duty(-1f, 50), 0.0001f)
        assertEquals(0.75f, RcCarChannelMapping.duty(1f, 50), 0.0001f)
        assertEquals(0f, RcCarChannelMapping.duty(-1f, 100), 0.0001f)
        assertEquals(1f, RcCarChannelMapping.duty(1f, 100), 0.0001f)
    }
}
