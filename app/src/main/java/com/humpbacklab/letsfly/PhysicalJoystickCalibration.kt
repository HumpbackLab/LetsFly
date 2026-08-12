package com.humpbacklab.letsfly

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

data class JoystickTravelBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
) {
    val centerX: Float get() = (minX + maxX) / 2f
    val centerY: Float get() = (minY + maxY) / 2f
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY

    fun isUsable(minTravel: Float): Boolean = width >= minTravel && height >= minTravel
}

private class TravelAccumulator {
    private var minX = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    fun add(x: Float, y: Float) {
        minX = kotlin.math.min(minX, x)
        maxX = max(maxX, x)
        minY = kotlin.math.min(minY, y)
        maxY = max(maxY, y)
    }

    fun bounds(): JoystickTravelBounds? = if (minX.isFinite()) {
        JoystickTravelBounds(minX, maxX, minY, maxY)
    } else null
}

/** Full-screen touch collector used before the joystick views have known positions. */
class PhysicalJoystickCalibrationView(
    context: Context,
    private val promptForSide: (isLeft: Boolean) -> String,
    private val invalidPrompt: () -> String,
    private val onComplete: (left: JoystickTravelBounds, right: JoystickTravelBounds) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val minTravel = 24f * density
    private val overlayPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 22f * density
    }
    private val boundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CAF50.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private var isLeft = true
    private var leftBounds: JoystickTravelBounds? = null
    private var accumulator = TravelAccumulator()
    private var currentBounds: JoystickTravelBounds? = null
    private var message = promptForSide(true)

    init {
        isClickable = true
        contentDescription = message
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val lines = message.split('\n')
        val startY = height / 2f - (lines.size - 1) * textPaint.textSize * 0.65f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, width / 2f, startY + index * textPaint.textSize * 1.3f, textPaint)
        }
        currentBounds?.let {
            canvas.drawRect(it.minX, it.minY, it.maxX, it.maxY, boundsPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                accumulator = TravelAccumulator()
                currentBounds = null
                addEventPoints(event)
            }
            MotionEvent.ACTION_MOVE -> addEventPoints(event)
            MotionEvent.ACTION_UP -> {
                addEventPoints(event)
                val result = accumulator.bounds()
                if (result == null || !result.isUsable(minTravel)) {
                    message = invalidPrompt()
                    contentDescription = message
                    currentBounds = null
                } else if (isLeft) {
                    leftBounds = result
                    isLeft = false
                    currentBounds = null
                    message = promptForSide(false)
                    contentDescription = message
                    announceForAccessibility(message)
                } else {
                    onComplete(requireNotNull(leftBounds), result)
                }
            }
            MotionEvent.ACTION_CANCEL -> currentBounds = null
        }
        invalidate()
        return true
    }

    private fun addEventPoints(event: MotionEvent) {
        for (i in 0 until event.historySize) {
            accumulator.add(event.getHistoricalX(0, i), event.getHistoricalY(0, i))
        }
        accumulator.add(event.getX(0), event.getY(0))
        currentBounds = accumulator.bounds()
    }
}
