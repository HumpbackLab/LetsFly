package com.humpbacklab.letsfly

import java.util.TreeMap

/** Decodes APFPV's default systematic zfec 6/8 transport blocks over GF(256). */
internal class ApfpvFecDecoder {
    companion object {
        private const val CODING_K = 6
        private const val CODING_N = 8
        private const val MAX_BLOCK_QUEUE_SIZE = 3
        private const val RESTART_BACKJUMP_BLOCKS = 64L

        // This is the systematic matrix produced by fec_new(6, 8) in hx-esp32-cam-fpv.
        private val ENCODING_MATRIX = arrayOf(
            intArrayOf(1, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 0, 0, 0, 0),
            intArrayOf(0, 0, 1, 0, 0, 0),
            intArrayOf(0, 0, 0, 1, 0, 0),
            intArrayOf(0, 0, 0, 0, 1, 0),
            intArrayOf(0, 0, 0, 0, 0, 1),
            intArrayOf(6, 38, 197, 229, 63, 62),
            intArrayOf(130, 23, 173, 221, 230, 2)
        )
    }

    data class DecodedPacket(val payload: ByteArray, val restoredByFec: Boolean)

    private class Block(val index: Long) {
        val packets = arrayOfNulls<ByteArray>(CODING_N)
        val restored = BooleanArray(CODING_K)
        var nextPrimary = 0

        fun packetCount() = packets.count { it != null }
    }

    private val blocks = TreeMap<Long, Block>()
    private var nextBlockIndex = 0L

    fun push(packet: ApfpvProtocol.TransportPacket): List<DecodedPacket> {
        var block = blocks[packet.blockIndex]
        if (block == null) {
            if (packet.blockIndex < nextBlockIndex) {
                val backjump = nextBlockIndex - packet.blockIndex
                if (backjump < RESTART_BACKJUMP_BLOCKS) {
                    return emptyList()
                }
                blocks.clear()
                nextBlockIndex = packet.blockIndex
            }
            block = Block(packet.blockIndex)
            blocks[packet.blockIndex] = block
        }

        if (block.packets[packet.packetIndex] == null) {
            block.packets[packet.packetIndex] = packet.payload
        }
        return drain()
    }

    private fun drain(): List<DecodedPacket> {
        val output = mutableListOf<DecodedPacket>()
        while (blocks.isNotEmpty()) {
            val entry = blocks.entries.first()
            val block = entry.value

            while (block.nextPrimary < CODING_K) {
                val primary = block.packets[block.nextPrimary] ?: break
                output += DecodedPacket(primary, block.restored[block.nextPrimary])
                block.nextPrimary++
            }
            if (block.nextPrimary == CODING_K) {
                nextBlockIndex = block.index + 1
                blocks.remove(entry.key)
                continue
            }

            if (block.packetCount() >= CODING_K && restoreMissingPrimaries(block)) {
                continue
            }

            // Keep a small ordered window so normal UDP reordering does not turn a
            // recoverable 6/8 block into a missing JPEG fragment.
            if (blocks.size > MAX_BLOCK_QUEUE_SIZE) {
                nextBlockIndex = block.index + 1
                blocks.remove(entry.key)
                continue
            }
            break
        }
        return output
    }

    private fun restoreMissingPrimaries(block: Block): Boolean {
        val selectedIndices = IntArray(CODING_K)
        val selectedPayloads = arrayOfNulls<ByteArray>(CODING_K)
        val parityPackets = (CODING_K until CODING_N)
            .filter { block.packets[it] != null }
            .iterator()

        for (primaryIndex in 0 until CODING_K) {
            val primary = block.packets[primaryIndex]
            if (primary != null) {
                selectedIndices[primaryIndex] = primaryIndex
                selectedPayloads[primaryIndex] = primary
            } else {
                if (!parityPackets.hasNext()) {
                    return false
                }
                val parityIndex = parityPackets.next()
                selectedIndices[primaryIndex] = parityIndex
                selectedPayloads[primaryIndex] = block.packets[parityIndex]
            }
        }

        val packetSize = selectedPayloads[0]!!.size
        if (selectedPayloads.any { it == null || it.size != packetSize }) {
            return false
        }
        val decodeMatrix = invertMatrix(
            Array(CODING_K) { row -> ENCODING_MATRIX[selectedIndices[row]].copyOf() }
        ) ?: return false

        for (primaryIndex in 0 until CODING_K) {
            if (block.packets[primaryIndex] != null) {
                continue
            }
            val restoredPacket = ByteArray(packetSize)
            for (sourceIndex in 0 until CODING_K) {
                val coefficient = decodeMatrix[primaryIndex][sourceIndex]
                if (coefficient == 0) {
                    continue
                }
                val source = selectedPayloads[sourceIndex]!!
                for (byteIndex in restoredPacket.indices) {
                    restoredPacket[byteIndex] = (
                        restoredPacket[byteIndex].toInt() xor
                            GaloisField.multiply(coefficient, source[byteIndex].toInt() and 0xFF)
                    ).toByte()
                }
            }
            block.packets[primaryIndex] = restoredPacket
            block.restored[primaryIndex] = true
        }
        return true
    }

    private fun invertMatrix(matrix: Array<IntArray>): Array<IntArray>? {
        val augmented = Array(CODING_K) { row ->
            IntArray(CODING_K * 2).also { values ->
                matrix[row].copyInto(values)
                values[CODING_K + row] = 1
            }
        }

        for (column in 0 until CODING_K) {
            val pivot = (column until CODING_K).firstOrNull { augmented[it][column] != 0 }
                ?: return null
            if (pivot != column) {
                val temporary = augmented[column]
                augmented[column] = augmented[pivot]
                augmented[pivot] = temporary
            }

            val pivotInverse = GaloisField.inverse(augmented[column][column])
            for (index in 0 until CODING_K * 2) {
                augmented[column][index] = GaloisField.multiply(augmented[column][index], pivotInverse)
            }
            for (row in 0 until CODING_K) {
                if (row == column) {
                    continue
                }
                val factor = augmented[row][column]
                if (factor == 0) {
                    continue
                }
                for (index in 0 until CODING_K * 2) {
                    augmented[row][index] = augmented[row][index] xor
                        GaloisField.multiply(factor, augmented[column][index])
                }
            }
        }
        return Array(CODING_K) { row -> augmented[row].copyOfRange(CODING_K, CODING_K * 2) }
    }

    private object GaloisField {
        private val exponent = IntArray(510)
        private val logarithm = IntArray(256)

        init {
            var value = 1
            for (index in 0 until 255) {
                exponent[index] = value
                logarithm[value] = index
                value = value shl 1
                if (value and 0x100 != 0) {
                    value = value xor 0x11D
                }
            }
            for (index in 255 until exponent.size) {
                exponent[index] = exponent[index - 255]
            }
        }

        fun multiply(left: Int, right: Int): Int {
            if (left == 0 || right == 0) {
                return 0
            }
            return exponent[logarithm[left] + logarithm[right]]
        }

        fun inverse(value: Int): Int = exponent[255 - logarithm[value]]
    }
}
