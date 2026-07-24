package com.humpbacklab.letsfly

import java.io.ByteArrayOutputStream

/** APFPV transport/video wire parser and JPEG frame assembler. */
internal object ApfpvProtocol {
    const val UDP_PORT = 5600
    const val CAMERA_HOST = "192.168.4.1"

    private const val PACKET_HEADER_SIZE = 12
    private const val VIDEO_HEADER_SIZE = 18
    private const val PACKET_VERSION = 3
    private const val PACKET_SIGNATURE = 56
    private const val PRIMARY_PACKET_COUNT = 6
    private const val TRANSPORT_PACKET_COUNT = 8
    private const val VIDEO_PACKET_TYPE = 0
    private const val CONFIG_PACKET_TYPE = 3
    private const val GROUND_CONNECT_PACKET_TYPE = 2
    private const val GROUND_CONFIG_PACKET_TYPE = 1
    private const val AIR_HEADER_SIZE = 12
    private const val GROUND_HEADER_SIZE = 11
    private const val GROUND_TRANSPORT_PAYLOAD_SIZE = 64
    private const val GROUND_FEC_K = 2
    private const val GROUND_FEC_N = 3
    private const val DEFAULT_GS_DEVICE_ID = 0x4C46

    data class VideoFragment(
        val frameIndex: Long,
        val partIndex: Int,
        val lastPart: Boolean,
        val payload: ByteArray
    )

    data class AirConfig(
        val packet: ByteArray,
        val airDeviceId: Int,
        val gsDeviceId: Int
    )

    data class TransportPacket(
        val blockIndex: Long,
        val packetIndex: Int,
        val payload: ByteArray
    )

    /**
     * Official APFPV control session: Connect until the Air config is received,
     * then echo that config every 250 ms through the fixed ground-to-air 2/3 FEC.
     */
    internal class ControlSession(
        private val gsDeviceId: Int = DEFAULT_GS_DEVICE_ID
    ) {
        private var activeGsDeviceId = gsDeviceId
        private var airConfig: AirConfig? = null
        private var firstPayload: ByteArray? = null
        private var blockIndex = 0L

        @Synchronized
        fun acceptAirConfig(payload: ByteArray): Boolean {
            val parsed = parseAirConfig(payload) ?: return false
            if (parsed.gsDeviceId == 0) {
                return false
            }
            // APFPV keeps its previous pairing indefinitely. Reuse the ID advertised
            // by Air so a newly installed client can resume that legitimate session.
            activeGsDeviceId = parsed.gsDeviceId
            airConfig = parsed
            return true
        }

        @Synchronized
        fun buildControlDatagrams(): List<ByteArray> {
            val config = airConfig
            val sessionPacket = if (config == null) {
                makeConnectPacket(activeGsDeviceId)
            } else {
                makeConfigPacket(config, activeGsDeviceId)
            }
            val payload = sessionPacket.copyOf(GROUND_TRANSPORT_PAYLOAD_SIZE)
            val toDeviceId = config?.airDeviceId ?: 0
            val first = firstPayload
            if (first == null) {
                firstPayload = payload
                return listOf(
                    makeTransportDatagram(activeGsDeviceId, toDeviceId, blockIndex, 0, payload)
                )
            }

            val datagrams = listOf(
                makeTransportDatagram(activeGsDeviceId, toDeviceId, blockIndex, 1, payload),
                makeTransportDatagram(
                    activeGsDeviceId,
                    toDeviceId,
                    blockIndex,
                    GROUND_FEC_K,
                    makeGroundParity(first, payload)
                )
            )
            firstPayload = null
            blockIndex = (blockIndex + 1) and 0xFF_FFFFL
            return datagrams
        }
    }

    fun parseVideoFragment(datagram: ByteArray, length: Int): VideoFragment? {
        val transportPacket = parseTransportPacket(datagram, length) ?: return null
        if (transportPacket.packetIndex >= PRIMARY_PACKET_COUNT) {
            return null
        }
        return parseVideoPacket(transportPacket.payload, transportPacket.payload.size)
    }

    fun parseTransportPacket(datagram: ByteArray, length: Int): TransportPacket? {
        if (length < PACKET_HEADER_SIZE || length > datagram.size ||
            unsigned(datagram[0]) != PACKET_VERSION ||
            unsigned(datagram[1]) != PACKET_SIGNATURE
        ) {
            return null
        }

        val transportSize = readLe16(datagram, 6)
        val packetIndex = unsigned(datagram[11])
        if (packetIndex >= TRANSPORT_PACKET_COUNT ||
            transportSize == 0 ||
            transportSize > length - PACKET_HEADER_SIZE
        ) {
            return null
        }

        val blockIndex = unsigned(datagram[8]).toLong() or
            (unsigned(datagram[9]).toLong() shl 8) or
            (unsigned(datagram[10]).toLong() shl 16)
        return TransportPacket(
            blockIndex,
            packetIndex,
            datagram.copyOfRange(PACKET_HEADER_SIZE, PACKET_HEADER_SIZE + transportSize)
        )
    }

    fun parseVideoPacket(data: ByteArray, length: Int): VideoFragment? {
        if (length < VIDEO_HEADER_SIZE || length > data.size) {
            return null
        }
        val declaredSize = readLe32(data, 1)
        if (unsigned(data[0]) != VIDEO_PACKET_TYPE ||
            declaredSize < VIDEO_HEADER_SIZE ||
            declaredSize > length ||
            !hasValidCrc(data, 0, VIDEO_HEADER_SIZE, 7)
        ) {
            return null
        }

        val partFlags = unsigned(data[13])
        return VideoFragment(
            frameIndex = readLe32(data, 14),
            partIndex = partFlags and 0x7F,
            lastPart = partFlags and 0x80 != 0,
            payload = data.copyOfRange(VIDEO_HEADER_SIZE, declaredSize.toInt())
        )
    }

    private fun parseAirConfig(data: ByteArray): AirConfig? {
        if (data.size < AIR_HEADER_SIZE ||
            unsigned(data[0]) != CONFIG_PACKET_TYPE ||
            unsigned(data[6]) != PACKET_VERSION
        ) {
            return null
        }
        val declaredSize = readLe32(data, 1)
        if (declaredSize < AIR_HEADER_SIZE || declaredSize > data.size ||
            !hasValidCrc(data, 0, declaredSize.toInt(), 7)
        ) {
            return null
        }
        return AirConfig(
            packet = data.copyOf(declaredSize.toInt()),
            airDeviceId = readLe16(data, 8),
            gsDeviceId = readLe16(data, 10)
        )
    }

    private fun makeConnectPacket(gsDeviceId: Int): ByteArray =
        ByteArray(GROUND_HEADER_SIZE).also { packet ->
            packet[0] = GROUND_CONNECT_PACKET_TYPE.toByte()
            writeLe32(packet, 1, packet.size.toLong())
            packet[6] = PACKET_VERSION.toByte()
            writeLe16(packet, 7, 0)
            writeLe16(packet, 9, gsDeviceId)
            packet[5] = crc8(packet, 0, packet.size).toByte()
        }

    private fun makeConfigPacket(config: AirConfig, gsDeviceId: Int): ByteArray =
        ByteArray(config.packet.size).also { packet ->
            packet[0] = GROUND_CONFIG_PACKET_TYPE.toByte()
            writeLe32(packet, 1, packet.size.toLong())
            packet[6] = PACKET_VERSION.toByte()
            writeLe16(packet, 7, config.airDeviceId)
            writeLe16(packet, 9, gsDeviceId)
            packet[11] = 0 // ping
            config.packet.copyInto(packet, AIR_HEADER_SIZE, AIR_HEADER_SIZE)
            packet[5] = crc8(packet, 0, packet.size).toByte()
        }

    private fun makeTransportDatagram(
        fromDeviceId: Int,
        toDeviceId: Int,
        blockIndex: Long,
        packetIndex: Int,
        payload: ByteArray
    ): ByteArray = ByteArray(PACKET_HEADER_SIZE + GROUND_TRANSPORT_PAYLOAD_SIZE).also { packet ->
        packet[0] = PACKET_VERSION.toByte()
        packet[1] = PACKET_SIGNATURE.toByte()
        writeLe16(packet, 2, fromDeviceId)
        writeLe16(packet, 4, toDeviceId)
        writeLe16(packet, 6, GROUND_TRANSPORT_PAYLOAD_SIZE)
        packet[8] = blockIndex.toByte()
        packet[9] = (blockIndex ushr 8).toByte()
        packet[10] = (blockIndex ushr 16).toByte()
        packet[11] = packetIndex.toByte()
        payload.copyInto(packet, PACKET_HEADER_SIZE, 0, GROUND_TRANSPORT_PAYLOAD_SIZE)
    }

    private fun makeGroundParity(first: ByteArray, second: ByteArray): ByteArray {
        require(first.size == GROUND_TRANSPORT_PAYLOAD_SIZE)
        require(second.size == GROUND_TRANSPORT_PAYLOAD_SIZE)
        return ByteArray(GROUND_TRANSPORT_PAYLOAD_SIZE) { index ->
            val firstValue = unsigned(first[index])
            val secondValue = unsigned(second[index])
            (multiplyByThree(firstValue) xor multiplyByTwo(secondValue)).toByte()
        }
    }

    private fun multiplyByTwo(value: Int): Int =
        if (value and 0x80 == 0) value shl 1 else ((value shl 1) xor 0x11D) and 0xFF

    private fun multiplyByThree(value: Int): Int = multiplyByTwo(value) xor value

    private fun hasValidCrc(data: ByteArray, offset: Int, size: Int, crcOffset: Int): Boolean {
        val expected = unsigned(data[crcOffset])
        data[crcOffset] = 0
        val actual = crc8(data, offset, size)
        data[crcOffset] = expected.toByte()
        return expected == actual
    }

    private fun crc8(data: ByteArray, offset: Int, size: Int): Int {
        var crc = 0
        for (index in offset until offset + size) {
            crc = crc xor unsigned(data[index])
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF
                else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

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

/** Reassembles ordered APFPV JPEG fragments and abandons a frame after any missing part. */
internal class ApfpvFrameAssembler {
    companion object {
        private const val MAX_JPEG_SIZE = 2 * 1024 * 1024
    }

    private var frameIndex: Long? = null
    private var nextPartIndex = 0
    private var frame = ByteArrayOutputStream()

    fun push(fragment: ApfpvProtocol.VideoFragment): ByteArray? {
        val currentFrameIndex = frameIndex
        if (currentFrameIndex == null || isNewer(fragment.frameIndex, currentFrameIndex)) {
            frameIndex = fragment.frameIndex
            nextPartIndex = 0
            frame.reset()
        } else if (fragment.frameIndex != currentFrameIndex) {
            return null
        }

        if (fragment.partIndex != nextPartIndex ||
            frame.size() + fragment.payload.size > MAX_JPEG_SIZE
        ) {
            return null
        }

        frame.write(fragment.payload)
        nextPartIndex++
        if (!fragment.lastPart) {
            return null
        }

        val completedFrame = frame.toByteArray()
        nextPartIndex = 0
        frame.reset()
        return completedFrame
    }

    private fun isNewer(candidate: Long, current: Long): Boolean {
        val difference = (candidate - current) and 0xFFFF_FFFFL
        return difference in 1..0x7FFF_FFFFL
    }
}
