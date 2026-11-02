package com.humpbacklab.letsfly

import org.junit.Assert.*
import org.junit.Test

class ElrsStartupHandshakeTest {
    private class Link {
        var now = 0L
        var allowed = true
        val sent = mutableListOf<ByteArray>()
        val client = ElrsStartupHandshake({ sent.add(it) }, { allowed })
        val telemetry = CrsfBatteryTelemetry { client.receive(it, now) }
        init { client.start(now) }
        fun feed(frame: ByteArray) = telemetry.feed(frame, now)
        fun commands() = sent.filter { it[2] == 0x32.toByte() }
    }

    @Test fun confirmsExistingNonzeroModelWithBothCommandCrcsAndDoesNotWriteSettings() {
        val link = Link()
        link.feed(parameter(selection("Packet Rate", "Hz")))
        assertEquals(2, link.sent.last()[5].toInt())
        link.feed(parameter(selection("Model Match", " (ID: 37)"), field = 2))
        assertArrayEquals(hex("EE 08 32 EE EA 10 05 25 CA 0C"), link.commands().single())
        assertFalse(link.client.active)
        assertFalse(link.sent.any { it[2] == 0x2D.toByte() })
        link.feed(parameter(selection("Model Match", " (ID: 37)"), field = 2))
        link.client.tick(500)
        assertEquals(1, link.commands().size)
    }

    @Test fun acceptsEveryUsbFragmentBoundary() {
        val packet = parameter(selection("Model Match", " (ID: 0)"))
        for (split in 0..packet.size) {
            val link = Link()
            link.feed(packet.copyOfRange(0, split))
            link.feed(packet.copyOfRange(split, packet.size))
            assertArrayEquals(hex("EE 08 32 EE EA 10 05 00 DC 4D"), link.commands().single())
        }
    }

    @Test fun handlesChunkedFieldsAndIgnoresOldOrOutOfOrderChunks() {
        val link = Link()
        val data = selection("Model Match", " (ID: 63)")
        val first = parameter(data.copyOfRange(0, 12), left = 2)
        link.feed(first)
        assertEquals(1, link.sent.last()[6].toInt())
        link.feed(first)
        link.feed(parameter(data.copyOfRange(24, data.size)))
        assertTrue(link.commands().isEmpty())
        link.feed(parameter(data.copyOfRange(12, 24), left = 1))
        assertEquals(2, link.sent.last()[6].toInt())
        link.feed(parameter(data.copyOfRange(24, data.size)))
        assertArrayEquals(hex("EE 08 32 EE EA 10 05 3F D0 F6"), link.commands().single())
    }

    @Test fun ignoresForeignFieldsDevicesAndCorruptFrames() {
        val link = Link()
        val data = selection("Model Match", " (ID: 0)")
        link.feed(parameter(data, field = 2))
        link.feed(parameter(data, source = 0xEC))
        link.feed(parameter(data, destination = 0xEC))
        val damaged = parameter(data).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        link.feed(damaged)
        assertTrue(link.commands().isEmpty())
        // Flush any false header left by corrupt bytes before testing the next valid frame.
        link.feed(ByteArray(64))
        link.feed(parameter(data))
        assertEquals(1, link.commands().size)
    }

    @Test fun unknownOrInvalidModelFormatNeverGuessesAModelId() {
        val variants = listOf(
            selection("Model Match", ""), selection("Model Match", " (ID: 64)"),
            selection("Model Match", " (ID: -1)"), selection("Model Match", "ID: 1"),
            selection("Model Match", " (ID: 1)").dropLast(1).toByteArray(),
            selection("Model Match", " (ID: 1)").also { it[1] = 10 },
            selection("Model Match", " (ID: 1)").also { it[0] = 1 }
        )
        for (data in variants) {
            val link = Link()
            link.feed(parameter(data))
            assertFalse(link.client.active)
            assertTrue(link.commands().isEmpty())
        }
    }

    @Test fun retriesReadsDuringColdBootButStopsAtDeadline() {
        val link = Link()
        link.client.tick(299)
        assertEquals(1, link.sent.size)
        link.client.tick(300)
        assertEquals(2, link.sent.size)
        link.client.tick(10_000)
        link.now = 10_000
        link.feed(parameter(selection("Model Match", " (ID: 0)")))
        assertFalse(link.client.active)
        assertEquals(2, link.sent.size)
        assertTrue(link.commands().isEmpty())
    }

    @Test fun armingAndClosingCancelTheHandshakeIncludingQueuedResponses() {
        for (arm in listOf(true, false)) {
            val link = Link()
            if (arm) link.allowed = false else link.client.close()
            link.feed(parameter(selection("Model Match", " (ID: 0)")))
            link.client.tick(500)
            assertFalse(link.client.active)
            assertEquals(1, link.sent.size)
            assertTrue(link.commands().isEmpty())
        }
    }

    @Test fun fieldSearchIsBoundedAndReadsOnly() {
        val link = Link()
        for (field in 1..16) link.feed(parameter(selection("Other", ""), field = field))
        assertFalse(link.client.active)
        assertEquals(16, link.sent.size)
        assertTrue(link.sent.all { it[2] == 0x2C.toByte() })
    }

    private fun selection(name: String, units: String): ByteArray =
        byteArrayOf(0, 9) + "$name\u0000Off;On\u0000".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 1, 0) + "$units\u0000".toByteArray(Charsets.US_ASCII)

    private fun parameter(data: ByteArray, field: Int = 1, left: Int = 0,
                          source: Int = 0xEE, destination: Int = 0xEA): ByteArray {
        val frame = byteArrayOf(0xEA.toByte(), (data.size + 6).toByte(), 0x2B,
            destination.toByte(), source.toByte(), field.toByte(), left.toByte()) + data + byteArrayOf(0)
        var crc = 0
        for (byte in frame.slice(2 until frame.lastIndex)) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x80 != 0) 0xD5 else 0) and 0xFF }
        }
        frame[frame.lastIndex] = crc.toByte()
        return frame
    }

    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
