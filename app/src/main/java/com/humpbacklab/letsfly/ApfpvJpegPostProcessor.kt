package com.humpbacklab.letsfly

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * CPU port of android_gs' source-resolution JPEG deblocking and medium adaptive
 * dithering shader. It runs on the JPEG worker before a frame reaches the UI.
 */
internal class ApfpvJpegPostProcessor {
    private data class Params(
        val strength: Float,
        val alpha: Float,
        val beta: Float,
        val ditherAmplitude: Float,
        val ditherFlatThreshold: Float
    )

    private var sourcePixels = IntArray(0)
    private var outputPixels = IntArray(0)

    fun process(bitmap: Bitmap, jpeg: ByteArray): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 4 || height < 4) {
            return bitmap
        }

        val pixelCount = width * height
        if (sourcePixels.size != pixelCount) {
            sourcePixels = IntArray(pixelCount)
            outputPixels = IntArray(pixelCount)
        }
        bitmap.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        System.arraycopy(sourcePixels, 0, outputPixels, 0, pixelCount)

        val params = buildParams(jpeg)
        deblockVerticalEdges(width, height, params)
        deblockHorizontalEdges(width, height, params)
        applyAdaptiveDithering(width, height, params)

        val output = if (bitmap.isMutable) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun deblockVerticalEdges(width: Int, height: Int, params: Params) {
        for (boundary in 8 until width step 8) {
            if (boundary + 1 >= width) {
                continue
            }
            for (y in 0 until height) {
                val row = y * width
                val p1Index = row + boundary - 2
                val p0Index = row + boundary - 1
                val q0Index = row + boundary
                val q1Index = row + boundary + 1
                filterBoundaryPair(p1Index, p0Index, q0Index, q1Index, params)
            }
        }
    }

    private fun deblockHorizontalEdges(width: Int, height: Int, params: Params) {
        for (boundary in 8 until height step 8) {
            if (boundary + 1 >= height) {
                continue
            }
            for (x in 0 until width) {
                val p1Index = (boundary - 2) * width + x
                val p0Index = (boundary - 1) * width + x
                val q0Index = boundary * width + x
                val q1Index = (boundary + 1) * width + x
                filterBoundaryPair(p1Index, p0Index, q0Index, q1Index, params)
            }
        }
    }

    private fun filterBoundaryPair(
        p1Index: Int,
        p0Index: Int,
        q0Index: Int,
        q1Index: Int,
        params: Params
    ) {
        val p1Color = sourcePixels[p1Index]
        val p0Color = sourcePixels[p0Index]
        val q0Color = sourcePixels[q0Index]
        val q1Color = sourcePixels[q1Index]
        val p1 = luma(p1Color)
        val p0 = luma(p0Color)
        val q0 = luma(q0Color)
        val q1 = luma(q1Color)
        val edge = abs(q0 - p0)
        val flatness = max(abs(p0 - p1), abs(q1 - q0))
        if (edge >= params.alpha || flatness >= params.beta) {
            return
        }

        val weight = params.strength *
            (1f - smoothstep(params.alpha * 0.35f, params.alpha, edge)) *
            (1f - smoothstep(params.beta * 0.35f, params.beta, flatness))
        if (weight <= 0f) {
            return
        }

        val local = averageColor(p1Color, p0Color, q0Color, q1Color)
        val leftTarget = mixColor(q0Color, local, 0.35f)
        val rightTarget = mixColor(p0Color, local, 0.35f)
        outputPixels[p0Index] = mixColor(outputPixels[p0Index], leftTarget, weight)
        outputPixels[q0Index] = mixColor(outputPixels[q0Index], rightTarget, weight)
    }

    private fun applyAdaptiveDithering(width: Int, height: Int, params: Params) {
        for (y in 0 until height) {
            val downY = minOf(y + 1, height - 1)
            for (x in 0 until width) {
                val rightX = minOf(x + 1, width - 1)
                val index = y * width + x
                val color = outputPixels[index]
                val currentLuma = luma(color)
                val gradient = max(
                    abs(currentLuma - luma(sourcePixels[y * width + rightX])),
                    abs(currentLuma - luma(sourcePixels[downY * width + x]))
                )
                val flatArea = 1f - smoothstep(
                    params.ditherFlatThreshold * 0.5f,
                    params.ditherFlatThreshold,
                    gradient
                )
                if (flatArea <= 0f) {
                    continue
                }

                var hash = x * 0x1f123bb5 + y * 0x05491333 + currentLuma.toInt() * 31
                hash = hash xor (hash ushr 16)
                hash *= 0x45d9f3b
                hash = hash xor (hash ushr 16)
                val noise = ((hash and 0xFFFF) / 65535f - 0.5f) *
                    params.ditherAmplitude * flatArea
                outputPixels[index] = addRgb(color, noise)
            }
        }
    }

    private fun buildParams(jpeg: ByteArray): Params {
        var offset = 0
        var quantizationTotal = 0L
        var quantizationCount = 0
        while (offset + 4 <= jpeg.size) {
            if (unsigned(jpeg[offset]) != 0xFF) {
                offset++
                continue
            }
            while (offset < jpeg.size && unsigned(jpeg[offset]) == 0xFF) {
                offset++
            }
            if (offset >= jpeg.size) {
                break
            }

            val marker = unsigned(jpeg[offset++])
            if (marker == 0xDA) {
                break
            }
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                continue
            }
            if (offset + 2 > jpeg.size) {
                break
            }
            val segmentLength = (unsigned(jpeg[offset]) shl 8) or unsigned(jpeg[offset + 1])
            offset += 2
            if (segmentLength < 2 || offset + segmentLength - 2 > jpeg.size) {
                break
            }
            val segmentEnd = offset + segmentLength - 2
            if (marker == 0xDB) {
                while (offset < segmentEnd) {
                    val tableInfo = unsigned(jpeg[offset++])
                    val entrySize = if (tableInfo ushr 4 == 0) 1 else 2
                    if (offset + 64 * entrySize > segmentEnd) {
                        break
                    }
                    repeat(64) {
                        val value = if (entrySize == 1) {
                            unsigned(jpeg[offset++])
                        } else {
                            ((unsigned(jpeg[offset]) shl 8) or unsigned(jpeg[offset + 1])).also {
                                offset += 2
                            }
                        }
                        quantizationTotal += value
                        quantizationCount++
                    }
                }
            }
            offset = segmentEnd
        }

        val average = if (quantizationCount > 0) {
            quantizationTotal.toFloat() / quantizationCount
        } else {
            57f
        }
        val severity = ((average - 12f) / 90f).coerceIn(0f, 1f)
        return Params(
            strength = 0.55f + 0.30f * severity,
            alpha = 30f + 42f * severity,
            beta = 24f + 34f * severity,
            ditherAmplitude = 6f + 8f * severity,
            ditherFlatThreshold = (0.020f + 0.025f * severity) * 255f
        )
    }

    private fun luma(color: Int): Float {
        val red = color ushr 16 and 0xFF
        val green = color ushr 8 and 0xFF
        val blue = color and 0xFF
        return red * 0.299f + green * 0.587f + blue * 0.114f
    }

    private fun averageColor(first: Int, second: Int, third: Int, fourth: Int): Int {
        val red = ((first ushr 16 and 0xFF) + (second ushr 16 and 0xFF) +
            (third ushr 16 and 0xFF) + (fourth ushr 16 and 0xFF)) / 4
        val green = ((first ushr 8 and 0xFF) + (second ushr 8 and 0xFF) +
            (third ushr 8 and 0xFF) + (fourth ushr 8 and 0xFF)) / 4
        val blue = ((first and 0xFF) + (second and 0xFF) +
            (third and 0xFF) + (fourth and 0xFF)) / 4
        return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val clamped = amount.coerceIn(0f, 1f)
        val red = mixChannel(from ushr 16 and 0xFF, to ushr 16 and 0xFF, clamped)
        val green = mixChannel(from ushr 8 and 0xFF, to ushr 8 and 0xFF, clamped)
        val blue = mixChannel(from and 0xFF, to and 0xFF, clamped)
        return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private fun mixChannel(from: Int, to: Int, amount: Float): Int =
        (from + (to - from) * amount).toInt().coerceIn(0, 255)

    private fun addRgb(color: Int, value: Float): Int {
        val red = ((color ushr 16 and 0xFF) + value).toInt().coerceIn(0, 255)
        val green = ((color ushr 8 and 0xFF) + value).toInt().coerceIn(0, 255)
        val blue = ((color and 0xFF) + value).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) {
            return if (value < edge0) 0f else 1f
        }
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun unsigned(value: Byte) = value.toInt() and 0xFF
}
