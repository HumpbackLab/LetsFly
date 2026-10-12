package com.humpbacklab.letsfly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomControlConfigTest {
    @Test fun defaultLayoutAndPersistenceRoundTrip() {
        val config = CustomControlConfig.default()
        assertNull(config.error())
        assertEquals(config, CustomControlConfig.decode(config.encode()))
        assertEquals(0, config.defaults[2])
        assertEquals(0, config.defaults[4])
        assertEquals(50, config.defaults[0])
    }

    @Test fun duplicateOrArmChannelCannotBeAssigned() {
        val config = CustomControlConfig.default()
        assertTrue(config.copy(controls = config.controls +
            CustomControl(99, CustomControlType.BUTTON, .5f, .5f, CustomAxis(1)))
            .error() != null)
        assertTrue(config.copy(controls = listOf(
            CustomControl(1, CustomControlType.BUTTON, .5f, .5f, CustomAxis(5))))
            .error() != null)
        assertNull(CustomControlConfig.decode("broken"))
    }

    @Test fun twoAxisSingleAxisToggleReturnAndDisarm() {
        val config = CustomControlConfig(List(16) { if (it == 2 || it >= 4) 0 else 50 }, listOf(
            CustomControl(1, CustomControlType.JOYSTICK, .2f, .5f,
                CustomAxis(1, 10, 90, true, 55),
                CustomAxis(3, 0, 100, false, 0)),
            CustomControl(2, CustomControlType.VERTICAL, .5f, .5f,
                CustomAxis(6, 20, 80, true, 40)),
            CustomControl(3, CustomControlType.BUTTON, .8f, .5f,
                CustomAxis(7), offValue = 15, onValue = 85)
        ))
        val state = CustomControlState(config)
        assertFalse(state.armed)
        state.reset(true)
        assertEquals(50, state.value(1))
        state.move(1, 1f, 1f)
        state.move(2, 0f, -1f)
        assertEquals(90, state.value(1))
        assertEquals(100, state.value(3))
        assertEquals(20, state.value(6))
        assertEquals(1, state.advanceButton(3))
        assertEquals(85, state.value(7))
        state.release(1)
        state.release(2)
        assertEquals(55, state.value(1))
        assertEquals(100, state.value(3))
        assertEquals(40, state.value(6))
        assertEquals(0, state.advanceButton(3))
        assertEquals(15, state.value(7))
        state.reset(false)
        assertEquals(0, state.value(3))
        assertEquals(0, state.value(5))
        assertEquals(0, state.value(7))
        state.move(1, -1f, -1f)
        assertEquals(50, state.value(1))
        state.reset(true)
        assertEquals(100, state.value(5))
        assertArrayEquals(config.defaults.toIntArray().also { it[4] = 100 }, state.snapshot())
    }

    @Test fun threePositionButtonCyclesAndResets() {
        val base = CustomControlConfig.default()
        val button = base.controls.first { it.type == CustomControlType.BUTTON }
            .copy(buttonPositions = 3, offValue = 10, middleValue = 55,
                onValue = 90, widthScale = 1.4f, heightScale = .8f,
                backgroundOpacity = 45)
        val config = base.copy(controls = base.controls.map {
            if (it.id == button.id) button else it
        })
        assertNull(config.error())
        assertEquals(config, CustomControlConfig.decode(config.encode()))
        val state = CustomControlState(config)
        state.reset(true)
        assertEquals(1, state.advanceButton(button.id))
        assertEquals(55, state.value(6))
        assertEquals(2, state.advanceButton(button.id))
        assertEquals(90, state.value(6))
        assertEquals(0, state.advanceButton(button.id))
        assertEquals(10, state.value(6))
        state.reset(false)
        assertEquals(0, state.buttonPosition(button.id))
        assertEquals(0, state.value(6))
    }

    @Test fun oldProfileDecodesAsTwoPositionButton() {
        val legacy = CustomControlConfig.default(false).encode().replaceFirst("2\n", "1\n")
            .lines().mapIndexed { index, line ->
                if (index < 2) line else line.split(',').take(8).joinToString(",")
            }.joinToString("\n")
        val decoded = CustomControlConfig.decode(legacy)!!
        assertEquals(2, decoded.controls.first { it.type == CustomControlType.BUTTON }.buttonPositions)
        assertEquals(1f, decoded.controls.first().widthScale)
    }

    @Test fun invalidButtonPositionOrAppearanceIsRejected() {
        val base = CustomControlConfig.default()
        val button = base.controls.first { it.type == CustomControlType.BUTTON }
        assertTrue(base.copy(controls = base.controls.map {
            if (it.id == button.id) it.copy(buttonPositions = 4) else it
        }).error() != null)
        assertTrue(base.copy(controls = base.controls.map {
            if (it.id == button.id) it.copy(widthScale = 2.1f) else it
        }).error() != null)
    }

    @Test fun fullScreenMigrationPreservesPositionsAndControlSettings() {
        val legacy = CustomControlConfig.default(false)
        val decoded = CustomControlConfig.decode(legacy.encode())!!
        assertFalse(decoded.fullScreenCoordinates)
        val migrated = decoded.asFullScreen(400, 96)
        assertTrue(migrated.fullScreenCoordinates)
        for ((old, current) in decoded.controls.zip(migrated.controls)) {
            assertEquals(96f + old.y * 304, current.y * 400, .001f)
            assertEquals(96f + old.resetY * 304, current.resetY * 400, .001f)
            assertEquals(old.x, current.x)
            assertEquals(old.first, current.first)
            assertEquals(old.second, current.second)
        }
        assertEquals(migrated, migrated.asFullScreen(400, 96))
        assertEquals(migrated, CustomControlConfig.decode(migrated.encode()))
    }

    @Test fun customChannelsUseExistingCrsfPacketAndResetAfterDisarm() {
        val base = CustomControlConfig.default()
        val config = base.copy(defaults = base.defaults.toMutableList().also { it[8] = 55 },
            controls = base.controls + CustomControl(99, CustomControlType.BUTTON,
                .5f, .5f, CustomAxis(16), buttonPositions = 3, middleValue = 73))
        assertNull(config.error())
        val state = CustomControlState(config)
        val crsf = CRSFData()
        fun packedChannels(): IntArray {
            state.snapshot().forEachIndexed { index, percent ->
                crsf.data_array[index] = (percent / 100f *
                    MainActivity.DUTY_CYCLE_MULTIPLIER + MainActivity.DUTY_CYCLE_OFFSET).toInt()
            }
            val packet = crsf.pack().toByteArray()
            assertEquals(26, packet.size)
            assertEquals(0xC8, packet[0].toInt() and 0xFF)
            assertEquals(0x18, packet[1].toInt() and 0xFF)
            assertEquals(0x16, packet[2].toInt() and 0xFF)
            return IntArray(16) { channel ->
                var value = 0
                for (bit in 0 until 11) {
                    val offset = channel * 11 + bit
                    val packedBit = (packet[3 + offset / 8].toInt() ushr (offset % 8)) and 1
                    value = value or (packedBit shl bit)
                }
                value
            }
        }
        state.reset(true)
        state.move(1, 1f, 1f)
        state.move(2, -1f, 0f)
        state.advanceButton(3)
        state.advanceButton(99)
        assertArrayEquals(intArrayOf(172, 991, 1811, 1811, 1811, 1811, 172, 172,
            1073, 172, 172, 172, 172, 172, 172, 1368), packedChannels())
        state.reset(false)
        state.move(1, -1f, 1f)
        state.advanceButton(99)
        assertArrayEquals(intArrayOf(991, 991, 172, 991, 172, 172, 172, 172,
            1073, 172, 172, 172, 172, 172, 172, 172), packedChannels())
    }
}
