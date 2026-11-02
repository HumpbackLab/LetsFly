package com.humpbacklab.letsfly

import org.junit.Assert.*
import org.junit.Test

class ElrsPacketRateClientTest {
    private inner class Link {
        val sent = mutableListOf<ByteArray>()
        var disarmed = true
        val client = ElrsPacketRateClient({ sent.add(it) }, { disarmed })
        var now = 0L
        val telemetry = CrsfBatteryTelemetry { client.receive(it, now) }
        fun feed(frame: ByteArray) = telemetry.feed(frame, now)
        fun feedParameter(data: ByteArray, field: Int = 1) {
            val chunks = data.toList().chunked(56)
            chunks.forEachIndexed { i, chunk ->
                feed(parameter(chunk.toByteArray(), remaining = chunks.lastIndex - i, field = field))
            }
        }
    }

    @Test fun connectingDoesNotWriteOrOverrideTheModuleRate() {
        val link = Link()
        assertTrue(link.sent.isEmpty())
        link.client.tick(10000)
        assertTrue(link.sent.isEmpty())
        assertTrue(link.client.refresh(10000))
        assertArrayEquals(bytes(0xEE, 6, 0x2C, 0xEE, 0xEA, 1, 0, 0x86), link.sent.single())
    }

    @Test fun readsOptionsAcrossEveryUsbFragmentBoundary() {
        val packet = parameter(selection())
        for (split in 0..packet.size) {
            val link = Link()
            link.client.refresh(0)
            link.feed(packet.copyOfRange(0, split))
            link.feed(packet.copyOfRange(split, packet.size))
            assertEquals(ElrsPacketRateClient.Status.READY, link.client.status)
            assertEquals("250Hz", link.client.rate!!.label)
            assertEquals(listOf(0, 2, 3), link.client.rate!!.options.map { it.value })
        }
    }

    @Test fun reassemblesChunksAndIgnoresDuplicateAndOutOfOrderChunks() {
        val link = Link()
        val data = selection()
        link.client.refresh(0)
        link.feed(parameter(data.copyOfRange(0, 12), remaining = 2))
        assertEquals(1, link.sent.last()[6].toInt())
        link.feed(parameter(data.copyOfRange(0, 12), remaining = 2))
        link.feed(parameter(data.copyOfRange(24, data.size), remaining = 0))
        assertNull(link.client.rate)
        assertEquals(2, link.sent.size)
        link.feed(parameter(data.copyOfRange(12, 24), remaining = 1))
        assertEquals(2, link.sent.last()[6].toInt())
        link.feed(parameter(data.copyOfRange(24, data.size), remaining = 0))
        assertEquals("250Hz", link.client.rate!!.label)
    }

    @Test fun writesTheWireIndexAndOnlyReportsSuccessAfterReadback() {
        val link = ready()
        assertTrue(link.client.apply(3, 100))
        assertArrayEquals(bytes(0xEE, 6, 0x2D, 0xEE, 0xEA, 1, 3, 0x9A), link.sent.last())
        assertEquals(ElrsPacketRateClient.Status.WRITING, link.client.status)
        link.feed(parameter(selection(value = 2))) // unsolicited response before verification
        link.client.tick(399)
        assertEquals(2, link.sent.size)
        link.client.tick(400)
        assertEquals(0x2C, link.sent.last()[2].toInt())
        link.feed(parameter(selection(value = 3)))
        assertEquals(ElrsPacketRateClient.Status.APPLIED, link.client.status)
        assertEquals("500Hz", link.client.rate!!.label)
        assertEquals(1, link.sent.count { it[2] == 0x2D.toByte() })
    }

    @Test fun rejectedOrClampedRateShowsActualModuleValue() {
        val link = ready()
        link.client.apply(3, 0)
        link.client.tick(300)
        link.feed(parameter(selection(value = 2)))
        assertEquals(ElrsPacketRateClient.Status.REJECTED, link.client.status)
        assertEquals("250Hz", link.client.rate!!.label)
        assertEquals(1, link.sent.count { it[2] == 0x2D.toByte() })
    }

    @Test fun disarmedGuardIsCheckedAgainAtWriteTime() {
        val link = ready()
        link.disarmed = false
        assertFalse(link.client.canChange)
        assertFalse(link.client.apply(3, 0))
        assertTrue(link.client.refresh(0)) // reading is harmless while armed
        assertTrue(link.sent.all { it[2] == 0x2C.toByte() })
    }

    @Test fun cannotWriteMissingBlankOrUnchangedOptions() {
        val link = Link()
        assertFalse(link.client.apply(3, 0))
        link.client.refresh(0)
        assertFalse(link.client.apply(3, 0))
        link.feed(parameter(selection()))
        for (value in listOf(-1, 1, 2, 4, 255)) assertFalse(link.client.apply(value, 0))
        assertTrue(link.sent.all { it[2] == 0x2C.toByte() })
    }

    @Test fun limitsReadRetriesAndAllowsExplicitRetry() {
        val link = Link()
        link.client.refresh(0)
        for (time in listOf(800L, 1600L, 2400L, 5000L)) link.client.tick(time)
        assertEquals(3, link.sent.size)
        assertEquals(ElrsPacketRateClient.Status.TIMEOUT, link.client.status)
        assertTrue(link.client.refresh(6000))
        link.feed(parameter(selection()))
        assertEquals(ElrsPacketRateClient.Status.READY, link.client.status)
    }

    @Test fun failedVerificationNeverRetriesTheRateWrite() {
        val link = ready()
        link.client.apply(3, 0)
        for (time in listOf(300L, 1100L, 1900L, 2700L)) link.client.tick(time)
        assertEquals(ElrsPacketRateClient.Status.TIMEOUT, link.client.status)
        assertEquals(1, link.sent.count { it[2] == 0x2D.toByte() })
    }

    @Test fun ignoresOtherDevicesAndFields() {
        val link = Link()
        link.client.refresh(0)
        link.feed(parameter(selection(), field = 2))
        link.feed(parameter(selection(), source = 0xEC))
        link.feed(parameter(selection(), destination = 0xEC))
        assertNull(link.client.rate)
        link.feed(parameter(selection()))
        assertEquals(ElrsPacketRateClient.Status.READY, link.client.status)
    }

    @Test fun refusesUnsupportedOrMalformedFieldsBeforeAnyWrite() {
        val invalid = listOf(
            selection(name = "Telem Ratio"), selection(type = 13), selection(type = 0x89),
            selection().copyOf(10), selection().dropLast(1).toByteArray(),
            selection(value = 1), selection(maximum = 20), selection(minimum = 4, maximum = 3),
            selection(value = 3, maximum = 2)
        )
        for (data in invalid) {
            val link = Link()
            link.client.refresh(0)
            link.feed(parameter(data))
            assertEquals(ElrsPacketRateClient.Status.UNSUPPORTED, link.client.status)
            assertFalse(link.client.apply(3, 0))
            assertTrue(link.sent.all { it[2] == 0x2C.toByte() })
        }
    }

    @Test fun respectsTheModulesMinimumAndMaximumOptionIndices() {
        val link = Link()
        link.client.refresh(0)
        link.feed(parameter(selection(minimum = 2, maximum = 2)))
        assertEquals(listOf(2), link.client.rate!!.options.map { it.value })
    }

    @Test fun batteryAndParameterTelemetryShareOneCrcCheckedStream() {
        val link = Link()
        link.client.refresh(0)
        val corrupt = parameter(selection()).also { it[10] = (it[10].toInt() xor 1).toByte() }
        val battery = bytes(0xEA, 10, 8, 0, 123, 0, 0, 0, 0, 0, 0, 0xC4)
        // Corrupt data can contain a plausible long header; allow the bounded decoder
        // to discard it before checking that the next battery frame is still delivered.
        link.feed(bytes(0xFF, 0xFF) + corrupt + ByteArray(64) { 0x55 } + battery)
        assertNull(link.client.rate)
        assertEquals(123, link.telemetry.latest()!!.voltageDecivolts)
        parameter(selection()).forEach { link.feed(byteArrayOf(it)) }
        assertEquals(ElrsPacketRateClient.Status.READY, link.client.status)
        assertEquals(123, link.telemetry.latest()!!.voltageDecivolts)
    }

    @Test fun closingCancelsPendingVerificationAndIgnoresOldResponses() {
        val link = ready()
        link.client.apply(3, 0)
        link.client.close()
        val sent = link.sent.size
        link.client.tick(10000)
        link.feed(parameter(selection(value = 3)))
        assertFalse(link.client.refresh(10000))
        assertFalse(link.client.apply(3, 10000))
        assertEquals(sent, link.sent.size)
        assertNull(link.client.rate)
        assertEquals(ElrsPacketRateClient.Status.CLOSED, link.client.status)
    }

    @Test fun capsRateChoicesAt500WithoutChangingWireIndicesOrOverridingCurrentRate() {
        val link = Link()
        link.client.refresh(0)
        val labels = "50Hz(-115dBm);250Hz(-108dBm);500Hz(-105dBm);D250(-104dBm);D500(-104dBm);F500(-104dBm);F1000(-104dBm)"
        val data = bytes(0, 9) + ("Packet Rate\u0000" + labels + "\u0000").toByteArray() + bytes(6, 0, 6, 0, 0)
        link.feedParameter(data)
        assertEquals("F1000(-104dBm)", link.client.rate!!.label)
        assertEquals((0..5).toList(), link.client.rate!!.options.map { it.value })
        assertFalse(link.client.apply(6, 10))
        assertTrue(link.sent.all { it[2] == 0x2C.toByte() })
        assertTrue(link.client.apply(5, 20))
        assertEquals(5, link.sent.last()[6].toInt())
    }

    @Test fun readsAndVerifiesTelemetryRatioThroughSameUsbStream() {
        val link = Link()
        val ratio = link.client.telemetryRatio!!
        assertTrue(ratio.refresh(0))
        assertEquals(2, link.sent.single()[5].toInt())
        link.feedParameter(ratioSelection(), field = 2)
        assertEquals("Std", ratio.rate!!.label)
        assertNull(link.client.rate)
        assertTrue(ratio.apply(6, 100))
        assertEquals(2, link.sent.last()[5].toInt())
        assertEquals(6, link.sent.last()[6].toInt())
        link.client.tick(400)
        link.feedParameter(ratioSelection(value = 6), field = 2)
        assertEquals(ElrsPacketRateClient.Status.APPLIED, ratio.status)
        assertEquals("1:8", ratio.rate!!.label)
    }

    @Test fun serializesWritesAndClosesTelemetryWithTheUsbSession() {
        val link = ready()
        val ratio = link.client.telemetryRatio!!
        ratio.refresh(0)
        link.feedParameter(ratioSelection(), field = 2)
        assertTrue(link.client.apply(3, 100))
        assertFalse(ratio.apply(6, 100))
        link.client.tick(400)
        link.feed(parameter(selection(value = 3)))
        assertTrue(ratio.apply(6, 500))
        assertFalse(link.client.apply(2, 500))
        assertFalse(link.client.refresh(500))
        link.client.close()
        val count = link.sent.size
        link.client.tick(10000)
        link.feedParameter(ratioSelection(value = 6), field = 2)
        assertEquals(count, link.sent.size)
        assertEquals(ElrsPacketRateClient.Status.CLOSED, ratio.status)
        assertNull(ratio.rate)
    }

    @Test fun refusesMisidentifiedRatioAndHonorsDisabledOptionsAndArming() {
        val link = Link()
        val ratio = link.client.telemetryRatio!!
        ratio.refresh(0)
        link.feed(parameter(selection(), field = 2))
        assertEquals(ElrsPacketRateClient.Status.UNSUPPORTED, ratio.status)
        assertFalse(ratio.apply(2, 0))
        ratio.refresh(10)
        val data = bytes(0, 9) + "Telem Ratio\u0000;;;;;;;;1:2;\u0000".toByteArray() + bytes(8, 0, 9, 0, 0)
        link.feed(parameter(data, field = 2))
        assertEquals(listOf(8), ratio.rate!!.options.map { it.value })
        assertFalse(ratio.apply(0, 20))
        ratio.refresh(30)
        link.feedParameter(ratioSelection(), field = 2)
        link.disarmed = false
        assertFalse(ratio.apply(6, 40))
    }

    private fun ratioSelection(value: Int = 0): ByteArray = bytes(0, 9) +
        "Telem Ratio\u0000Std;Off;1:128;1:64;1:32;1:16;1:8;1:4;1:2;Race\u0000".toByteArray() +
        bytes(value, 0, 9, 0, 0)

    private fun ready(): Link = Link().apply {
        client.refresh(0)
        feed(parameter(selection()))
    }

    private fun selection(name: String = "Packet Rate", type: Int = 9, value: Int = 2,
                          minimum: Int = 0, maximum: Int = 3): ByteArray =
        bytes(0, type) + (name + "\u0000" + "50Hz;;250Hz;500Hz;\u0000").toByteArray() +
            bytes(value, minimum, maximum, 0, 0)

    private fun parameter(data: ByteArray, remaining: Int = 0, field: Int = 1,
                          source: Int = 0xEE, destination: Int = 0xEA): ByteArray {
        val body = bytes(0x2B, destination, source, field, remaining) + data
        var crc = 0
        for (byte in body) {
            crc = crc xor (byte.toInt() and 255)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 128 != 0) 0xD5 else 0) and 255 }
        }
        return bytes(0xEA, body.size + 1) + body + bytes(crc)
    }

    private fun bytes(vararg data: Int) = ByteArray(data.size) { data[it].toByte() }
}
