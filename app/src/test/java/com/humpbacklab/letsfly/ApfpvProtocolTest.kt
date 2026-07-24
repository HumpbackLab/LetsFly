package com.humpbacklab.letsfly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApfpvProtocolTest {
    @Test
    fun controlSessionSendsConnectThroughTwoOfThreeFec() {
        val session = ApfpvProtocol.ControlSession()

        val first = session.buildControlDatagrams()
        val second = session.buildControlDatagrams()

        assertEquals(1, first.size)
        assertEquals(2, second.size)
        assertTransportHeader(first[0], 0, 0, 0)
        assertTransportHeader(second[0], 0, 0, 1)
        assertTransportHeader(second[1], 0, 0, 2)
        assertEquals(2, unsigned(first[0][12]))
        assertEquals(11L, readLe32(first[0], 13))
        assertEquals(3, unsigned(first[0][18]))
        assertEquals(0x4C46, readLe16(first[0], 21))
        assertPacketCrc(first[0], 12, 11, 5)

        val expectedParity = ByteArray(64) { index ->
            val left = unsigned(first[0][12 + index])
            val right = unsigned(second[0][12 + index])
            (multiplyByThree(left) xor multiplyByTwo(right)).toByte()
        }
        assertArrayEquals(expectedParity, second[1].copyOfRange(12, 76))
    }

    @Test
    fun controlSessionEchoesAirConfigWithoutChangingCameraSettings() {
        val session = ApfpvProtocol.ControlSession()
        val airConfig = makeAirConfig(gsDeviceId = 1)

        assertTrue(session.acceptAirConfig(airConfig))
        val datagram = session.buildControlDatagrams().single()

        assertTransportHeader(datagram, 0, 0x1234, 0, fromDeviceId = 1)
        val payload = datagram.copyOfRange(12, 76)
        assertEquals(1, unsigned(payload[0]))
        assertEquals(airConfig.size.toLong(), readLe32(payload, 1))
        assertEquals(3, unsigned(payload[6]))
        assertEquals(0x1234, readLe16(payload, 7))
        assertEquals(1, readLe16(payload, 9))
        assertEquals(0, unsigned(payload[11]))
        assertArrayEquals(
            airConfig.copyOfRange(12, airConfig.size),
            payload.copyOfRange(12, airConfig.size)
        )
        assertPacketCrc(payload, 0, airConfig.size, 5)
    }

    @Test
    fun assemblerDropsIncompleteFrameAndRecoversOnNextFrame() {
        val assembler = ApfpvFrameAssembler()
        assembler.push(fragment(10, 0, false, "old"))

        assertEquals(null, assembler.push(fragment(11, 1, true, "bad")))
        assertEquals(null, assembler.push(fragment(12, 0, false, "new")))
        val completed = assembler.push(fragment(12, 1, true, " frame"))

        assertNotNull(completed)
        assertArrayEquals("new frame".toByteArray(), completed)
    }

    @Test
    fun parserExtractsPrimaryVideoPacket() {
        val datagram = byteArrayOf(
            3, 56, 0x34, 0x12, 0x46, 0x4C, 21, 0, 1, 0, 0, 0,
            0, 21, 0, 0, 0, 0, 3, 0xC9.toByte(), 0x34, 0x12, 0x46, 0x4C,
            0, 0x80.toByte(), 7, 0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()
        )

        val fragment = ApfpvProtocol.parseVideoFragment(datagram, datagram.size)

        assertNotNull(fragment)
        assertEquals(7L, fragment!!.frameIndex)
        assertEquals(0, fragment.partIndex)
        assertEquals(true, fragment.lastPart)
        assertArrayEquals("abc".toByteArray(), fragment.payload)
    }

    @Test
    fun parserRejectsPacketOutsideSixOfEightTransportBlock() {
        val datagram = ByteArray(13)
        datagram[0] = 3
        datagram[1] = 56
        datagram[6] = 1
        datagram[11] = 8

        assertEquals(null, ApfpvProtocol.parseTransportPacket(datagram, datagram.size))
    }

    private fun fragment(frame: Long, part: Int, last: Boolean, text: String) =
        ApfpvProtocol.VideoFragment(frame, part, last, text.toByteArray())

    private fun makeAirConfig(gsDeviceId: Int): ByteArray {
        val packet = ByteArray(57)
        packet[0] = 3
        writeLe32(packet, 1, packet.size.toLong())
        packet[5] = 9
        packet[6] = 3
        writeLe16(packet, 8, 0x1234)
        writeLe16(packet, 10, gsDeviceId)
        for (index in 12 until packet.size) {
            packet[index] = (index * 7).toByte()
        }
        packet[7] = crc8(packet, 0, packet.size, 7).toByte()
        return packet
    }

    private fun assertTransportHeader(
        datagram: ByteArray,
        blockIndex: Int,
        toDeviceId: Int,
        packetIndex: Int,
        fromDeviceId: Int = 0x4C46
    ) {
        assertEquals(76, datagram.size)
        assertEquals(3, unsigned(datagram[0]))
        assertEquals(56, unsigned(datagram[1]))
        assertEquals(fromDeviceId, readLe16(datagram, 2))
        assertEquals(toDeviceId, readLe16(datagram, 4))
        assertEquals(64, readLe16(datagram, 6))
        assertEquals(blockIndex, unsigned(datagram[8]))
        assertEquals(packetIndex, unsigned(datagram[11]))
    }

    private fun assertPacketCrc(data: ByteArray, offset: Int, size: Int, crcOffset: Int) {
        assertEquals(
            unsigned(data[offset + crcOffset]),
            crc8(data, offset, size, crcOffset)
        )
    }

    private fun crc8(data: ByteArray, offset: Int, size: Int, crcOffset: Int): Int {
        var crc = 0
        for (index in offset until offset + size) {
            val value = if (index == offset + crcOffset) 0 else unsigned(data[index])
            crc = crc xor value
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF
                else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun multiplyByTwo(value: Int): Int =
        if (value and 0x80 == 0) value shl 1 else ((value shl 1) xor 0x11D) and 0xFF

    private fun multiplyByThree(value: Int): Int = multiplyByTwo(value) xor value

    private fun unsigned(value: Byte) = value.toInt() and 0xFF

    private fun readLe16(data: ByteArray, offset: Int): Int =
        unsigned(data[offset]) or (unsigned(data[offset + 1]) shl 8)

    private fun readLe32(data: ByteArray, offset: Int): Long =
        readLe16(data, offset).toLong() or (readLe16(data, offset + 2).toLong() shl 16)

    private fun writeLe16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeLe32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
        data[offset + 2] = (value ushr 16).toByte()
        data[offset + 3] = (value ushr 24).toByte()
    }
}
