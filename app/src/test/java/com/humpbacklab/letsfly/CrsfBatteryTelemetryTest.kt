package com.humpbacklab.letsfly

import org.junit.Assert.*
import org.junit.Test

class CrsfBatteryTelemetryTest {
    // Independent wire vectors, with CRC-8/DVB-S2 over type and payload.
    private val analogFrame = bytes(0xEA, 10, 8, 0, 123, 0, 0, 0, 0, 0, 0, 0xC4)
    private val fullFrame = bytes(0xEA, 10, 8, 0, 168, 0, 35, 0x12, 0x34, 0x56, 72, 0xB4)

    @Test fun decodesBigEndianBatteryValuesAndTimestamp() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(fullFrame, 100)
        assertEquals(CrsfBatteryReading(168, 35, 0x123456, 72, 100), telemetry.latest())
        assertEquals(16.8, telemetry.latest()!!.voltageDecivolts / 10.0, 0.0001)
    }

    @Test fun analogVbatDoesNotPretendToReportZeroPercent() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(analogFrame, 0)
        assertEquals(123, telemetry.latest()!!.voltageDecivolts)
        assertNull(telemetry.latest()!!.remainingPercent)
    }

    @Test fun acceptsEveryFragmentBoundaryAndSingleByteReads() {
        for (split in 0..fullFrame.size) {
            val telemetry = CrsfBatteryTelemetry()
            telemetry.feed(fullFrame.copyOfRange(0, split), 5)
            telemetry.feed(fullFrame.copyOfRange(split, fullFrame.size), 5)
            assertEquals(72, telemetry.latest()!!.remainingPercent)
        }
        val telemetry = CrsfBatteryTelemetry()
        fullFrame.forEach { telemetry.feed(byteArrayOf(it), 9) }
        assertEquals(168, telemetry.latest()!!.voltageDecivolts)
    }

    @Test fun handlesConcatenatedFramesAndIgnoresOtherTelemetry() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(analogFrame + frame(bytes(1, 2, 3), type = 0x14) + fullFrame, 20)
        assertEquals(168, telemetry.latest()!!.voltageDecivolts)
        telemetry.feed(frame(bytes(4, 5), type = 0x16), 100)
        assertEquals(20L, telemetry.latest()!!.receivedAtMs)
    }

    @Test fun rejectsCorruptCrcAndRecoversFollowingValidFrame() {
        val telemetry = CrsfBatteryTelemetry()
        val damaged = analogFrame.copyOf().also { it[4] = 100 }
        telemetry.feed(damaged, 10)
        assertNull(telemetry.latest())
        telemetry.feed(fullFrame, 30)
        assertEquals(30L, telemetry.latest()!!.receivedAtMs)
    }

    @Test fun recoversNoiseAndInvalidLengthWithoutGrowingBuffer() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(ByteArray(4096) { 0xFF.toByte() } +
            bytes(0xEA, 0, 0xEA, 1, 0xEA, 63, 0xEA, 255) + analogFrame, 1)
        assertEquals(123, telemetry.latest()!!.voltageDecivolts)
        // A corrupted long header is discarded after the bounded frame is available.
        telemetry.feed(bytes(0xEA, 62, 0x55) + fullFrame + ByteArray(64) { 0x55 }, 2)
        assertEquals(168, telemetry.latest()!!.voltageDecivolts)
    }

    @Test fun payloadContainingValidHeaderMustNotBreakFragmentedFrame() {
        // C8 02 01 D5 inside current/capacity is a complete, CRC-valid non-battery frame.
        val packet = frame(bytes(0, 100, 0xC8, 2, 1, 0xD5, 0, 80))
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(packet.copyOfRange(0, packet.size - 1), 0)
        assertNull(telemetry.latest())
        telemetry.feed(packet.copyOfRange(packet.size - 1, packet.size), 1)
        assertEquals(100, telemetry.latest()!!.voltageDecivolts)
        assertEquals(80, telemetry.latest()!!.remainingPercent)
    }

    @Test fun rejectsShortPayloadAndUnusableVoltage() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(frame(bytes(0, 100, 0, 0, 0, 0, 0)), 0)
        telemetry.feed(frame(bytes(0, 0, 0, 0, 0, 0, 0, 0)), 1)
        telemetry.feed(frame(bytes(0xFF, 0xFF, 0, 0, 0, 0, 0, 0)), 2)
        assertNull(telemetry.latest())
    }

    @Test fun invalidPercentageStillAllowsVoltageReading() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(frame(bytes(0, 121, 0, 0, 0, 0, 0, 255)), 0)
        assertEquals(121, telemetry.latest()!!.voltageDecivolts)
        assertNull(telemetry.latest()!!.remainingPercent)
    }

    @Test fun realSensorCanReportZeroPercentAndSignedCurrent() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(frame(bytes(0, 110, 0xFF, 0xF6, 0, 1, 0, 0)), 0)
        assertEquals(-10, telemetry.latest()!!.currentDeciamps)
        assertEquals(0, telemetry.latest()!!.remainingPercent)
    }

    @Test fun supportsPrimarySensorIdAndIgnoresAlternateBattery() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(frame(bytes(0, 150, 0, 0, 0, 0, 0, 50, 0)), 10)
        telemetry.feed(frame(bytes(0, 200, 0, 0, 0, 0, 0, 90, 1)), 20)
        assertEquals(150, telemetry.latest()!!.voltageDecivolts)
        assertEquals(10L, telemetry.latest()!!.receivedAtMs)
    }

    @Test fun expiresWithoutRefreshFromUnrelatedOrInvalidData() {
        val telemetry = CrsfBatteryTelemetry()
        telemetry.feed(analogFrame, 100)
        telemetry.feed(frame(bytes(1, 2, 3), type = 0x14), 4_000)
        telemetry.feed(analogFrame.copyOf().also { it[it.lastIndex] = 0 }, 4_900)
        val reading = telemetry.latest()!!
        assertTrue(reading.isFresh(5_099))
        assertFalse(reading.isFresh(5_100))
        assertFalse(reading.isFresh(99))
        telemetry.feed(fullFrame, 5_200)
        assertTrue(telemetry.latest()!!.isFresh(5_200))
    }

    @Test fun newSerialSessionDoesNotReuseOldReadingOrPartialFrame() {
        val old = CrsfBatteryTelemetry()
        old.feed(analogFrame, 0)
        old.feed(fullFrame.copyOfRange(0, 5), 0)
        val fresh = CrsfBatteryTelemetry()
        old.feed(fullFrame.copyOfRange(5, fullFrame.size), 10)
        assertNull(fresh.latest())
        fresh.feed(analogFrame, 20)
        assertEquals(123, fresh.latest()!!.voltageDecivolts)
        assertEquals(20L, fresh.latest()!!.receivedAtMs)
    }

    @Test fun acceptsMaximumFrameAndKnownRoutingAddresses() {
        for (address in listOf(0xEA, 0xEE, 0xEC, 0xC8, 0)) {
            val telemetry = CrsfBatteryTelemetry()
            telemetry.feed(frame(ByteArray(60) { 0x55 }, type = 0x14, address = address), 0)
            telemetry.feed(analogFrame.copyOf().also { it[0] = address.toByte() }, 1)
            assertEquals(123, telemetry.latest()!!.voltageDecivolts)
        }
    }

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun frame(payload: ByteArray, type: Int = 8, address: Int = 0xEA): ByteArray {
        val body = byteArrayOf(type.toByte()) + payload
        val crc = CRSFData().crsf_crc8(body.toUByteArray()).toByte()
        return bytes(address, payload.size + 2) + body + byteArrayOf(crc)
    }
}
