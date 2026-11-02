package com.humpbacklab.letsfly

import org.junit.Assert.*
import org.junit.Test

class ElrsBaudFallbackTest {
    private class Link {
        var allowed = true
        var switches = 0
        val fallback = ElrsBaudFallback({ allowed }, { switches++ })
        val telemetry = CrsfBatteryTelemetry { fallback.receive() }
        init { fallback.start(100L, true) }
    }

    @Test fun fallsBackOnlyOnceAfterTheStartupResponseDeadline() {
        val link = Link()
        link.fallback.tick(100L + ElrsBaudFallback.WAIT_MS - 1)
        assertEquals(0, link.switches)
        link.fallback.tick(100L + ElrsBaudFallback.WAIT_MS)
        assertEquals(1, link.switches)
        link.fallback.tick(100_000L)
        assertEquals(1, link.switches)
        assertFalse(link.fallback.active)
    }

    @Test fun aModuleReplyLocksHighBaudEvenWithoutReceiverTelemetry() {
        val packet = moduleTimingFrame()
        for (split in 0..packet.size) {
            val link = Link()
            link.telemetry.feed(packet.copyOfRange(0, split), 200)
            link.telemetry.feed(packet.copyOfRange(split, packet.size), 200)
            link.fallback.tick(100_000L)
            assertEquals(0, link.switches)
            assertFalse(link.fallback.active)
            assertNull(link.telemetry.latest())
        }
    }

    @Test fun corruptTrafficDoesNotCountAsAResponse() {
        val link = Link()
        val corrupt = moduleTimingFrame().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        link.telemetry.feed(corrupt, 200)
        link.fallback.tick(100_000L)
        assertEquals(1, link.switches)
    }

    @Test fun partialRepliesDoNotCountUntilTheirCrcIsValidated() {
        val link = Link()
        val packet = moduleTimingFrame()
        link.telemetry.feed(packet.copyOf(packet.size - 1), 200)
        assertTrue(link.fallback.active)
        link.telemetry.feed(byteArrayOf(packet.last()), 300)
        assertFalse(link.fallback.active)
        link.fallback.tick(100_000L)
        assertEquals(0, link.switches)
    }

    @Test fun armingCancelsFallbackUntilTheNextUsbConnection() {
        val link = Link()
        link.allowed = false
        link.fallback.tick(200)
        link.allowed = true
        link.fallback.tick(100_000L)
        assertEquals(0, link.switches)
        assertFalse(link.fallback.active)
    }

    @Test fun disconnectOrAnAlreadyConfiguredLegacyBaudNeverSwitches() {
        val link = Link()
        link.fallback.close()
        link.fallback.tick(100_000L)
        assertEquals(0, link.switches)
        link.fallback.start(200, false)
        link.fallback.tick(100_000L)
        assertEquals(0, link.switches)
    }

    @Test fun eachNewConnectionGetsItsOwnResponseWindow() {
        val link = Link()
        link.fallback.receive()
        link.fallback.start(20_000, true)
        link.fallback.tick(20_000L + ElrsBaudFallback.WAIT_MS - 1)
        assertEquals(0, link.switches)
        link.fallback.tick(20_000L + ElrsBaudFallback.WAIT_MS)
        assertEquals(1, link.switches)
    }

    private fun moduleTimingFrame(): ByteArray {
        // A TX-local timing packet, available even when the receiver is powered off.
        val packet = byteArrayOf(0xEA.toByte(), 13, 0x3A, 0xEA.toByte(), 0xEE.toByte(),
            0x10, 0, 0, 0x4E, 0x20, 0, 0, 0, 0, 0)
        var crc = 0
        for (i in 2 until packet.lastIndex) {
            crc = crc xor (packet[i].toInt() and 0xFF)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x80 != 0) 0xD5 else 0) and 0xFF }
        }
        packet[packet.lastIndex] = crc.toByte()
        return packet
    }
}
