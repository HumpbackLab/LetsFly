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
        assertEquals(55, state.value(1))
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
        assertEquals(55, state.value(1))
        state.reset(true)
        assertEquals(100, state.value(5))
        assertArrayEquals(config.defaults.toIntArray().also { it[0] = 55; it[4] = 100 }, state.snapshot())
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
        val returns = mapOf(1 to 40, 2 to 60, 3 to 50, 4 to 30)
        val config = base.copy(defaults = base.defaults.toMutableList().also { it[8] = 55 },
            controls = base.controls.map { control ->
                fun returning(axis: CustomAxis?) = axis?.let {
                    it.copy(returnValue = returns[it.channel] ?: it.returnValue)
                }
                control.copy(first = returning(control.first), second = returning(control.second))
            } + CustomControl(99, CustomControlType.BUTTON,
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
        assertArrayEquals(intArrayOf(827, 1155, 172, 663, 172, 172, 172, 172,
            1073, 172, 172, 172, 172, 172, 172, 1368), packedChannels())
    }

    @Test fun auxiliaryButtonsWorkDisarmedAndSurviveArmTransitions() {
        val base = CustomControlConfig.default()
        val buttons = (6..16).map { channel ->
            CustomControl(channel, CustomControlType.BUTTON, .5f, .5f,
                CustomAxis(channel), buttonPositions = if (channel == 7) 3 else 2)
        }
        val state = CustomControlState(base.copy(controls = buttons))
        buttons.forEach {
            assertTrue(state.isControlEnabled(it))
            state.advanceButton(it.id)
        }
        assertEquals(50, state.value(7))
        state.advanceButton(7)
        assertEquals(100, state.value(7))
        for (arm in listOf(true, false, true)) {
            state.setArmed(arm)
            assertEquals(if (arm) 100 else 0, state.value(5))
            assertEquals(0, state.value(3))
            for (channel in 6..16) assertEquals(100, state.value(channel))
            assertEquals(2, state.buttonPosition(7))
        }
        state.setArmed(false)
        assertEquals(0, state.advanceButton(7))
        assertEquals(0, state.value(7))
        // Disconnect and leaving the mode still perform a full reset.
        state.reset(false)
        for (channel in 6..16) assertEquals(0, state.value(channel))
    }

    @Test fun disarmedMixedJoystickLocksThrottleAtReturnValueButAllowsAuxiliaryAxis() {
        val base = CustomControlConfig.default()
        val mixed = CustomControl(1, CustomControlType.JOYSTICK, .5f, .5f,
            CustomAxis(3, autoReturn = true, returnValue = 80),
            CustomAxis(7, autoReturn = false))
        val returning = CustomControl(2, CustomControlType.VERTICAL, .5f, .5f,
            CustomAxis(6, 10, 90, true, 35))
        val throttleButton = CustomControl(3, CustomControlType.BUTTON, .5f, .5f,
            CustomAxis(3))
        val state = CustomControlState(base.copy(
            defaults = base.defaults.toMutableList().also { it[2] = 75 },
            controls = listOf(mixed, returning)))
        assertTrue(state.isControlEnabled(mixed))
        assertFalse(state.isControlEnabled(throttleButton))
        assertEquals(80, state.value(3))
        state.move(1, 1f, 1f)
        state.release(1)
        assertEquals(80, state.value(3))
        assertEquals(100, state.value(7))
        state.move(2, 0f, 1f)
        assertEquals(90, state.value(6))
        state.release(2)
        assertEquals(35, state.value(6))
        state.setArmed(true)
        assertEquals(80, state.value(3))
        assertEquals(100, state.value(7))
        state.move(1, 1f, -1f)
        assertEquals(100, state.value(3))
        state.setArmed(false)
        assertEquals(80, state.value(3))
        val buttonState = CustomControlState(base.copy(controls = listOf(throttleButton)))
        assertEquals(0, buttonState.advanceButton(3))
        assertEquals(0, buttonState.value(3))
    }

    @Test fun throttleDisarmUsesReturnOrDefaultAccordingToAutomaticReturn() {
        val base = CustomControlConfig.default()
        for (autoReturn in listOf(false, true)) {
            for (throttleOnSecondAxis in listOf(false, true)) {
                val throttle = CustomAxis(3, autoReturn = autoReturn, returnValue = 50)
                val auxiliary = CustomAxis(7, autoReturn = false)
                val joystick = CustomControl(1, CustomControlType.JOYSTICK, .5f, .5f,
                    if (throttleOnSecondAxis) auxiliary else throttle,
                    if (throttleOnSecondAxis) throttle else auxiliary)
                val config = base.copy(controls = listOf(joystick),
                    defaults = base.defaults.toMutableList().also { it[2] = 45 })
                val state = CustomControlState(CustomControlConfig.decode(config.encode())!!)
                val parked = if (autoReturn) 50 else 45
                assertEquals(parked, state.value(3))
                state.move(1, 1f, 1f)
                assertEquals(parked, state.value(3))
                state.setArmed(true)
                assertEquals(parked, state.value(3))
                state.move(1, 1f, 1f)
                assertEquals(100, state.value(3))
                state.release(1)
                assertEquals(if (autoReturn) 50 else 100, state.value(3))
                state.setArmed(false)
                assertEquals(parked, state.value(3))
                assertEquals(100, state.value(7))
                state.setArmed(true)
                assertEquals(parked, state.value(3))
                state.reset(false)
                assertEquals(parked, state.value(3))
                assertEquals(0, state.value(7))
            }
        }
    }

    @Test fun primaryButtonsUseTheirOffValuesWhileDisarmed() {
        val base = CustomControlConfig.default()
        for (channel in 1..4) {
            val button = CustomControl(1, CustomControlType.BUTTON, .5f, .5f,
                CustomAxis(channel, autoReturn = false), offValue = 45, onValue = 100)
            val state = CustomControlState(base.copy(controls = listOf(button)))
            assertEquals(45, state.value(channel))
            assertEquals(0, state.advanceButton(1))
            assertEquals(45, state.value(channel))
            state.setArmed(true)
            state.advanceButton(1)
            assertEquals(100, state.value(channel))
            state.setArmed(false)
            assertEquals(45, state.value(channel))
            assertEquals(0, state.buttonPosition(1))
            state.setArmed(true)
            assertEquals(45, state.value(channel))
        }
    }

    @Test fun unassignedPrimaryChannelsRetainTheirConfiguredFixedDefaults() {
        val base = CustomControlConfig.default()
        val defaults = base.defaults.toMutableList().also {
            it[0] = 20; it[1] = 40; it[2] = 50; it[3] = 70
        }
        val state = CustomControlState(base.copy(controls = emptyList(),
            defaults = defaults))
        for (armed in listOf(false, true, false)) {
            state.setArmed(armed)
            assertArrayEquals(intArrayOf(20, 40, 50, 70), state.snapshot().take(4).toIntArray())
        }
    }

    @Test fun primaryAxesUseReturnOrDefaultAcrossArmingWithoutResettingAuxiliaries() {
        val base = CustomControlConfig.default()
        for (autoReturn in listOf(false, true)) {
            val config = base.copy(controls = listOf(
                CustomControl(1, CustomControlType.JOYSTICK, .2f, .5f,
                    CustomAxis(1, autoReturn = autoReturn, returnValue = 20),
                    CustomAxis(2, autoReturn = autoReturn, returnValue = 40)),
                CustomControl(2, CustomControlType.JOYSTICK, .8f, .5f,
                    CustomAxis(4, autoReturn = autoReturn, returnValue = 70),
                    CustomAxis(3, autoReturn = autoReturn, returnValue = 50)),
                CustomControl(3, CustomControlType.BUTTON, .5f, .5f, CustomAxis(7))))
            val state = CustomControlState(CustomControlConfig.decode(config.encode())!!)
            val neutral = if (autoReturn) intArrayOf(20, 40, 50, 70)
                else intArrayOf(50, 50, 0, 50)
            assertArrayEquals(neutral, state.snapshot().take(4).toIntArray())
            state.advanceButton(3)
            state.setArmed(true)
            assertArrayEquals(neutral, state.snapshot().take(4).toIntArray())
            assertEquals(100, state.value(5))
            state.move(1, 1f, -1f)
            state.move(2, -1f, 1f)
            assertArrayEquals(intArrayOf(100, 0, 100, 0), state.snapshot().take(4).toIntArray())
            state.release(1)
            state.release(2)
            assertArrayEquals(if (autoReturn) neutral else intArrayOf(100, 0, 100, 0),
                state.snapshot().take(4).toIntArray())
            state.setArmed(false)
            state.move(1, -1f, 1f)
            state.move(2, 1f, -1f)
            assertArrayEquals(neutral, state.snapshot().take(4).toIntArray())
            assertEquals(0, state.value(5))
            assertEquals(100, state.value(7))
            assertEquals(1, state.buttonPosition(3))
            state.setArmed(true)
            assertArrayEquals(neutral, state.snapshot().take(4).toIntArray())
            assertEquals(100, state.value(7))
            state.reset(false)
            assertArrayEquals(neutral, state.snapshot().take(4).toIntArray())
            assertEquals(0, state.value(7))
        }
    }

    @Test fun mixedAutomaticReturnSettingsChooseDisarmedValuesPerAxis() {
        val base = CustomControlConfig.default()
        val config = base.copy(
            defaults = base.defaults.toMutableList().also { it[2] = 50 },
            controls = listOf(
                CustomControl(1, CustomControlType.JOYSTICK, .2f, .5f,
                    CustomAxis(1, autoReturn = true, returnValue = 20),
                    CustomAxis(2, autoReturn = false, returnValue = 40)),
                CustomControl(2, CustomControlType.JOYSTICK, .8f, .5f,
                    CustomAxis(4, autoReturn = true, returnValue = 70),
                    CustomAxis(3, autoReturn = false, returnValue = 0)),
                CustomControl(3, CustomControlType.BUTTON, .5f, .5f, CustomAxis(7))))
        val state = CustomControlState(CustomControlConfig.decode(config.encode())!!)
        val parked = intArrayOf(20, 50, 50, 70)
        assertArrayEquals(parked, state.snapshot().take(4).toIntArray())
        state.advanceButton(3)
        state.setArmed(true)
        state.move(1, 1f, -1f)
        state.move(2, -1f, 1f)
        state.release(1)
        state.release(2)
        assertArrayEquals(intArrayOf(20, 0, 100, 70), state.snapshot().take(4).toIntArray())
        state.setArmed(false)
        assertArrayEquals(parked, state.snapshot().take(4).toIntArray())
        assertEquals(100, state.value(7))
        state.setArmed(true)
        assertArrayEquals(parked, state.snapshot().take(4).toIntArray())
    }
}
