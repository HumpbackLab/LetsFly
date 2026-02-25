package com.example.myapplication

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
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

    private var thrust:Float=0f

    private lateinit var leftJoyStick: Joystick
    private lateinit var rightJoyStick: Joystick

    private lateinit var manualSwitch: Switch
    private lateinit var armSwitch: Switch

    private var yaw_offset:Float=0f

    // CH6 Three-position switch states
    private enum class CH6Position {
        LOW, MIDDLE, HIGH
    }

    private var ch6Position = CH6Position.LOW  // Start at LOW so first click goes to MIDDLE
    private lateinit var ch6Switch: Button

    fun duty2CRSF(duty:Float)=(duty * DUTY_CYCLE_MULTIPLIER + DUTY_CYCLE_OFFSET).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        sensorManager.registerListener(MyListener(this::sensorCallBack), sensor, SENSOR_DELAY_FASTEST);
        serialDriver =
            CH34xUARTDriver(getSystemService(USB_SERVICE) as UsbManager, this, "cn.wch.wchusbdriver.USB_PERMISSION")

        //bytes=getTestByteArray("C8 18 16 E0 03 1F 2B C0 F7 8B 5F FC E2 17 E5 2B 5F F9 CA 07 00 00 44 3C E2 B8")
        leftJoyStick = findViewById<Joystick>(R.id.leftJoystick)
        rightJoyStick = findViewById<Joystick>(R.id.rightJoystick)
        manualSwitch = findViewById(R.id.switchManual)
        armSwitch = findViewById(R.id.switchArm)
        ch6Switch = findViewById(R.id.switchCH6)


        // ch1 roll
        // ch2 pitch
        // ch3 throttle
        // ch4 yaw
        leftJoyStick.setOnJoystickMoveListener(
            object : OnJoystickMoveListener {
                override fun onJoystickValueChanged(x: Float, y: Float) {
                    if (!useGyroControl) {
                        crsfData.data_array[3] = duty2CRSF(x / 2f + 0.5f)
                        crsfData.data_array[2] = duty2CRSF(y / 2f + 0.5f)
                    }
                }
            }, 10
        )

        rightJoyStick.setOnJoystickMoveListener(
            object : OnJoystickMoveListener {
                override fun onJoystickValueChanged(x: Float, y: Float) {
                    if (!useGyroControl) {
                        crsfData.data_array[0] = duty2CRSF(x / 2 + 0.5f)
                        crsfData.data_array[1] = duty2CRSF(y / 2 + 0.5f)
                    }
                }
            }, 10
        )

        // Add listeners to switches for visual feedback
        manualSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchVisualFeedback(manualSwitch, isChecked)
        }

        armSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateSwitchVisualFeedback(armSwitch, isChecked)
        }

        // Add click listener to CH6 switch to cycle through positions
        ch6Switch.setOnClickListener {
            // Cycle through positions: LOW -> MIDDLE -> HIGH -> LOW
            ch6Position = when (ch6Position) {
                CH6Position.LOW -> CH6Position.MIDDLE
                CH6Position.MIDDLE -> CH6Position.HIGH
                CH6Position.HIGH -> CH6Position.LOW
            }
            ch6Switch.isEnabled = true  // Always enable the switch when clicked
            updateCH6SwitchUI()
        }

        // Initialize visual feedback for switches
        updateSwitchVisualFeedback(manualSwitch, manualSwitch.isChecked)
        updateSwitchVisualFeedback(armSwitch, armSwitch.isChecked)
        updateCH6SwitchUI()  // Initialize CH6 switch UI

        //val test=TextView(this)
        //test.setText("BV")
        //(tableLayoutView[3] as TableRow).addView(test)

        for (i in 1..16) {
            crsfData.data_array[i - 1] = i
        }
        bytes = crsfData.pack().toByteArray()
        for (i in bytes) {
            print(String.format("%02X,", i))
        }
        println("")
    }

    /** Called when the user taps the Toggle Layout button */
    fun toggleLayout(view: View) {
        // Toggle the requested orientation
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            // Currently in portrait, request landscape
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            // Currently in landscape, request portrait
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun updateSwitchVisualFeedback(switch: Switch, isChecked: Boolean) {
        if (isChecked) {
            switch.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            switch.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun updateCH6SwitchUI() {
        val positionText = when (ch6Position) {
            CH6Position.LOW -> "LOW"
            CH6Position.MIDDLE -> "MID"
            CH6Position.HIGH -> "HIGH"
        }
        ch6Switch.text = "CH6: $positionText"

        // Set visual feedback color based on position
        when (ch6Position) {
            CH6Position.LOW -> ch6Switch.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            CH6Position.MIDDLE -> ch6Switch.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            CH6Position.HIGH -> ch6Switch.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
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
            debugInfo("No Uart Device!")
            return false
        }

        if (!serialDriver.uartInit()) {
            debugInfo("Fail to Open Uart Device!")
            return false
        }
        val config_ret = serialDriver.setConfig(115200, 8, 1, 0, 0)
        if(config_ret){
            serialOpened = true
            bytes = crsfData.pack().toByteArray()
            for (i in 0..500) {
                uartWrite(bytes, 26)
                sleep(10)
            }
            bytes = crsfData.packCmd(0x01u,0x00u).toByteArray()
            for(i in 0..10){
                uartWrite(bytes, 8)
                sleep(10)
            }
            debugInfo("config ret:$config_ret")
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
            debugInfo("Uart device disconnected!")
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
        if (!useGyroControl) {
            useGyroControl = true
            leftJoyStick.enable = false
            rightJoyStick.enable = false

            findViewById<Button>(R.id.useGyroButton).text = "USE Joystick"
            thrust=0f
        } else {
            useGyroControl = false
            leftJoyStick.enable = true
            rightJoyStick.enable = true
            findViewById<Button>(R.id.useGyroButton).text = "USE Gyro"
        }

        // Visual feedback for the button
        val button = findViewById<Button>(R.id.useGyroButton)
        if (useGyroControl) {
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
        }
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

        // Update CRSF data regardless of UI throttling
        if (useGyroControl) {
            val tempRoll = constrain(-130f, -50f, listen.pitch) + 50
            val tempPitch = constrain(-40f, 40f, listen.roll) + 40

            val tempYaw = constrain(-40f,40f,listen.yaw-yaw_offset)+40

            crsfData.data_array[0] = duty2CRSF(1 + tempRoll / 80)   // raw:0~-180
            crsfData.data_array[1] = duty2CRSF(tempPitch / 80)  // raw:-90~90
            crsfData.data_array[2] = duty2CRSF(thrust)
            crsfData.data_array[3] = duty2CRSF(tempYaw/80)
            leftJoyStick.setXY(0f,(thrust-0.5f)*2f)
            rightJoyStick.setXY((1 + tempRoll / 80) * 2 - 1, (tempPitch / 80) * 2 - 1)

            if(listen.pitch > -20 || listen.pitch < -160){
                // a fast way to stop
                armSwitch.isChecked=false
            }
        }

        if(manualSwitch.isChecked){
            crsfData.data_array[7]=duty2CRSF(1f)
        }else{
            crsfData.data_array[7]=duty2CRSF(0f)
        }

        if(armSwitch.isChecked){
            crsfData.data_array[4]=duty2CRSF(1f)
            yaw_offset = listen.yaw
        }else{
            crsfData.data_array[4]=duty2CRSF(0f)
        }

        // Set CH6 value based on position
        val ch6Value = when (ch6Position) {
            CH6Position.LOW -> 0f      // Lowest value
            CH6Position.MIDDLE -> 0.5f // Middle value
            CH6Position.HIGH -> 1f     // Highest value
        }
        crsfData.data_array[5] = duty2CRSF(ch6Value)  // CH6 corresponds to index 5

        if (serialOpened) {
            bytes = crsfData.pack().toByteArray()
            uartWrite(bytes, 26)
        }

        // Throttle UI updates to prevent excessive redraws
        if (currentTime - lastUpdateTime >= uiUpdateInterval) {
            lastUpdateTime = currentTime

            val channel_text = crsfData.data_array.map { "$it" }.joinToString("  ")
            findViewById<TextView>(R.id.testView).text = channel_text + "\nroll ${listen.roll} \npitch:${listen.pitch}" +
                    "\nyaw:${listen.yaw}\nyaw_offset:${yaw_offset}\n"
        }
    }
}
