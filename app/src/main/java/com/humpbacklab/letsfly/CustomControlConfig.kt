package com.humpbacklab.letsfly

import android.content.SharedPreferences

internal enum class CustomControlType { JOYSTICK, HORIZONTAL, VERTICAL, BUTTON }

internal data class CustomAxis(
    val channel: Int,
    val low: Int = 0,
    val high: Int = 100,
    val autoReturn: Boolean = true,
    val returnValue: Int = 50
) {
    fun value(position: Float): Int =
        (low + (high - low) * ((position.coerceIn(-1f, 1f) + 1f) / 2f)).toInt()

    fun position(value: Int): Float =
        if (low == high) 0f else ((value - low).toFloat() / (high - low) * 2f - 1f)
            .coerceIn(-1f, 1f)
}

internal data class CustomControl(
    val id: Int,
    val type: CustomControlType,
    val x: Float,
    val y: Float,
    val first: CustomAxis? = null,
    val second: CustomAxis? = null,
    val offValue: Int = 0,
    val onValue: Int = 100,
    val buttonPositions: Int = 2,
    val middleValue: Int = 50,
    val widthScale: Float = 1f,
    val heightScale: Float = 1f,
    val backgroundOpacity: Int = 100,
    val resetX: Float = x,
    val resetY: Float = y
)

internal data class CustomControlConfig(
    val defaults: List<Int>,
    val controls: List<CustomControl>,
    val fullScreenCoordinates: Boolean = true
) {
    fun asFullScreen(canvasHeight: Int, fixedHeaderHeight: Int): CustomControlConfig {
        if (fullScreenCoordinates || canvasHeight <= fixedHeaderHeight) return this
        val inset = fixedHeaderHeight.toFloat() / canvasHeight
        fun convert(y: Float) = inset + y * (1f - inset)
        return copy(controls = controls.map {
            it.copy(y = convert(it.y), resetY = convert(it.resetY))
        }, fullScreenCoordinates = true)
    }

    fun error(): String? {
        if (defaults.size != 16 || defaults.any { it !in 0..100 } || defaults[4] != 0)
            return "Invalid channel defaults"
        val ids = mutableSetOf<Int>()
        val channels = mutableSetOf<Int>()
        for (control in controls) {
            if (control.id < 0 || !ids.add(control.id) || control.x !in 0f..1f ||
                control.y !in 0f..1f) return "Invalid control position or ID"
            if (control.type == CustomControlType.JOYSTICK && control.second == null ||
                control.type != CustomControlType.JOYSTICK && control.second != null ||
                control.first == null) return "Invalid control axes"
            if (control.offValue !in 0..100 || control.onValue !in 0..100 ||
                control.middleValue !in 0..100 || control.buttonPositions !in 2..3)
                return "Invalid button value"
            if (control.widthScale !in .5f..2f || control.heightScale !in .5f..2f ||
                control.backgroundOpacity !in 10..100 || control.resetX !in 0f..1f ||
                control.resetY !in 0f..1f) return "Invalid control appearance"
            for (axis in listOfNotNull(control.first, control.second)) {
                if (axis.channel !in 1..16 || axis.channel == 5 ||
                    !channels.add(axis.channel)) return "Channel is unavailable or already assigned"
                if (listOf(axis.low, axis.high, axis.returnValue).any { it !in 0..100 })
                    return "Channel value must be 0–100%"
            }
        }
        return null
    }

    fun encode(): String = buildString {
        append(if (fullScreenCoordinates) "3\n" else "2\n")
        append(defaults.joinToString(","))
        for (control in controls) {
            append('\n')
            append(listOf(control.id, control.type.name, control.x, control.y,
                control.offValue, control.onValue).joinToString(","))
            for (axis in listOf(control.first, control.second)) {
                append(',')
                append(if (axis == null) "-" else listOf(axis.channel, axis.low, axis.high,
                    if (axis.autoReturn) 1 else 0, axis.returnValue).joinToString(":"))
            }
            append(',')
            append(listOf(control.buttonPositions, control.middleValue, control.widthScale,
                control.heightScale, control.backgroundOpacity, control.resetX,
                control.resetY).joinToString(","))
        }
    }

    companion object {
        const val PREF_NAME = "rc_controller_prefs"
        const val PREF_KEY = "custom_control_config_v1"

        fun default(fullScreenCoordinates: Boolean = true): CustomControlConfig {
            val defaults = List(16) { index -> if (index == 2 || index >= 4) 0 else 50 }
            val joystickY = if (fullScreenCoordinates) .72f else .64f
            val buttonY = if (fullScreenCoordinates) .38f else .16f
            return CustomControlConfig(defaults, listOf(
                CustomControl(1, CustomControlType.JOYSTICK, .20f, joystickY,
                    CustomAxis(4), CustomAxis(3, autoReturn = false, returnValue = 0)),
                CustomControl(2, CustomControlType.JOYSTICK, .80f, joystickY,
                    CustomAxis(1), CustomAxis(2)),
                CustomControl(3, CustomControlType.BUTTON, .42f, buttonY, CustomAxis(6)),
                CustomControl(4, CustomControlType.BUTTON, .58f, buttonY, CustomAxis(7)),
                CustomControl(5, CustomControlType.BUTTON, .74f, buttonY, CustomAxis(8))
            ), fullScreenCoordinates)
        }

        fun decode(text: String): CustomControlConfig? {
            return try {
            val lines = text.split('\n')
            val version = lines.firstOrNull()
            if (version !in listOf("1", "2", "3")) return null
            val defaults = lines[1].split(',').map(String::toInt)
            val controls = lines.drop(2).map { line ->
                val parts = line.split(',')
                if (parts.size != if (version == "1") 8 else 15) error("Invalid control")
                fun axis(raw: String): CustomAxis? {
                    if (raw == "-") return null
                    val fields = raw.split(':').map(String::toInt)
                    if (fields.size != 5 || fields[3] !in 0..1) error("Invalid axis")
                    return CustomAxis(fields[0], fields[1], fields[2], fields[3] == 1, fields[4])
                }
                val x = parts[2].toFloat()
                val y = parts[3].toFloat()
                val template = default(false).controls.firstOrNull {
                    it.id == parts[0].toInt() && it.type.name == parts[1]
                }
                val extended = version != "1"
                CustomControl(parts[0].toInt(), CustomControlType.valueOf(parts[1]),
                    x, y, axis(parts[6]), axis(parts[7]), parts[4].toInt(), parts[5].toInt(),
                    if (extended) parts[8].toInt() else 2,
                    if (extended) parts[9].toInt() else 50,
                    if (extended) parts[10].toFloat() else 1f,
                    if (extended) parts[11].toFloat() else 1f,
                    if (extended) parts[12].toInt() else 100,
                    if (extended) parts[13].toFloat() else template?.x ?: x,
                    if (extended) parts[14].toFloat() else template?.y ?: y)
            }
            CustomControlConfig(defaults, controls, version == "3").takeIf { it.error() == null }
            } catch (_: Exception) { null }
        }

        fun load(preferences: SharedPreferences): CustomControlConfig =
            preferences.getString(PREF_KEY, null)?.let(::decode) ?: default()
    }
}

internal class CustomControlState(private val config: CustomControlConfig) {
    private val values = config.defaults.toMutableList()
    private val buttonPositions = mutableMapOf<Int, Int>()
    private val primaryReturnValues = config.defaults.take(4).toIntArray().apply {
        for (control in config.controls) {
            for (axis in listOfNotNull(control.first, control.second)) {
                if (axis.channel in 1..4) this[axis.channel - 1] =
                    if (control.type == CustomControlType.BUTTON) control.offValue else axis.returnValue
            }
        }
    }
    var armed = false
        private set

    init { reset(false) }

    fun reset(arm: Boolean) {
        buttonPositions.clear()
        for (index in values.indices) values[index] = config.defaults[index]
        setArmed(arm)
    }

    fun setArmed(arm: Boolean) {
        armed = arm
        // Keep auxiliary switch/axis state across arming, especially Wi-Fi CH7.
        // Primary controls start at their stop/neutral values even when
        // automatic return on release is disabled.
        for (index in 0..3) values[index] = primaryReturnValues[index]
        values[4] = if (arm) 100 else 0
        config.controls.filter { (it.first?.channel ?: 0) < 6 }
            .forEach { buttonPositions.remove(it.id) }
    }

    private fun canControl(channel: Int): Boolean = channel != 5 && (armed || channel >= 6)

    fun isControlEnabled(control: CustomControl): Boolean =
        listOfNotNull(control.first, control.second).any { canControl(it.channel) }

    fun move(id: Int, x: Float, y: Float) {
        val control = config.controls.firstOrNull { it.id == id } ?: return
        if (control.type == CustomControlType.BUTTON) return
        control.first?.takeIf { canControl(it.channel) }?.let { values[it.channel - 1] =
            it.value(if (control.type == CustomControlType.VERTICAL) y else x) }
        control.second?.takeIf { canControl(it.channel) }?.let { values[it.channel - 1] = it.value(y) }
    }

    fun release(id: Int) {
        val control = config.controls.firstOrNull { it.id == id } ?: return
        for (axis in listOfNotNull(control.first, control.second)) {
            if (canControl(axis.channel) && axis.autoReturn) values[axis.channel - 1] = axis.returnValue
        }
    }

    fun advanceButton(id: Int): Int {
        val control = config.controls.firstOrNull { it.id == id &&
            it.type == CustomControlType.BUTTON } ?: return 0
        if (!canControl(control.first!!.channel)) return buttonPosition(id)
        val position = ((buttonPositions[id] ?: 0) + 1) % control.buttonPositions
        buttonPositions[id] = position
        values[control.first!!.channel - 1] = when (position) {
            0 -> control.offValue
            1 -> if (control.buttonPositions == 3) control.middleValue else control.onValue
            else -> control.onValue
        }
        return position
    }

    fun buttonPosition(id: Int): Int = buttonPositions[id] ?: 0

    fun value(channel: Int): Int = values[channel - 1]
    fun snapshot(): IntArray = values.toIntArray()
}
