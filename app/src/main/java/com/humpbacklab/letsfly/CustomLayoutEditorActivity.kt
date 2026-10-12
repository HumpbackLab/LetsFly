package com.humpbacklab.letsfly

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class CustomLayoutEditorActivity : AppCompatActivity() {
    private lateinit var canvas: FrameLayout
    private lateinit var tools: LinearLayout
    private lateinit var status: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var menuButton: Button
    private lateinit var fixedControls: View
    private lateinit var config: CustomControlConfig
    private val channels = (1..16).filter { it != 5 }
    private val history = mutableListOf<CustomControlConfig>()
    private var historyIndex = 0
    private var selectedId: Int? = null
    private var lockSize = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var layoutIssues = emptyList<CustomLayoutIssue>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        supportActionBar?.hide()
        config = CustomControlConfig.load(getSharedPreferences(CustomControlConfig.PREF_NAME, MODE_PRIVATE))

        history.add(config)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(25, 25, 25))
        }
        canvas = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 12, 12)) }
        root.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        val preview = layoutInflater.inflate(R.layout.activity_main, root, false)
        fixedControls = preview.findViewById(R.id.topControlsContainer)
        (fixedControls.parent as ViewGroup).removeView(fixedControls)
        for (id in listOf(R.id.switchCH6, R.id.switchCH7, R.id.switchCH8)) {
            fixedControls.findViewById<View>(id).visibility = View.GONE
        }
        for (id in listOf(R.id.openSerialButton, R.id.videoRotateButton,
            R.id.videoMirrorButton, R.id.settingsButton, R.id.switchArm)) {
            fixedControls.findViewById<View>(id).apply {
                setOnClickListener(null)
                isEnabled = false
                isClickable = false
            }
        }
        root.addView(fixedControls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(48, 48, 48))
        }
        val fixedHeader = TextView(this).apply {
            text = getString(R.string.custom_editor_fixed_header)
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        toolbar.addView(fixedHeader, LinearLayout.LayoutParams(0, -1, 1f))
        fun action(label: Int, click: () -> Unit) {
            toolbar.addView(Button(this).apply {
                setText(label)
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(dp(62), dp(48)))
        }
        action(R.string.custom_add_control) { chooseControlType() }
        action(R.string.custom_channel_defaults) { editDefaults() }
        action(R.string.custom_undo) { navigateHistory(-1) }
        action(R.string.custom_redo) { navigateHistory(1) }
        action(R.string.custom_save) { save() }
        action(R.string.custom_cancel) { finish() }
        action(R.string.custom_hide_tools) { collapseTools() }
        root.addView(toolbar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
            setBackgroundColor(Color.rgb(45, 45, 45))
            visibility = View.GONE
        }
        root.addView(tools, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(48)
        })
        menuButton = Button(this).apply {
            setText(R.string.custom_editor_menu)
            visibility = View.GONE
            setOnClickListener {
                toolbar.visibility = View.VISIBLE
                visibility = View.GONE
                showTools()
            }
        }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(64), dp(48),
            Gravity.BOTTOM or Gravity.END))
        var menuStartX = 0f
        var menuStartY = 0f
        var menuPointerX = 0f
        var menuPointerY = 0f
        var menuMoved = false
        menuButton.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    menuStartX = target.x
                    menuStartY = target.y
                    menuPointerX = event.rawX
                    menuPointerY = event.rawY
                    menuMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - menuPointerX
                    val dy = event.rawY - menuPointerY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) menuMoved = true
                    if (menuMoved) {
                        target.x = (menuStartX + dx).coerceIn(0f,
                            (root.width - target.width).coerceAtLeast(0).toFloat())
                        target.y = (menuStartY + dy).coerceIn(0f,
                            (root.height - target.height).coerceAtLeast(0).toFloat())
                    }
                }
                MotionEvent.ACTION_UP -> if (!menuMoved) target.performClick()
            }
            true
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setBackgroundColor(Color.rgb(120, 30, 30))
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2,
            Gravity.TOP or Gravity.START).apply { topMargin = dp(96) })
        setContentView(root)
        canvas.foreground = object : Drawable() {
            private val paint = Paint().apply {
                color = Color.rgb(0, 220, 200)
                strokeWidth = dp(1).toFloat()
                style = Paint.Style.STROKE
            }
            override fun draw(target: Canvas) {
                val conflicts = layoutIssues.flatMap { it.ids }.toSet()
                for (control in config.controls) {
                    if (control.id !in conflicts && control.id != selectedId) continue
                    val outline = CustomControlViews.area(this@CustomLayoutEditorActivity,
                        control, canvas.width, canvas.height).outline
                    val path = Path().apply {
                        moveTo(outline.first().x, outline.first().y)
                        for (point in outline.drop(1)) lineTo(point.x, point.y)
                        close()
                    }
                    paint.color = if (control.id in conflicts) Color.RED else Color.CYAN
                    paint.strokeWidth = dp(2).toFloat()
                    target.drawPath(path, paint)
                }
                val selected = config.controls.firstOrNull { it.id == selectedId } ?: return
                paint.color = Color.rgb(0, 220, 200)
                paint.strokeWidth = dp(1).toFloat()
                val limit = dp(6).toFloat()
                val otherX = config.controls.filter { it.id != selected.id }.map { it.x } + .5f
                val otherY = config.controls.filter { it.id != selected.id }.map { it.y } + .5f
                if (otherX.any { abs(it - selected.x) * canvas.width < limit }) {
                    val x = selected.x * canvas.width
                    target.drawLine(x, 0f, x, canvas.height.toFloat(), paint)
                }
                if (otherY.any { abs(it - selected.y) * canvas.height < limit }) {
                    val y = selected.y * canvas.height
                    target.drawLine(0f, y, canvas.width.toFloat(), y, paint)
                }
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) {
                paint.colorFilter = filter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        CustomControlViews.whenCanvasReady(canvas) {
            config = config.asFullScreen(canvas.height, fixedControls.height)
            config = config.copy(controls = config.controls.map(::fitPosition))
            history[0] = config
            render()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        repeatHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun collapseTools() {
        repeatHandler.removeCallbacksAndMessages(null)
        tools.visibility = View.GONE
        toolbar.visibility = View.GONE
        menuButton.visibility = View.VISIBLE
    }

    private fun showConfigError(error: String) {
        Toast.makeText(this, if (error.startsWith("Channel is unavailable"))
            R.string.custom_duplicate_channel else R.string.custom_invalid_config,
            Toast.LENGTH_LONG).show()
    }

    private fun render() {
        canvas.removeAllViews()
        for (control in config.controls) {
            val view = CustomControlViews.create(this, control)
            CustomControlViews.place(canvas, view, control)
            view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
                config.controls.firstOrNull { it.id == control.id }?.let {
                    CustomControlViews.position(canvas, target, it.x, it.y)
                }
            }
            view.setOnClickListener { selectControl(control.id) }
            var startX = 0f
            var startY = 0f
            var pointerX = 0f
            var pointerY = 0f
            var moved = false
            var beforeDrag = config
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        repeatHandler.removeCallbacksAndMessages(null)
                        tools.visibility = View.GONE
                        toolbar.visibility = View.GONE
                        menuButton.visibility = View.GONE
                        beforeDrag = config
                        selectedId = control.id
                        startX = event.rawX - target.x
                        startY = event.rawY - target.y
                        pointerX = event.rawX
                        pointerY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val x = (event.rawX - startX).coerceIn(0f,
                            (canvas.width - target.width).coerceAtLeast(0).toFloat())
                        val y = (event.rawY - startY).coerceIn(0f,
                            (canvas.height - target.height).coerceAtLeast(0).toFloat())
                        if (abs(event.rawX - pointerX) > dp(3) ||
                            abs(event.rawY - pointerY) > dp(3)) moved = true
                        target.x = x
                        target.y = y
                        config = config.copy(controls = config.controls.map {
                            if (it.id == control.id) it.copy(
                                x = (x + target.width / 2f) / canvas.width,
                                y = (y + target.height / 2f) / canvas.height) else it
                        })
                        updateWarnings()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            commitHistory()
                            selectControl(control.id)
                        } else {
                            config = beforeDrag
                            beforeDrag.controls.first { it.id == control.id }.let {
                                CustomControlViews.position(canvas, target, it.x, it.y)
                            }
                            target.performClick()
                        }
                        toolbar.visibility = View.VISIBLE
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        config = beforeDrag
                        render()
                        collapseTools()
                        true
                    }
                    else -> true
                }
            }
        }
        updateWarnings()
    }

    private fun change(candidate: CustomControlConfig) {
        val fitted = candidate.copy(controls = candidate.controls.map(::fitPosition))
        if (fitted == config) {
            commitHistory()
            return
        }
        config = fitted
        commitHistory()
        render()
        showTools()
    }

    private fun commitHistory() {
        if (history[historyIndex] == config) return
        history.subList(historyIndex + 1, history.size).clear()
        history.add(config)
        if (history.size > 30) history.removeAt(0)
        historyIndex = history.lastIndex
    }

    private fun navigateHistory(direction: Int) {
        val next = historyIndex + direction
        if (next !in history.indices) return
        historyIndex = next
        config = history[next]
        if (selectedId != null && config.controls.none { it.id == selectedId }) selectedId = null
        render()
        showTools()
    }

    private fun selectControl(id: Int) {
        selectedId = id
        showTools()
        updateWarnings()
    }

    private fun selectedControl() = config.controls.firstOrNull { it.id == selectedId }

    private fun fitPosition(control: CustomControl): CustomControl {
        if (canvas.width <= 0 || canvas.height <= 0) return control
        val (width, height) = CustomControlViews.size(this, control, canvas.width, canvas.height)
        val halfX = (width / 2f / canvas.width).coerceAtMost(.5f)
        val halfY = (height / 2f / canvas.height).coerceAtMost(.5f)
        return control.copy(x = control.x.coerceIn(halfX, 1f - halfX),
            y = control.y.coerceIn(halfY, 1f - halfY))
    }

    private fun liveControl(candidate: CustomControl) {
        val updated = fitPosition(candidate)
        config = config.copy(controls = config.controls.map {
            if (it.id == updated.id) updated else it
        })
        val index = config.controls.indexOfFirst { it.id == updated.id }
        val view = canvas.getChildAt(index) ?: return
        val (width, height) = CustomControlViews.size(this, updated, canvas.width, canvas.height)
        view.layoutParams = FrameLayout.LayoutParams(width, height)
        view.post { CustomControlViews.position(canvas, view, updated.x, updated.y) }
        if (view is Button) CustomControlViews.styleButton(view, updated, 0)
        else if (view is Joystick) view.setCustomAppearance(
            updated.type == CustomControlType.JOYSTICK &&
                updated.widthScale != updated.heightScale, updated.backgroundOpacity)
        updateWarnings()
    }

    private fun showTools() {
        val control = selectedControl()
        tools.removeAllViews()
        if (control == null) { tools.visibility = View.GONE; return }
        tools.visibility = View.VISIBLE
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun small(label: Int, click: () -> Unit) {
            top.addView(Button(this).apply {
                setText(label)
                textSize = 11f
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        small(R.string.custom_control_settings) { editControl(control.id) }
        small(R.string.custom_delete) {
            selectedId = null
            change(config.copy(controls = config.controls.filterNot { it.id == control.id }))
        }
        small(R.string.custom_reset_appearance) {
            change(config.copy(controls = config.controls.map {
                it.copy(x = it.resetX, y = it.resetY,
                    widthScale = 1f, heightScale = 1f, backgroundOpacity = 100)
            }))
        }
        small(R.string.custom_hide_tools) { collapseTools() }
        val lock = CheckBox(this).apply {
            setText(R.string.custom_lock_aspect)
            textSize = 11f
            setTextColor(Color.WHITE)
            isChecked = lockSize
            setOnCheckedChangeListener { _, checked -> lockSize = checked }
        }
        top.addView(lock)
        tools.addView(top)

        val sliders = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun slider(labelId: Int, initial: Int, max: Int, onValue: (Int) -> Unit) {
            val title = TextView(this).apply {
                text = getString(labelId)
                textSize = 11f
                setTextColor(Color.WHITE)
            }
            sliders.addView(title)
            sliders.addView(SeekBar(this).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) onValue(progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) { }
                    override fun onStopTrackingTouch(bar: SeekBar) {
                        commitHistory()
                        showTools()
                    }
                })
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        slider(R.string.custom_width, (control.widthScale * 100).toInt() - 50, 150) { value ->
            resizeSelected(true, (value + 50) / 100f)
        }
        slider(R.string.custom_height, (control.heightScale * 100).toInt() - 50, 150) { value ->
            resizeSelected(false, (value + 50) / 100f)
        }
        slider(R.string.custom_opacity, control.backgroundOpacity - 10, 90) { value ->
            selectedControl()?.let { liveControl(it.copy(backgroundOpacity = value + 10)) }
        }
        tools.addView(sliders)

        val arrows = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun arrow(symbol: String, dx: Int, dy: Int) {
            val button = Button(this).apply { text = symbol; textSize = 14f }
            var repeated = false
            button.setOnClickListener {
                nudge(dx, dy)
                commitHistory()
            }
            val runnable = object : Runnable {
                override fun run() {
                    repeated = true
                    nudge(dx, dy)
                    repeatHandler.postDelayed(this, 70)
                }
            }
            button.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        repeated = false
                        target.isPressed = true
                        repeatHandler.postDelayed(runnable, 400)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        target.isPressed = false
                        repeatHandler.removeCallbacks(runnable)
                        if (event.actionMasked == MotionEvent.ACTION_UP && !repeated)
                            target.performClick()
                        else commitHistory()
                        true
                    }
                    else -> true
                }
            }
            arrows.addView(button, LinearLayout.LayoutParams(dp(54), dp(36)))
        }
        arrow("←", -1, 0); arrow("↑", 0, -1)
        arrow("↓", 0, 1); arrow("→", 1, 0)
        tools.addView(arrows)
    }

    private fun resizeSelected(horizontal: Boolean, next: Float) {
        val control = selectedControl() ?: return
        val updated = if (lockSize) {
            val factor = (next / if (horizontal) control.widthScale else control.heightScale)
                .coerceIn(maxOf(.5f / control.widthScale, .5f / control.heightScale),
                    minOf(2f / control.widthScale, 2f / control.heightScale))
            control.copy(widthScale = control.widthScale * factor,
                heightScale = control.heightScale * factor)
        } else if (horizontal) control.copy(widthScale = next)
        else control.copy(heightScale = next)
        liveControl(updated)
    }

    private fun nudge(dx: Int, dy: Int) {
        val control = selectedControl() ?: return
        if (canvas.width <= 0 || canvas.height <= 0) return
        val (width, height) = CustomControlViews.size(this, control, canvas.width, canvas.height)
        val halfX = (width / 2f / canvas.width).coerceAtMost(.5f)
        val halfY = (height / 2f / canvas.height).coerceAtMost(.5f)
        liveControl(control.copy(x = (control.x + dx * dp(1).toFloat() / canvas.width)
            .coerceIn(halfX, 1f - halfX),
            y = (control.y + dy * dp(1).toFloat() / canvas.height)
                .coerceIn(halfY, 1f - halfY)))
    }

    private fun updateWarnings() {
        if (canvas.width <= 0 || canvas.height <= 0) return
        layoutIssues = CustomControlGeometry.issues(config.controls.map {
            CustomControlViews.area(this, it, canvas.width, canvas.height)
        }, canvas.width, canvas.height,
            CustomControlViews.fixedButtonBounds(fixedControls, canvas),
            resources.displayMetrics.density * .5f)
        status.visibility = if (layoutIssues.isEmpty()) View.GONE else View.VISIBLE
        status.text = layoutIssues.map { it.kind }.distinct().joinToString("\n") { kind ->
            getString(when (kind) {
                CustomLayoutIssueKind.OUTSIDE_SCREEN -> R.string.custom_outside_screen
                CustomLayoutIssueKind.FIXED_OVERLAP -> R.string.custom_fixed_overlap
                CustomLayoutIssueKind.CONTROL_OVERLAP -> R.string.custom_overlap
            })
        }
        canvas.invalidate()
    }

    private fun chooseControlType() {
        val names = intArrayOf(R.string.custom_joystick, R.string.custom_horizontal,
            R.string.custom_vertical, R.string.custom_toggle_button)
        val icons = intArrayOf(R.drawable.ic_custom_joystick,
            R.drawable.ic_custom_horizontal, R.drawable.ic_custom_vertical,
            R.drawable.ic_custom_button)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.custom_add_control)
            .setView(grid).create()
        for (rowIndex in 0..1) {
            val row = LinearLayout(this)
            for (column in 0..1) {
                val selected = rowIndex * 2 + column
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    contentDescription = getString(names[selected])
                    isClickable = true
                    setOnClickListener {
                val type = CustomControlType.values()[selected]
                val used = config.controls.flatMap { listOfNotNull(it.first?.channel,
                    it.second?.channel) }.toSet()
                val free = channels.filter { it !in used }
                if (free.size < if (type == CustomControlType.JOYSTICK) 2 else 1) {
                    Toast.makeText(this@CustomLayoutEditorActivity,
                        R.string.custom_no_channels, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val id = (config.controls.maxOfOrNull { it.id } ?: 0) + 1
                val control = CustomControl(id, type, .5f, .5f, CustomAxis(free[0]),
                    if (type == CustomControlType.JOYSTICK) CustomAxis(free[1]) else null)
                config = config.copy(controls = config.controls + control)
                render()
                selectControl(id)
                dialog.dismiss()
                editControl(id, removeOnCancel = true)
                    }
                }
                card.addView(ImageView(this).apply {
                    setImageResource(icons[selected])
                    contentDescription = getString(names[selected])
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                card.addView(TextView(this).apply {
                    setText(names[selected])
                    textSize = 12f
                    gravity = Gravity.CENTER
                })
                row.addView(card, LinearLayout.LayoutParams(0, dp(86), 1f))
            }
            grid.addView(row)
        }
        dialog.show()
    }

    private fun number(value: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        selectAll()
    }

    private fun channelSpinner(channel: Int): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@CustomLayoutEditorActivity,
            android.R.layout.simple_spinner_dropdown_item,
            channels.map { CustomControlViews.channelName(this@CustomLayoutEditorActivity, it) })
        setSelection(channels.indexOf(channel).coerceAtLeast(0))
    }

    private fun label(container: LinearLayout, text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.DKGRAY)
            textSize = 14f
        })
    }

    private data class AxisFields(val channel: Spinner, val low: EditText,
        val high: EditText, val auto: CheckBox, val returnValue: EditText)

    private fun axisFields(container: LinearLayout, name: String, axis: CustomAxis): AxisFields {
        label(container, name)
        val channel = channelSpinner(axis.channel)
        container.addView(channel)
        label(container, getString(R.string.custom_output_range))
        val low = number(axis.low)
        val high = number(axis.high)
        val row = LinearLayout(this)
        row.addView(low, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(high, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(row)
        val auto = CheckBox(this).apply {
            setText(R.string.custom_auto_return)
            isChecked = axis.autoReturn
        }
        container.addView(auto)
        label(container, getString(R.string.custom_return_value))
        val returnValue = number(axis.returnValue)
        container.addView(returnValue)
        return AxisFields(channel, low, high, auto, returnValue)
    }

    private fun editControl(id: Int, removeOnCancel: Boolean = false) {
        val control = config.controls.firstOrNull { it.id == id } ?: return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val first: AxisFields?
        val second: AxisFields?
        var off: EditText? = null
        var on: EditText? = null
        var middle: EditText? = null
        var positions: Spinner? = null
        var buttonChannel: Spinner? = null
        if (control.type == CustomControlType.BUTTON) {
            label(content, getString(R.string.custom_channel))
            val channel = channelSpinner(control.first!!.channel)
            buttonChannel = channel
            content.addView(channel)
            label(content, getString(R.string.custom_button_positions))
            positions = Spinner(this).apply {
                adapter = ArrayAdapter(this@CustomLayoutEditorActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf(getString(R.string.custom_two_positions),
                        getString(R.string.custom_three_positions)))
                setSelection(control.buttonPositions - 2)
            }
            content.addView(positions)
            label(content, getString(R.string.custom_button_values))
            off = number(control.offValue)
            on = number(control.onValue)
            label(content, getString(R.string.custom_button_off))
            content.addView(off)
            val middleLabel = TextView(this).apply {
                setText(R.string.custom_button_middle)
                setTextColor(Color.DKGRAY)
                textSize = 14f
            }
            content.addView(middleLabel)
            middle = number(control.middleValue)
            content.addView(middle)
            label(content, getString(R.string.custom_button_on))
            content.addView(on)
            fun updateMiddle() {
                val visible = if (positions.selectedItemPosition == 1) View.VISIBLE else View.GONE
                middleLabel.visibility = visible
                middle.visibility = visible
            }
            positions.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                    position: Int, id: Long) { updateMiddle() }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
            }
            updateMiddle()
            first = null
            second = null
        } else {
            first = axisFields(content,
                if (control.type == CustomControlType.VERTICAL) "Y" else "X", control.first!!)
            second = control.second?.let { axisFields(content, "Y", it) }
        }
        fun discardDraft() {
            if (!removeOnCancel) return
            selectedId = null
            change(config.copy(controls = config.controls.filterNot { it.id == id }))
            showTools()
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.custom_control_settings)
            .setView(scroll).setPositiveButton(R.string.custom_confirm, null)
            .setNegativeButton(R.string.custom_cancel) { _, _ -> discardDraft() }.create()
        dialog.setOnCancelListener { discardDraft() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    fun parse(field: EditText) = field.text.toString().toInt()
                    fun read(fields: AxisFields) = CustomAxis(
                        channels[fields.channel.selectedItemPosition], parse(fields.low),
                        parse(fields.high), fields.auto.isChecked, parse(fields.returnValue))
                    val updated = if (control.type == CustomControlType.BUTTON) {
                        control.copy(first = CustomAxis(channels[buttonChannel!!.selectedItemPosition]),
                            offValue = parse(off!!), onValue = parse(on!!),
                            middleValue = parse(middle!!),
                            buttonPositions = positions!!.selectedItemPosition + 2)
                    } else control.copy(first = read(first!!), second = second?.let(::read))
                    val candidate = config.copy(controls = config.controls.map {
                        if (it.id == id) updated else it
                    })
                    val error = candidate.error()
                    if (error != null) {
                        showConfigError(error)
                    } else {
                        change(candidate)
                        dialog.dismiss()
                    }
                } catch (_: NumberFormatException) {
                    Toast.makeText(this, R.string.custom_invalid_value, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun editDefaults() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val fields = channels.associateWith { channel ->
            label(content, CustomControlViews.channelName(this, channel))
            number(config.defaults[channel - 1]).also(content::addView)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.custom_channel_defaults)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.custom_confirm, null)
            .setNegativeButton(R.string.custom_cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val defaults = config.defaults.toMutableList()
                    for ((channel, field) in fields) defaults[channel - 1] = field.text.toString().toInt()
                    val candidate = config.copy(defaults = defaults)
                    val error = candidate.error()
                    if (error != null) showConfigError(error)
                    else { change(candidate); dialog.dismiss() }
                } catch (_: NumberFormatException) {
                    Toast.makeText(this, R.string.custom_invalid_value, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun save() {
        val error = config.error()
        if (error != null) {
            showConfigError(error)
            return
        }
        if (canvas.width <= 0 || canvas.height <= 0) return
        updateWarnings()
        if (layoutIssues.isNotEmpty()) {
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(CustomControlConfig.PREF_NAME, MODE_PRIVATE).edit()
            .putString(CustomControlConfig.PREF_KEY, config.encode()).apply()
        Toast.makeText(this, R.string.custom_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
