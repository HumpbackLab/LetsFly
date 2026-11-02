package com.humpbacklab.letsfly

/** Confirm the module's existing model, without selecting a different RF configuration. */
internal class ElrsStartupHandshake(
    private val send: (ByteArray) -> Unit,
    private val allowConfirm: () -> Boolean
) {
    var active = false
        private set
    private var deadline = 0L
    private var requestedAt = 0L
    private var field = 1
    private var chunk = 0
    private var remaining: Int? = null
    private val data = ArrayList<Byte>()

    fun start(nowMs: Long) {
        field = 1
        chunk = 0
        remaining = null
        data.clear()
        deadline = nowMs + 10_000L
        active = true
        request(nowMs)
    }

    fun close() { active = false }

    fun tick(nowMs: Long) {
        if (!canContinue(nowMs)) return
        // Also covers the module's UART baud search on a cold boot.
        if (nowMs - requestedAt >= 300L) request(nowMs)
    }

    /** Frames arrive through the existing CRC-checked USB stream decoder. */
    fun receive(frame: ByteArray, nowMs: Long) {
        if (!canContinue(nowMs) || frame.size < 9 ||
            frame[2].toInt() and 0xFF != 0x2B ||
            frame[3].toInt() and 0xFF != 0xEA ||
            frame[4].toInt() and 0xFF != 0xEE ||
            frame[5].toInt() and 0xFF != field) return
        val left = frame[6].toInt() and 0xFF
        if (left > 31 || remaining?.let { left != it - 1 } == true) return
        if (data.size + frame.size - 8 > 512) { close(); return }
        data.addAll(frame.slice(7 until frame.lastIndex))
        remaining = left
        if (left > 0) {
            chunk++
            request(nowMs)
            return
        }

        val bytes = data.toByteArray()
        val nameEnd = (2 until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: -1
        if (nameEnd > 2 && bytes.copyOfRange(2, nameEnd).toString(Charsets.US_ASCII) == "Model Match") {
            // ELRS exposes its current ID in the selection's units: " (ID: 0)".
            val optionsEnd = (nameEnd + 1 until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
            if (bytes[0] == 0.toByte() && bytes[1].toInt() and 0x7F == 9 && optionsEnd != null) {
                val unitsStart = optionsEnd + 5 // value, min, max, default
                val unitsEnd = (unitsStart until bytes.size).firstOrNull { bytes[it] == 0.toByte() }
                val id = unitsEnd?.let {
                    Regex("\\s*\\(ID: (\\d{1,3})\\)")
                        .matchEntire(bytes.copyOfRange(unitsStart, it).toString(Charsets.US_ASCII))
                        ?.groupValues?.get(1)?.toIntOrNull()
                }
                if (id != null && id in 0..63) {
                    close() // Exactly one confirmation per USB session; never retry a command.
                    send(modelConfirmation(id))
                    return
                }
            }
            close() // Unknown firmware format: leave the module's normal timeout intact.
            return
        }
        // Field IDs vary with firmware and hardware; do not hard-code Model Match's ID.
        field++
        if (field > 16) { close(); return }
        chunk = 0
        remaining = null
        data.clear()
        request(nowMs)
    }

    private fun canContinue(nowMs: Long): Boolean {
        if (active && (nowMs >= deadline || !allowConfirm())) close()
        return active
    }

    private fun request(nowMs: Long) {
        if (!canContinue(nowMs)) return
        requestedAt = nowMs
        val frame = byteArrayOf(0xEE.toByte(), 6, 0x2C, 0xEE.toByte(), 0xEA.toByte(), field.toByte(), chunk.toByte(), 0)
        frame[7] = crc(frame, 7, 0xD5)
        send(frame)
    }

    private fun modelConfirmation(id: Int): ByteArray {
        val frame = byteArrayOf(0xEE.toByte(), 8, 0x32, 0xEE.toByte(), 0xEA.toByte(), 0x10, 0x05, id.toByte(), 0, 0)
        frame[8] = crc(frame, 8, 0xBA)
        frame[9] = crc(frame, 9, 0xD5)
        return frame
    }

    private fun crc(frame: ByteArray, end: Int, polynomial: Int): Byte {
        var value = 0
        for (i in 2 until end) {
            value = value xor (frame[i].toInt() and 0xFF)
            repeat(8) { value = ((value shl 1) xor if (value and 0x80 != 0) polynomial else 0) and 0xFF }
        }
        return value.toByte()
    }
}
