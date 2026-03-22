package com.humpbacklab.letsfly

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.get
import com.hehongdan.ch34xuartdriver.CH34xUARTDriver
import java.lang.Thread.sleep
import kotlin.math.asin
import kotlin.math.atan2

class MainActivity : AppCompatActivity() {
    companion object {
        // Constants for sensor to degree conversion
        const val RADIAN_TO_DEGREE_FACTOR = 180.0f / Math.PI.toFloat()

        // Constants for duty cycle conversion
        const val DUTY_CYCLE_MULTIPLIER = 1639
        const val DUTY_CYCLE_OFFSET = 172

        // Sensor delay constant (using GAME_ROTATION_VECTOR, we want a balance between responsiveness and power)
        const val SENSOR_DELAY_NORMAL = 16667  // ~60Hz (in microseconds)
        const val SENSOR_DELAY_FASTEST = 0     // For high-frequency updates of internal logic

        // CRSF packet constants
        const val CRSF_PACKET_TYPE_RC_CHANNELS = 0x16
        const val CRSF_PACKET_ADDRESS = 0xC8
        const val CRSF_PACKET_LENGTH = 0x18
    }
    class MyListener(val callback: (listen: MyListener) -> Unit) : SensorEventListener {
        public var roll: Float = 0.0f
        public var pitch: Float = 0.0f
        public var yaw: Float = 0.0f
        var Q: FloatArray = FloatArray(4)
        var R: FloatArray = FloatArray(9)

        override fun onSensorChanged(event: SensorEvent?) {
            var test: FloatArray = FloatArray(3)
            if (event != null) {
                SensorManager.getQuaternionFromVector(Q, event.values)
                SensorManager.getRotationMatrixFromVector(R, event.values)
                SensorManager.getOrientation(R, test)
                //Q2Angle(Q)
                test = test.map { it * RADIAN_TO_DEGREE_FACTOR }.toFloatArray()
                yaw = test[0]
                roll = test[1]
                pitch = test[2]
                callback(this)
                //println("roll $roll   pitch:$pitch")
            }
        }

        fun Q2Angle(q: FloatArray) {
            pitch = asin(-1 * q[1] * q[3] + 2 * q[0] * q[2]) * 57.3f;           //pitch
            roll = atan2(2 * q[2] * q[3] + 2 * q[0] * q[1], -2 * q[1] * q[1] - 2 * q[2] * q[2] + 1) * 57.3f;   //roll
            yaw =
                atan2(2 * q[1] * q[2] + 2 * q[0] * q[3], -2 * q[2] * q[2] - 2 * q[3] * q[3] + 1) * 57.3f;         //yaw

        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    private lateinit var serialDriver: CH34xUARTDriver
    private var serialOpened = false

    private lateinit var bytes: ByteArray
    private val crsfData: CRSFData = CRSFData()
    private var useGyroControl = false
    private var lastUpdateTime = 0L
    private val uiUpdateInterval = 50L // Limit UI updates to every 50ms (20Hz)

    private lateinit var sharedPreferences: android.content.SharedPreferences

    private var thrust:Float=0f

    private lateinit var leftJoyStick: Joystick
    private lateinit var rightJoyStick: Joystick

    private lateinit var armSwitch: Switch

    private var yaw_offset:Float=0f

    // Generic three-position switch enum and class
    enum class SwitchPosition {
        LOW, MIDDLE, HIGH
    }

    data class ThreePositionSwitch(
        val button: Button,
        var position: SwitchPosition,
        val dataArrayIndex: Int  // Index in crsfData.data_array
    )

    // Instance variables for three-position switches
    private lateinit var ch6SwitchControl: ThreePositionSwitch
    private lateinit var ch7SwitchControl: ThreePositionSwitch
    private lateinit var ch8SwitchControl: ThreePositionSwitch

    fun duty2CRSF(duty:Float)=(duty * DUTY_CYCLE_MULTIPLIER + DUTY_CYCLE_OFFSET).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize shared preferences
        sharedPreferences = getSharedPreferences("rc_controller_prefs", Context.MODE_PRIVATE)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        sensorManager.registerListener(MyListener(this::sensorCallBack), sensor, SENSOR_DELAY_FASTEST);
        serialDriver =
            CH34xUARTDriver(getSystemService(USB_SERVICE) as UsbManager, this, "cn.wch.wchusbdriver.USB_PERMISSION")

        //bytes=getTestByteArray("C8 18 16 E0 03 1F 2B C0 F7 8B 5F FC E2 17 E5 2B 5F F9 CA 07 00 00 44 3C E2 B8")
        leftJoyStick = findViewById<Joystick>(R.id.leftJoystick)
        rightJoyStick = findViewById<Joystick>(R.id.rightJoystick)
        armSwitch = findViewById(R.id.switchArm)

        // Initialize three-position switches with a helper function
        ch6SwitchControl = createThreePositionSwitch(R.id.switchCH6, SwitchPosition.LOW, 5)  // CH6 corresponds to index 5
        ch7SwitchControl = createThreePositionSwitch(R.id.switchCH7, SwitchPosition.LOW, 6)  // CH7 corresponds to index 6
        ch8SwitchControl = createThreePositionSwitch(R.id.switchCH8, SwitchPosition.LOW, 7)  // CH8 corresponds to index 7

        val loadLeftJoyStick = { x:Float, y:Float -> 
            val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false)
            if(!isGyroEnabled){
                // Apply CH4 (index 3) range adjustment
                val ch4RangePercentage = sharedPreferences.getInt("ch4_range", 100) / 100f
                val adjustedX = x * ch4RangePercentage
                crsfData.data_array[3] = duty2CRSF(adjustedX / 2f + 0.5f)

                // Apply CH3 (index 2) range adjustment
                val ch3RangePercentage = sharedPreferences.getInt("ch3_range", 100) / 100f
                crsfData.data_array[2] = duty2CRSF((y / 2f + 0.5f) * ch3RangePercentage) 
                // special for throttle, range adjustment is applied after centering to ensure it scales correctly
                // from the minimum value  
            }
        }

        val loadRightJoyStick = { x:Float, y:Float ->
            val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false)
            if (!isGyroEnabled){
                // Apply CH1 (index 0) range adjustment
                val ch1RangePercentage = sharedPreferences.getInt("ch1_range", 100) / 100f
                val adjustedX = x * ch1RangePercentage
                crsfData.data_array[0] = duty2CRSF(adjustedX / 2 + 0.5f)

                // Apply CH2 (index 1) range adjustment
                val ch2RangePercentage = sharedPreferences.getInt("ch2_range", 100) / 100f
                val adjustedY = y * ch2RangePercentage
                crsfData.data_array[1] = duty2CRSF(adjustedY / 2 + 0.5f)
            }

        }
        // ch1 roll
        // ch2 pitch
        // ch3 throttle
        // ch4 yaw
        leftJoyStick.setOnJoystickMoveListener(
            object : OnJoystickMoveListener {
                override fun onJoystickValueChanged(x: Float, y: Float) {
                    loadLeftJoyStick(x,y)
                }
            }, 10
        )

        rightJoyStick.setOnJoystickMoveListener(
            object : OnJoystickMoveListener {
                override fun onJoystickValueChanged(x: Float, y: Float) {
                    loadRightJoyStick(x,y)
                }
            }, 10
        )

        // Add listeners to switches for visual feedback
        armSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchVisualFeedback(armSwitch, isChecked)
        }

        // Initialize visual feedback for switches
        updateSwitchVisualFeedback(armSwitch, armSwitch.isChecked)

        // Initialize three-position switches
        ch6SwitchControl = createThreePositionSwitch(R.id.switchCH6, SwitchPosition.LOW, 5)  // CH6 corresponds to index 5
        ch7SwitchControl = createThreePositionSwitch(R.id.switchCH7, SwitchPosition.LOW, 6)  // CH7 corresponds to index 6
        ch8SwitchControl = createThreePositionSwitch(R.id.switchCH8, SwitchPosition.LOW, 7)  // CH8 corresponds to index 7

        for (i in 1..16) {
            crsfData.data_array[i - 1] = i
        }
        bytes = crsfData.pack().toByteArray()
        for (i in bytes) {
            print(String.format("%02X,", i))
        }
        println("")

        // Initialize joystick states based on saved preferences
        initializeJoystickStates()

        // Set initial joystick positions after view layout is complete
        leftJoyStick.post {
            leftJoyStick.setXY(0f, -1.0f)
            rightJoyStick.setXY(0f, 0f)
            loadLeftJoyStick(leftJoyStick.getOutX(), leftJoyStick.getOutY())
            loadRightJoyStick(rightJoyStick.getOutX(), rightJoyStick.getOutY())
        }

        // Set initial orientation based on settings
        setInitialOrientation()
    }

    private fun setInitialOrientation() {
        // Check shared preferences for the orientation mode
        val orientationMode = sharedPreferences.getString("orientation_mode", "single_hand")
        when (orientationMode) {
            "dual_hand" -> {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            else -> { // Default to single hand (portrait)
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update orientation when activity resumes (e.g., when returning from settings)
        setInitialOrientation()
    }

    private fun initializeJoystickStates() {
        val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false)
        if (isGyroEnabled) {
            leftJoyStick.enable = false
            rightJoyStick.enable = false
        } else {
            leftJoyStick.enable = true
            rightJoyStick.enable = true
        }
    }

    // Helper function to create a three-position switch
    private fun createThreePositionSwitch(buttonId: Int, initialPosition: SwitchPosition, dataArrayIndex: Int): ThreePositionSwitch {
        val button = findViewById<Button>(buttonId)
        val switch = ThreePositionSwitch(button, initialPosition, dataArrayIndex)

        button.setOnClickListener {
            // Cycle through positions: LOW -> MIDDLE -> HIGH -> LOW
            switch.position = when (switch.position) {
                SwitchPosition.LOW -> SwitchPosition.MIDDLE
                SwitchPosition.MIDDLE -> SwitchPosition.HIGH
                SwitchPosition.HIGH -> SwitchPosition.LOW
            }
            button.isEnabled = true  // Always enable the switch when clicked
            updateThreePositionSwitchUI(switch)
        }

        updateThreePositionSwitchUI(switch)  // Initialize UI
        return switch
    }

    /** Called when the user taps the Toggle Layout button */
    fun toggleLayout(view: View) {
        // Get current orientation to determine what to switch to
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            // Currently in portrait, switch to landscape (dual hand mode)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

            // Save the new orientation mode to shared preferences
            with(sharedPreferences.edit()) {
                putString("orientation_mode", "dual_hand")
                apply()
            }
        } else {
            // Currently in landscape, switch to portrait (single hand mode)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            // Save the new orientation mode to shared preferences
            with(sharedPreferences.edit()) {
                putString("orientation_mode", "single_hand")
                apply()
            }
        }
    }

    private fun updateSwitchVisualFeedback(switch: Switch, isChecked: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For newer Android versions, use theme attributes for better consistency
            if (isChecked) {
                switch.thumbTintList = ContextCompat.getColorStateList(this, R.color.switch_arm_on)
                switch.trackTintList = ContextCompat.getColorStateList(this, R.color.switch_background_on)
            } else {
                switch.thumbTintList = ContextCompat.getColorStateList(this, R.color.switch_arm_off)
                switch.trackTintList = ContextCompat.getColorStateList(this, R.color.switch_background_off)
            }
        } else {
            // For older Android versions, use deprecated but functional setColorFilter
            if (isChecked) {
                @Suppress("DEPRECATION")
                switch.thumbDrawable.setColorFilter(
                    ContextCompat.getColor(this, R.color.switch_arm_on),
                    PorterDuff.Mode.MULTIPLY
                )
                @Suppress("DEPRECATION")
                switch.trackDrawable.setColorFilter(
                    ContextCompat.getColor(this, R.color.switch_background_on),
                    PorterDuff.Mode.MULTIPLY
                )
            } else {
                @Suppress("DEPRECATION")
                switch.thumbDrawable.setColorFilter(
                    ContextCompat.getColor(this, R.color.switch_arm_off),
                    PorterDuff.Mode.MULTIPLY
                )
                @Suppress("DEPRECATION")
                switch.trackDrawable.setColorFilter(
                    ContextCompat.getColor(this, R.color.switch_background_off),
                    PorterDuff.Mode.MULTIPLY
                )
            }
        }
    }

    private fun updateThreePositionSwitchUI(switch: ThreePositionSwitch) {
        // Set visual feedback color based on position only, no text label
        when (switch.position) {
            SwitchPosition.LOW -> switch.button.setBackgroundColor(ContextCompat.getColor(this, R.color.channel_low))
            SwitchPosition.MIDDLE -> switch.button.setBackgroundColor(ContextCompat.getColor(this, R.color.channel_middle))
            SwitchPosition.HIGH -> switch.button.setBackgroundColor(ContextCompat.getColor(this, R.color.channel_high))
        }
    }

    private fun debugInfo(str: String) =
        AlertDialog.Builder(this).setMessage(str).setTitle(getString(R.string.app_name)).create().show()

    fun getTestByteArray(str: String): ByteArray {
        val arr = str.split(" ")
        var result = ByteArray(26)
        for ((cnt, i) in arr.withIndex()) {
            result[cnt] = i.toUByte(16).toByte()
        }
        return result
    }

    private fun openUartDevice(): Boolean {
        val ret = serialDriver.resumeUsbList()
        if (ret == -1) {
            debugInfo(getString(R.string.msg_no_uart_device))
            return false
        }

        if (!serialDriver.uartInit()) {
            debugInfo(getString(R.string.msg_fail_open_uart))
            return false
        }
        val config_ret = serialDriver.setConfig(115200, 8, 1, 0, 0)
        if(config_ret){
            serialOpened = true
            bytes = crsfData.pack().toByteArray()
            for (i in 0..20) {
                uartWrite(bytes, 26)
                sleep(5)
            }
            bytes = crsfData.packCmd(0x01u,0x00u).toByteArray()
            for(i in 0..10){
                uartWrite(bytes, 8)
                sleep(5)
            }
            debugInfo("${getString(R.string.msg_config_success)}: $config_ret")
        }

        //debugInfo("config ret:$config_ret")
        return true
    }

    private fun uartWrite(bytearr: ByteArray, len: Int) {
        if (!serialOpened) {
            return
        }

        val ret = serialDriver.writeData(bytearr, len)
        if (ret < 0) {
            debugInfo(getString(R.string.msg_uart_disconnected))
            serialOpened = false
            findViewById<Button>(R.id.openSerialButton).isEnabled = true
            try {
                serialDriver.closeDevice()
            } catch (e: java.lang.Exception) {
                debugInfo(e.toString())
            }
        }
    }

    /** Called when the user taps the Send button */
    fun openSerial(view: View) {
        if (!openUartDevice()) {
            return
        }
        findViewById<Button>(R.id.openSerialButton).isEnabled = false
    }


    @SuppressLint("SetTextI18n")
    fun useGyro(view: View) {
        // Toggle the gyro control state in shared preferences
        val currentGyroState = sharedPreferences.getBoolean("gyro_enabled", false)
        val newGyroState = !currentGyroState

        with(sharedPreferences.edit()) {
            putBoolean("gyro_enabled", newGyroState)
            apply()
        }

        // Since the button no longer exists in UI, we just update the internal state
        // The actual enabling/disabling of joysticks is handled by checking preferences

        // If needed, you can update the joysticks immediately based on the new state
        if (newGyroState) {
            leftJoyStick.enable = false
            rightJoyStick.enable = false
        } else {
            leftJoyStick.enable = true
            rightJoyStick.enable = true
        }
    }

    /** Called when the user taps the Settings button */
    fun openSettings(view: View) {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    fun constrain(min: Float, max: Float, value: Float): Float {
        if (value < min) {
            return min
        } else if (value > max) {
            return max
        } else {
            return value
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            //音量+按键
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if(thrust<1){
                    thrust += 0.05f
                }else{
                    thrust = 1f
                }
                return true
            }
            //音量-按键
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (thrust > 0) {
                    thrust -= 0.05f
                } else {
                    thrust = 0f
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("SetTextI18n")
    fun sensorCallBack(listen: MyListener) {
        val currentTime = System.currentTimeMillis()

        // Read gyro control state from shared preferences
        val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false)

        // Update CRSF data regardless of UI throttling
        if (isGyroEnabled) {
            val tempRoll = constrain(-130f, -50f, listen.pitch) + 50
            val tempPitch = constrain(-40f, 40f, listen.roll) + 40

            val tempYaw = constrain(-40f,40f,listen.yaw-yaw_offset)+40

            // Apply CH1 range adjustment (for roll)
            val ch1RangePercentage = sharedPreferences.getInt("ch1_range", 100) / 100f
            val adjustedRollValue = 1 + (tempRoll / 80) * ch1RangePercentage
            crsfData.data_array[0] = duty2CRSF(adjustedRollValue)   // raw:0~-180

            // Apply CH2 range adjustment (for pitch)
            val ch2RangePercentage = sharedPreferences.getInt("ch2_range", 100) / 100f
            val adjustedPitchValue = (tempPitch / 80) * ch2RangePercentage
            crsfData.data_array[1] = duty2CRSF(adjustedPitchValue)  // raw:-90~90

            crsfData.data_array[2] = duty2CRSF(thrust)

            // Apply CH4 range adjustment (for yaw)
            val ch4RangePercentage = sharedPreferences.getInt("ch4_range", 100) / 100f
            val adjustedYawValue = (tempYaw/80) * ch4RangePercentage
            crsfData.data_array[3] = duty2CRSF(adjustedYawValue)

            leftJoyStick.setXY(0f,(thrust-0.5f)*2f)

            // Apply range adjustments to the right joystick display as well
            val adjustedRightX = ((1 + tempRoll / 80) * 2 - 1) * ch1RangePercentage
            val adjustedRightY = ((tempPitch / 80) * 2 - 1) * ch2RangePercentage
            rightJoyStick.setXY(adjustedRightX, adjustedRightY)

            if(listen.pitch > -20 || listen.pitch < -160){
                // a fast way to stop
                armSwitch.isChecked=false
            }
        }

        // Process all three-position switches
        listOf(ch6SwitchControl, ch7SwitchControl, ch8SwitchControl).forEach { switch ->
            val value = when (switch.position) {
                SwitchPosition.LOW -> 0f      // Lowest value
                SwitchPosition.MIDDLE -> 0.5f // Middle value
                SwitchPosition.HIGH -> 1f     // Highest value
            }
            crsfData.data_array[switch.dataArrayIndex] = duty2CRSF(value)
        }

        if(armSwitch.isChecked){
            crsfData.data_array[4]=duty2CRSF(1f)
            yaw_offset = listen.yaw
        }else{
            crsfData.data_array[4]=duty2CRSF(0f)
        }

        if (serialOpened) {
            bytes = crsfData.pack().toByteArray()
            uartWrite(bytes, 26)
        }

        // Throttle UI updates to prevent excessive redraws
        if (currentTime - lastUpdateTime >= uiUpdateInterval) {
            lastUpdateTime = currentTime

            // Check if debug values should be shown based on settings
            val showDebugValues = sharedPreferences.getBoolean("show_values", false)

            if (showDebugValues) {
                val channel_text = crsfData.data_array.map { "$it" }.joinToString("  ")
                findViewById<TextView>(R.id.testView).text = channel_text + "\nroll ${listen.roll} \npitch:${listen.pitch}" +
                        "\nyaw:${listen.yaw}\nyaw_offset:${yaw_offset}\n"
            } else {
                // If debug values are hidden, clear the text view or show nothing
                findViewById<TextView>(R.id.testView).text = ""
            }
        }
    }
}
