package com.humpbacklab.letsfly

/** Main-thread CRSF parameter exchange over the existing USB session. */
internal class ElrsPacketRateClient(
    private val send: (ByteArray) -> Unit,
    private val allowChange: () -> Boolean,
    private val fieldId: Int = 1,
    private val fieldName: String = "Packet Rate"
) {
    companion object {
        // Owned and cleared by MainActivity; settings never opens a second USB connection.
        var active: ElrsPacketRateClient? = null
        private const val RETRY_MS = 800L
    }

    enum class Status { IDLE, READING, READY, WRITING, APPLIED, REJECTED, TIMEOUT, UNSUPPORTED, CLOSED }
    data class Option(val value: Int, val label: String)
    data class Rate(val options: List<Option>, val value: Int,
                    val label: String = options.first { it.value == value }.label)

    val telemetryRatio: ElrsPacketRateClient? = if (fieldId == 1)
        ElrsPacketRateClient(send, { allowChange() && !busy }, 2, "Telem Ratio") else null

    var status = Status.IDLE
        private set
    var rate: Rate? = null
        private set
    val canChange: Boolean get() = status != Status.CLOSED && allowChange() && telemetryRatio?.busy != true
    val busy: Boolean get() = status == Status.READING || status == Status.WRITING
    private var chunk: Int? = null
    private var expectedRemaining: Int? = null
    private val data = ArrayList<Byte>()
    private var sentAt = 0L
    private var attempts = 0
    private var requestedValue: Int? = null
    private var verifyAt: Long? = null

    fun refresh(nowMs: Long): Boolean {
        if (status == Status.CLOSED || busy || telemetryRatio?.busy == true) return false
        requestedValue = null
        status = Status.READING
        startRead(nowMs)
        return true
    }

    fun apply(value: Int, nowMs: Long): Boolean {
        if (!canChange || busy || status !in listOf(Status.READY, Status.APPLIED, Status.REJECTED) ||
            rate?.options?.none { it.value == value } != false || rate?.value == value) return false
        requestedValue = value
        status = Status.WRITING
        chunk = null
        // Let the module commit the parameter and refresh its Lua value before reading it back.
        verifyAt = nowMs + 300L
        send(frame(0x2D, value))
        return true
    }

    fun tick(nowMs: Long) {
        telemetryRatio?.tick(nowMs)
        verifyAt?.let {
            if (nowMs >= it) {
                verifyAt = null
                startRead(nowMs)
            }
        }
        if (chunk != null && nowMs - sentAt >= RETRY_MS) {
            if (attempts >= 3) fail(Status.TIMEOUT) else requestChunk(nowMs)
        }
    }

    // Only complete, CRC-validated frames from the USB stream decoder are passed here.
    fun receive(packet: ByteArray, nowMs: Long) {
        telemetryRatio?.receive(packet, nowMs)
        if (chunk == null || packet.size < 9 || u(packet[1]) + 2 != packet.size ||
            u(packet[2]) != 0x2B || u(packet[3]) != 0xEA || u(packet[4]) != 0xEE ||
            u(packet[5]) != fieldId) return
        val remaining = u(packet[6])
        if (remaining > 63 || (expectedRemaining != null && remaining != expectedRemaining)) return
        data.addAll(packet.copyOfRange(7, packet.size - 1).toList())
        if (data.size > 1024) { fail(Status.UNSUPPORTED); return }
        if (remaining > 0) {
            chunk = chunk!! + 1
            expectedRemaining = remaining - 1
            attempts = 0
            requestChunk(nowMs)
            return
        }
        chunk = null
        val result = decode(data.toByteArray())
        data.clear()
        if (result == null) { fail(Status.UNSUPPORTED); return }
        rate = result
        status = when {
            requestedValue == null -> Status.READY
            requestedValue == result.value -> Status.APPLIED
            else -> Status.REJECTED
        }
        requestedValue = null
    }

    fun close() {
        telemetryRatio?.close()
        fail(Status.CLOSED)
        rate = null
    }

    private fun startRead(nowMs: Long) {
        data.clear()
        expectedRemaining = null
        chunk = 0
        attempts = 0
        requestChunk(nowMs)
    }

    private fun requestChunk(nowMs: Long) {
        val index = chunk ?: return
        sentAt = nowMs
        attempts++
        send(frame(0x2C, index))
    }

    private fun fail(result: Status) {
        chunk = null
        verifyAt = null
        requestedValue = null
        data.clear()
        status = result
    }

    private fun decode(bytes: ByteArray): Rate? {
        if (bytes.size < 3 || u(bytes[1]) != 9) return null
        var offset = 2
        fun string(): String? {
            val start = offset
            while (offset < bytes.size && bytes[offset] != 0.toByte()) offset++
            if (offset >= bytes.size) return null
            return String(bytes, start, offset++ - start, Charsets.UTF_8)
        }
        // Field IDs differ across devices: verify the name and type before permitting writes.
        if (string() != fieldName) return null
        val labels = string()?.split(';') ?: return null
        if (bytes.size - offset < 5) return null
        val value = u(bytes[offset++])
        val minimum = u(bytes[offset++])
        val maximum = u(bytes[offset++])
        offset++ // default value
        if (string() == null || minimum > maximum || maximum >= labels.size) return null
        // Empty entries are disabled modes. Preserve their wire indices when filtering them out.
        val supported = labels.mapIndexedNotNull { index, label ->
            if (index in minimum..maximum && label.isNotBlank()) Option(index, label) else null
        }
        val current = supported.firstOrNull { it.value == value } ?: return null
        val options = if (fieldId == 1) supported.filter {
            // Keep wire indices, including D/F modes; unknown rate formats are not writable.
            Regex("^[A-Za-z]*(\\d+)").find(it.label)?.groupValues?.get(1)
                ?.toIntOrNull()?.let { hz -> hz in 1..500 } == true
        } else supported
        return Rate(options, value, current.label)
    }

    private fun frame(type: Int, value: Int): ByteArray {
        val packet = byteArrayOf(0xEE.toByte(), 6, type.toByte(), 0xEE.toByte(), 0xEA.toByte(),
            fieldId.toByte(), value.toByte(), 0)
        var crc = 0
        for (index in 2..6) {
            crc = crc xor u(packet[index])
            repeat(8) {
                crc = ((crc shl 1) xor if (crc and 0x80 != 0) 0xD5 else 0) and 0xFF
            }
        }
        packet[7] = crc.toByte()
        return packet
    }

    private fun u(value: Byte): Int = value.toInt() and 0xFF
}
