package com.humpbacklab.letsfly

data class CrsfBatteryReading(
    val voltageDecivolts: Int,
    val currentDeciamps: Int,
    val capacityUsedMah: Int,
    val remainingPercent: Int?,
    val receivedAtMs: Long
) {
    fun isFresh(nowMs: Long): Boolean = nowMs >= receivedAtMs &&
        nowMs - receivedAtMs < CrsfBatteryTelemetry.TIMEOUT_MS
}

/** CRSF byte stream from the existing USB read callback; no changes to RC output. */
class CrsfBatteryTelemetry {
    companion object {
        const val TIMEOUT_MS = 5_000L
        private const val MAX_FRAME_SIZE = 64
    }

    private val buffer = ByteArray(MAX_FRAME_SIZE)
    private var size = 0
    private var reading: CrsfBatteryReading? = null

    @Synchronized
    fun latest(): CrsfBatteryReading? = reading

    @Synchronized
    fun feed(bytes: ByteArray, nowMs: Long) {
        for (byte in bytes) {
            if (size == buffer.size) discard(1)
            buffer[size++] = byte
            drain(nowMs)
        }
    }

    private fun drain(nowMs: Long) {
        while (size >= 2) {
            val length = unsigned(1)
            if (!isAddress(unsigned(0)) || length !in 2..MAX_FRAME_SIZE - 2) {
                discard(1)
                continue
            }
            val frameSize = length + 2
            // A payload can itself contain header-like bytes; wait for the outer CRC.
            if (size < frameSize) return
            if (!validCrc(0, frameSize)) {
                discard(1)
                continue
            }
            if (unsigned(2) == 0x08 && length >= 10) {
                // Optional sensor ID: show the primary battery rather than alternate sensors.
                if (length == 10 || unsigned(11) == 0) decodeBattery(nowMs)
            }
            discard(frameSize)
        }
    }

    private fun decodeBattery(nowMs: Long) {
        val voltage = (unsigned(3) shl 8) or unsigned(4)
        if (voltage !in 1..0x7FFF) return
        val current = ((unsigned(5) shl 8) or unsigned(6)).toShort().toInt()
        val capacity = (unsigned(7) shl 16) or (unsigned(8) shl 8) or unsigned(9)
        val remaining = unsigned(10)
        // Gyro-ELRS analog VBAT leaves current/capacity/remaining at zero: voltage only.
        val percent = remaining.takeIf {
            it in 1..100 || (it == 0 && (current != 0 || capacity != 0))
        }
        reading = CrsfBatteryReading(voltage, current, capacity, percent, nowMs)
    }

    private fun validCrc(start: Int, frameSize: Int): Boolean {
        var crc = 0
        for (index in start + 2 until start + frameSize - 1) {
            crc = crc xor unsigned(index)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0xD5 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc == unsigned(start + frameSize - 1)
    }

    private fun discard(count: Int) {
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private fun unsigned(index: Int): Int = buffer[index].toInt() and 0xFF

    private fun isAddress(value: Int): Boolean =
        value == 0xEA || value == 0xEE || value == 0xEC || value == 0xC8 || value == 0
}
