package com.humpbacklab.letsfly

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.roundToInt

internal object CustomControlViews {
    fun whenCanvasReady(canvas: FrameLayout, action: () -> Unit) {
        if (canvas.width > 0 && canvas.height > 0) {
            canvas.post { action() }
            return
        }
        canvas.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, left: Int, top: Int, right: Int,
                bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (view.width <= 0 || view.height <= 0) return
                view.removeOnLayoutChangeListener(this)
                canvas.post { action() }
            }
        })
    }

    fun area(context: Context, control: CustomControl, width: Int,
        height: Int): CustomControlArea {
        val (controlWidth, controlHeight) = size(context, control, width, height)
        return CustomControlGeometry.area(control.id, control.type,
            control.x * width, control.y * height, controlWidth.toFloat(),
            controlHeight.toFloat(), context.resources.displayMetrics.density)
    }

    fun fixedButtonBounds(root: View, canvas: View): List<CustomBounds> {
        val origin = IntArray(2)
        canvas.getLocationInWindow(origin)
        return listOf(R.id.openSerialButton, R.id.videoRotateButton, R.id.videoMirrorButton,
            R.id.settingsButton, R.id.switchArm).mapNotNull { id ->
            val view = root.findViewById<View>(id) ?: return@mapNotNull null
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0)
                return@mapNotNull null
            val position = IntArray(2)
            view.getLocationInWindow(position)
            val left = (position[0] - origin[0]).toFloat()
            val top = (position[1] - origin[1]).toFloat()
            CustomBounds(left, top, left + view.width, top + view.height)
        }
    }

    fun channelName(context: Context, channel: Int): String = when (channel) {
        1 -> context.getString(R.string.custom_roll)
        2 -> context.getString(R.string.custom_pitch)
        3 -> context.getString(R.string.custom_throttle)
        4 -> context.getString(R.string.custom_yaw)
        else -> context.getString(R.string.custom_channel_label, channel)
    }

    fun size(context: Context, control: CustomControl, canvasWidth: Int = 0,
        canvasHeight: Int = 0): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val scale = if (canvasWidth > 0 && canvasHeight > 0)
            minOf(1f, canvasWidth / (700f * density), canvasHeight / (260f * density))
                .coerceAtLeast(.6f) else 1f
        val (width, height) = when (control.type) {
            CustomControlType.JOYSTICK -> 150 to 150
            CustomControlType.HORIZONTAL -> 175 to 80
            CustomControlType.VERTICAL -> 80 to 175
            CustomControlType.BUTTON -> 92 to 50
        }
        return (width * density * scale * control.widthScale).roundToInt() to
            (height * density * scale * control.heightScale).roundToInt()
        }

    fun styleButton(button: Button, control: CustomControl, position: Int) {
        val color = when (position) {
            1 -> if (control.buttonPositions == 3) Color.rgb(230, 145, 25)
                else Color.rgb(0, 110, 230)
            2 -> Color.rgb(0, 110, 230)
            else -> Color.rgb(52, 57, 65)
        }
        button.background = GradientDrawable().apply {
            cornerRadius = button.resources.displayMetrics.density * 8f
            setColor(Color.argb(control.backgroundOpacity * 255 / 100,
                Color.red(color), Color.green(color), Color.blue(color)))
            setStroke((button.resources.displayMetrics.density *
                if (position == 0) 1f else 2f).roundToInt(),
                if (position == 0) Color.rgb(109, 121, 135) else Color.rgb(123, 216, 255))
        }
        val channel = control.first?.channel ?: 0
        button.text = button.context.getString(R.string.custom_button_state,
            if (channel in 1..4) channelName(button.context, channel) else "CH$channel",
            position + 1, control.buttonPositions)
        button.contentDescription = button.context.getString(R.string.custom_button_state,
            channelName(button.context, channel), position + 1, control.buttonPositions)
    }

    fun create(context: Context, control: CustomControl): View =
        if (control.type == CustomControlType.BUTTON) Button(context).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            styleButton(this, control, 0)
        } else Joystick(context).apply {
            setAxisMode(when (control.type) {
                CustomControlType.HORIZONTAL -> Joystick.AxisMode.HORIZONTAL
                CustomControlType.VERTICAL -> Joystick.AxisMode.VERTICAL
                else -> Joystick.AxisMode.BOTH
            })
            setCustomReturnBehavior(control.first?.autoReturn ?: true,
                control.second?.autoReturn ?: if (control.type == CustomControlType.VERTICAL)
                    control.first?.autoReturn ?: true else true)
            setCustomAppearance(control.type == CustomControlType.JOYSTICK &&
                control.widthScale != control.heightScale,
                control.backgroundOpacity)
            contentDescription = when (control.type) {
                CustomControlType.JOYSTICK -> context.getString(R.string.custom_two_channels_label,
                    channelName(context, control.first?.channel ?: 0),
                    channelName(context, control.second?.channel ?: 0))
                else -> channelName(context, control.first?.channel ?: 0)
            }
        }

    fun place(canvas: FrameLayout, view: View, control: CustomControl,
        onReady: () -> Unit = {}) {
        view.layoutParams = FrameLayout.LayoutParams(1, 1)
        canvas.addView(view)
        canvas.post {
            val (width, height) = size(canvas.context, control, canvas.width, canvas.height)
            view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int,
                    bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (v.width != width || v.height != height) return
                    v.removeOnLayoutChangeListener(this)
                    position(canvas, view, control.x, control.y)
                    onReady()
                }
            })
            view.layoutParams = FrameLayout.LayoutParams(width, height)
        }
    }

    fun position(canvas: FrameLayout, view: View, x: Float, y: Float) {
        if (canvas.width <= 0 || canvas.height <= 0) return
        view.x = (x * canvas.width - view.width / 2f).coerceIn(0f,
            (canvas.width - view.width).coerceAtLeast(0).toFloat())
        view.y = (y * canvas.height - view.height / 2f).coerceIn(0f,
            (canvas.height - view.height).coerceAtLeast(0).toFloat())
    }
}
