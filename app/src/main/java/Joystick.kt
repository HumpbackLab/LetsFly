package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt


interface OnJoystickMoveListener {
    fun onJoystickValueChanged(x:Float, y:Float)
}

class Joystick(context: Context, attrs: AttributeSet) : View(context, attrs){
    companion object {
        // Default repeat interval for joystick updates in milliseconds
        const val DEFAULT_REPEAT_INTERVAL: Long = 1000
    }

    private var buttonRadius: Int = 0
    private var joystickRadius: Int = 0
    private var centerX: Float = 0.0f
    private var centerY: Float = 0.0f
    private var xPosition:Int = 0
    private var yPosition:Int = 0
    private var listener:OnJoystickMoveListener? = null
    private var repeatInterval:Long = DEFAULT_REPEAT_INTERVAL
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    private var defaultX:Int = 0
    private var defaultY:Int = 0

    private val defaultXPercent:Float
    private val defaultYPercent:Float

    private val xReturnDefault:Boolean
    private val yReturnDefault:Boolean

    // Flag to control extended Y-axis range
    private var extendedRangeY: Boolean = false

    var enable:Boolean=true

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.Joystick,
            0, 0).apply {
            try {
                defaultXPercent = getFloat(R.styleable.Joystick_defaultXPercent, 0f)
                defaultYPercent = getFloat(R.styleable.Joystick_defaultYPercent, 0f)
                xReturnDefault = getBoolean(R.styleable.Joystick_xReturnDefault,true)
                yReturnDefault = getBoolean(R.styleable.Joystick_yReturnDefault,true)
            } finally {
                recycle()
            }

        }
    }
    private fun createRunnable(): Runnable {
        return Runnable {
            listener?.onJoystickValueChanged(getOutX(), getOutY())
            handler.postDelayed(runnable!!, repeatInterval)
        }
    }

    // Method to enable/disable extended Y-axis range
    fun setExtendedRangeY(extended: Boolean) {
        extendedRangeY = extended
    }

    fun setOnJoystickMoveListener(listener:OnJoystickMoveListener , interval:Long){
        this.listener=listener
        repeatInterval=interval
    }

    private val mainCirclePaint = Paint(ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        color = Color.BLUE
    }
    private val sencondCirclePaint = Paint(0).apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
    }

    private val buttonPaint = Paint(ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // setting the measured values to resize the view to a certain width and
        // height
        val d = Math.min(measure(widthMeasureSpec), measure(heightMeasureSpec))
        setMeasuredDimension(d, d)
    }

    override fun onSizeChanged(xNew: Int, yNew: Int, xOld: Int, yOld: Int) {
        super.onSizeChanged(xNew, yNew, xOld, yOld)
        // before measure, get the center of view
        centerX= width/2.0f
        centerY= height/2.0f

        //xPosition = width / 2
        //yPosition = width / 2
        val d = Math.min(xNew, yNew)
        buttonRadius = (d / 2 * 0.25).toInt()
        joystickRadius = (d / 2 * 0.75).toInt()

        defaultX = (centerX + defaultXPercent*joystickRadius).toInt()
        defaultY = (centerY - defaultYPercent*joystickRadius).toInt()

        xPosition=defaultX
        yPosition=defaultY

    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.apply {
            drawCircle(centerX,centerY,joystickRadius.toFloat(),mainCirclePaint)
            drawCircle(centerX,centerY,joystickRadius.toFloat()/2,sencondCirclePaint)
            drawLine(centerX,centerY,centerX+joystickRadius,centerY,sencondCirclePaint)
            drawLine(centerX,centerY,centerX,centerY-joystickRadius,sencondCirclePaint)
            drawCircle(xPosition.toFloat(), yPosition.toFloat(), buttonRadius.toFloat(), buttonPaint)
        }
    }

    private fun getOutX()=(xPosition - centerX) / joystickRadius
    private fun getOutY()=-(yPosition - centerY) / joystickRadius

    public fun setXY(targetX:Float,targetY:Float){
        xPosition=(joystickRadius*targetX+centerX).toInt()
        yPosition=(centerY-joystickRadius*targetY).toInt()
        invalidate()
    }

    private fun measure(measureSpec: Int): Int {
        var result = 0

        // Decode the measurement specifications.
        val specMode = MeasureSpec.getMode(measureSpec)
        val specSize = MeasureSpec.getSize(measureSpec)
        result = if (specMode == MeasureSpec.UNSPECIFIED) {
            // Return a default size of 200 if no bounds are specified.
            200
        } else {
            // As you want to fill the available space
            // always return the full available bounds.
            specSize
        }
        return result
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {

        if(!enable){
            return true
        }

        xPosition = event.x.toInt()
        yPosition = event.y.toInt()

        // Handle extended Y-axis range when needed
        if (extendedRangeY) {
            // For extended range, allow movement across a larger Y range
            // We need to expand the touch boundary to allow movement beyond the joystick circle
            // but keep the visual representation appropriate

            // Calculate the extended limits, but ensure they're reasonable
            // The button can now move further vertically
            val maxUpwardMovement = (height * 1.5).toInt()  // Allow moving up 1.5 times the view height from center
            val maxDownwardMovement = (height * 0.75).toInt()  // Allow moving down 0.75 times the view height from center

            val extendedTop = (centerY - maxUpwardMovement).toInt()
            val extendedBottom = (centerY + maxDownwardMovement).toInt()

            if(yPosition > extendedBottom) {
                yPosition = extendedBottom
            } else if(yPosition < extendedTop) {
                yPosition = extendedTop
            }

            // X position remains constrained normally within joystick radius
            if(xPosition > centerX + joystickRadius) {
                xPosition = (centerX + joystickRadius).toInt()
            } else if(xPosition < centerX - joystickRadius) {
                xPosition = (centerX - joystickRadius).toInt()
            }
        } else {
            // Original behavior for normal range
            if(xPosition > centerX + joystickRadius) {
                xPosition = (centerX + joystickRadius).toInt()
            } else if(xPosition < centerX - joystickRadius) {
                xPosition = (centerX - joystickRadius).toInt()
            }

            if(yPosition > centerY + joystickRadius) {
                yPosition = (centerY + joystickRadius).toInt()
            } else if(yPosition < centerY - joystickRadius) {
                yPosition = (centerY - joystickRadius).toInt()
            }
        }

        invalidate()
        if (event.action == MotionEvent.ACTION_UP) {
            // Apply default positions based on settings
            if(xReturnDefault){
                xPosition = defaultX
            }
            if(yReturnDefault){
                yPosition = defaultY
            }

            // Remove any pending callbacks to stop the updates
            if (runnable != null) {
                handler.removeCallbacks(runnable!!)
            }
            listener?.onJoystickValueChanged(getOutX(),getOutY())

        }else if (event.action==MotionEvent.ACTION_DOWN && listener!=null){
            // Remove any existing callback to avoid duplicates
            if (runnable != null) {
                handler.removeCallbacks(runnable!!)
            }

            // Create and post the new runnable
            runnable = createRunnable()
            handler.post(runnable!!)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean up the handler to prevent memory leaks
        if (runnable != null) {
            handler.removeCallbacks(runnable!!)
        }
    }
}