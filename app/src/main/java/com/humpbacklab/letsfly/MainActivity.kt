package com.humpbacklab.letsfly

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil

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

        // Default channel range percentage
        const val DEFAULT_CH4_RANGE = 50

        const val ORIENTATION_SINGLE_HAND = "single_hand"
        const val ORIENTATION_DUAL_HAND = "dual_hand"
        const val ORIENTATION_DUAL_HAND_AIRPLANE = "dual_hand_airplane"
        const val ORIENTATION_RC_CAR = "rc_car"
        const val ORIENTATION_CUSTOM = "custom"

        const val KEY_PHYSICAL_CALIBRATION_REQUESTED = "physical_joystick_calibration_requested"
        private const val KEY_PHYSICAL_CALIBRATED = "physical_joystick_calibrated"
        private const val PHYSICAL_LEFT = "physical_left"
        private const val PHYSICAL_RIGHT = "physical_right"
        private const val PHYSICAL_JOYSTICK_VISUAL_SCALE = 1.5f
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
    private var batteryTelemetry: CrsfBatteryTelemetry? = null
    private val batteryHandler = Handler(Looper.getMainLooper())
    private val batteryDisplayUpdater = object : Runnable {
        override fun run() {
            updateBatteryTelemetryView()
            batteryHandler.postDelayed(this, 500L)
        }
    }

    private lateinit var bytes: ByteArray
    private val crsfData: CRSFData = CRSFData()
    private var useGyroControl = false
    private var lastUpdateTime = 0L
    private val uiUpdateInterval = 50L // Limit UI updates to every 50ms (20Hz)

    private lateinit var sharedPreferences: android.content.SharedPreferences
    private lateinit var customConfig: CustomControlConfig
    private lateinit var customState: CustomControlState
    private var customCanvas: FrameLayout? = null
    private var customSafetyHold = false
    private val customHandler = Handler(Looper.getMainLooper())
    private var customSenderRunning = false
    private val customPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "orientation_mode") runOnUiThread {
            if (isCustomMode()) {
                armSwitch.isChecked = false
                customState.reset(false)
            }
            syncCustomSender()
        }
    }
    private val customSender = object : Runnable {
        override fun run() {
            val custom = isCustomMode()
            if (!custom && !customSafetyHold) {
                customSenderRunning = false
                return
            }
            if (custom || customSafetyHold) {
                if (custom && activeControlMode != ORIENTATION_CUSTOM && armSwitch.isChecked) {
                    armSwitch.isChecked = false
                }
                updateCustomChannelValues()
                if (serialOpened) {
                    bytes = crsfData.pack().toByteArray()
                    uartWrite(bytes, 26)
                }
                val now = System.currentTimeMillis()
                if (custom && now - lastUpdateTime >= uiUpdateInterval) {
                    lastUpdateTime = now
                    findViewById<TextView>(R.id.testView)?.text =
                        if (sharedPreferences.getBoolean("show_values", false))
                            crsfData.data_array.joinToString("  ") else ""
                }
            }
            customHandler.postDelayed(this, 20L)
        }
    }

    private fun updateCustomChannelValues() {
        val values = customState.snapshot()
        for (index in values.indices) {
            crsfData.data_array[index] = duty2CRSF(values[index] / 100f)
        }
    }

    private fun resetCustomSerialState() {
        if (!isCustomMode() && !customSafetyHold) return
        armSwitch.isChecked = false
        customState.reset(false)
        updateCustomControlViews()
        // Connection setup sends immediately, before the next scheduled custom frame.
        updateCustomChannelValues()
    }

    private fun syncCustomSender() {
        if ((isCustomMode() || customSafetyHold) && !customSenderRunning) {
            customSenderRunning = true
            customHandler.post(customSender)
        } else if (!isCustomMode() && !customSafetyHold && customSenderRunning) {
            customHandler.removeCallbacks(customSender)
            customSenderRunning = false
        }
    }

    private lateinit var videoView: ImageView
    private lateinit var videoRotateButton: ImageButton
    private lateinit var videoMirrorButton: ImageButton
    private var videoRotationDegrees = 0
    private var videoMirrored = false
    private var videoWidth = 0
    private var videoHeight = 0
    private lateinit var apfpvVideoReceiver: ApfpvVideoReceiver
    private val acceptingVideoFrames = AtomicBoolean(false)
    private val pendingVideoBitmap = AtomicReference<Bitmap?>()
    private val videoFramePostScheduled = AtomicBoolean(false)
    private val displayLatestVideoFrame = object : Runnable {
        override fun run() {
            val bitmap = pendingVideoBitmap.getAndSet(null)
            if (bitmap != null) {
                if (acceptingVideoFrames.get()) {
                    videoView.setImageBitmap(bitmap)
                    val sizeChanged = videoWidth != bitmap.width || videoHeight != bitmap.height
                    videoWidth = bitmap.width
                    videoHeight = bitmap.height
                    if (sizeChanged) updateVideoMatrix()
                } else {
                    bitmap.recycle()
                }
            }

            videoFramePostScheduled.set(false)
            if (acceptingVideoFrames.get() &&
                pendingVideoBitmap.get() != null &&
                videoFramePostScheduled.compareAndSet(false, true)
            ) {
                videoView.post(this)
            }
        }
    }

    private var thrust:Float=0f

    private lateinit var leftJoyStick: Joystick
    private lateinit var rightJoyStick: Joystick
    private var originalLeftJoystickWidth: Int = 0
    private var originalLeftJoystickHeight: Int = 0
    private var originalRightJoystickWidth: Int = 0
    private var originalRightJoystickHeight: Int = 0
    private var calibrationView: PhysicalJoystickCalibrationView? = null
    private var activeControlMode: String? = null

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
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.apfpvVideoView)
        videoView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoMatrix() }
        videoRotateButton = findViewById(R.id.videoRotateButton)
        videoMirrorButton = findViewById(R.id.videoMirrorButton)
        videoRotateButton.setOnClickListener {
            videoRotationDegrees = (videoRotationDegrees + 90) % 360
            updateVideoControls()
            updateVideoMatrix()
        }
        videoMirrorButton.setOnClickListener {
            videoMirrored = !videoMirrored
            updateVideoControls()
            updateVideoMatrix()
        }
        updateVideoControls()
        apfpvVideoReceiver = ApfpvVideoReceiver(::queueLatestVideoFrame)

        // Initialize shared preferences
        sharedPreferences = getSharedPreferences("rc_controller_prefs", Context.MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(customPreferenceListener)
        customConfig = CustomControlConfig.load(sharedPreferences)
        customState = CustomControlState(customConfig)
        customCanvas = findViewById(R.id.customControlCanvas)

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        sensorManager.registerListener(MyListener(this::sensorCallBack), sensor, SENSOR_DELAY_FASTEST);
        serialDriver =
            CH34xUARTDriver(getSystemService(USB_SERVICE) as UsbManager, this, "cn.wch.wchusbdriver.USB_PERMISSION")

        //bytes=getTestByteArray("C8 18 16 E0 03 1F 2B C0 F7 8B 5F FC E2 17 E5 2B 5F F9 CA 07 00 00 44 3C E2 B8")
        leftJoyStick = findViewById<Joystick>(R.id.leftJoystick)
        rightJoyStick = findViewById<Joystick>(R.id.rightJoystick)
        originalLeftJoystickWidth = leftJoyStick.layoutParams.width
        originalLeftJoystickHeight = leftJoyStick.layoutParams.height
        originalRightJoystickWidth = rightJoyStick.layoutParams.width
        originalRightJoystickHeight = rightJoyStick.layoutParams.height
        armSwitch = findViewById(R.id.switchArm)

        // Initialize three-position switches with a helper function
        ch6SwitchControl = createThreePositionSwitch(R.id.switchCH6, SwitchPosition.LOW, 5)  // CH6 corresponds to index 5
        ch7SwitchControl = createThreePositionSwitch(R.id.switchCH7, SwitchPosition.LOW, 6)  // CH7 corresponds to index 6
        ch8SwitchControl = createThreePositionSwitch(R.id.switchCH8, SwitchPosition.LOW, 7)  // CH8 corresponds to index 7

        val loadLeftJoyStick = loadLeft@{ x:Float, y:Float ->
            if (isCustomMode() || customSafetyHold) return@loadLeft
            val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false) && !isRcCarMode()
            if(!isGyroEnabled){
                if (isRcCarMode()) {
                    val range = sharedPreferences.getInt("ch4_range", DEFAULT_CH4_RANGE)
                    crsfData.data_array[3] = duty2CRSF(RcCarChannelMapping.duty(x, range))
                    return@loadLeft
                }
                if (!isDualHandAirplaneMode()) {
                    // Apply CH4 (index 3) range adjustment
                    val ch4RangePercentage = sharedPreferences.getInt("ch4_range", DEFAULT_CH4_RANGE) / 100f
                    val adjustedX = x * ch4RangePercentage
                    crsfData.data_array[3] = duty2CRSF(adjustedX / 2f + 0.5f)
                }

                // Apply CH3 (index 2) range adjustment
                val ch3RangePercentage = sharedPreferences.getInt("ch3_range", 100) / 100f
                crsfData.data_array[2] = duty2CRSF((y / 2f + 0.5f) * ch3RangePercentage) 
                // special for throttle, range adjustment is applied after centering to ensure it scales correctly
                // from the minimum value  
            }
        }

        val loadRightJoyStick = loadRight@{ x:Float, y:Float ->
            if (isCustomMode() || customSafetyHold) return@loadRight
            val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false) && !isRcCarMode()
            if (!isGyroEnabled){
                if (isRcCarMode()) {
                    val range = sharedPreferences.getInt("ch3_range", 100)
                    crsfData.data_array[2] = duty2CRSF(RcCarChannelMapping.duty(y, range))
                    return@loadRight
                }
                if (isDualHandAirplaneMode()) {
                    // Paper-airplane mode has no roll input. Right X controls yaw instead.
                    crsfData.data_array[0] = duty2CRSF(0.5f)
                    val ch4RangePercentage = sharedPreferences.getInt("ch4_range", DEFAULT_CH4_RANGE) / 100f
                    val adjustedX = x * ch4RangePercentage
                    crsfData.data_array[3] = duty2CRSF(adjustedX / 2f + 0.5f)
                } else {
                    // Apply CH1 (index 0) range adjustment
                    val ch1RangePercentage = sharedPreferences.getInt("ch1_range", 100) / 100f
                    val adjustedX = x * ch1RangePercentage
                    crsfData.data_array[0] = duty2CRSF(adjustedX / 2 + 0.5f)
                }

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
            if (isCustomMode() || customSafetyHold) {
                customState.setArmed(isChecked && !customSafetyHold)
                updateCustomControlViews()
                return@setOnCheckedChangeListener
            }
            // Enable the controls when armed.
            leftJoyStick.enable = isChecked
            if (isRcCarMode()) rightJoyStick.enable = isChecked
            // When disarmed, reset aircraft throttle to minimum or car controls to center.
            if (!isChecked) {
                thrust = 0f
                leftJoyStick.setXY(0f, if (isRcCarMode()) 0f else -1.0f)
                loadLeftJoyStick(leftJoyStick.getOutX(), leftJoyStick.getOutY())
                if (isRcCarMode()) {
                    rightJoyStick.setXY(0f, 0f)
                    loadRightJoyStick(0f, 0f)
                }
            }
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
            if (isCustomMode()) return@post
            applySavedPhysicalJoystickLayout()
            leftJoyStick.setXY(0f, if (isRcCarMode()) 0f else -1.0f)
            rightJoyStick.setXY(0f, 0f)
            loadLeftJoyStick(leftJoyStick.getOutX(), leftJoyStick.getOutY())
            loadRightJoyStick(rightJoyStick.getOutX(), rightJoyStick.getOutY())
        }

        // Add listener for when left joystick is touched while disabled
        leftJoyStick.addOnDisabledTouchListener(object : OnJoystickDisabledTouch {
            override fun onDisabledTouch() {
                debugInfo(getString(R.string.msg_please_arm_first))
            }
        })

        // Set initial orientation based on settings
        setInitialOrientation()
        syncCustomSender()
    }

    private fun setInitialOrientation() {
        // Check shared preferences for the orientation mode
        val orientationMode = sharedPreferences.getString("orientation_mode", ORIENTATION_SINGLE_HAND)
        when (orientationMode) {
            ORIENTATION_DUAL_HAND, ORIENTATION_DUAL_HAND_AIRPLANE, ORIENTATION_RC_CAR,
            ORIENTATION_CUSTOM -> {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            else -> { // Default to single hand (portrait)
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    override fun onResume() {
        super.onResume()
        customSafetyHold = false
        if (isCustomMode()) {
            val savedConfig = CustomControlConfig.load(sharedPreferences)
            if (savedConfig != customConfig) {
                customConfig = savedConfig
                customState = CustomControlState(customConfig)
            }
            armSwitch.isChecked = false
        }
        applyControlModeDefaults()
        syncCustomSender()
        initializeJoystickStates()
        leftJoyStick.post {
            applySavedPhysicalJoystickLayout()
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                sharedPreferences.getBoolean(KEY_PHYSICAL_CALIBRATION_REQUESTED, false)
            ) {
                sharedPreferences.edit().putBoolean(KEY_PHYSICAL_CALIBRATION_REQUESTED, false).apply()
                startPhysicalJoystickCalibration()
            }
        }
        acceptingVideoFrames.set(true)
        apfpvVideoReceiver.start()
        // Update orientation when activity resumes (e.g., when returning from settings)
        setInitialOrientation()
        batteryHandler.removeCallbacks(batteryDisplayUpdater)
        batteryHandler.post(batteryDisplayUpdater)
    }

    override fun onPause() {
        batteryHandler.removeCallbacks(batteryDisplayUpdater)
        if (isCustomMode()) {
            customSafetyHold = true
            armSwitch.isChecked = false
            customState.setArmed(false)
            updateCustomControlViews()
            syncCustomSender()
        }
        acceptingVideoFrames.set(false)
        apfpvVideoReceiver.stop()
        videoView.removeCallbacks(displayLatestVideoFrame)
        videoFramePostScheduled.set(false)
        pendingVideoBitmap.getAndSet(null)?.recycle()
        videoView.setImageDrawable(null)
        videoWidth = 0
        videoHeight = 0
        super.onPause()
    }

    override fun onDestroy() {
        serialDriver.setReadListener(null)
        serialDriver.setCloseListener(null)
        batteryTelemetry = null
        batteryHandler.removeCallbacksAndMessages(null)
        customHandler.removeCallbacks(customSender)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(customPreferenceListener)
        super.onDestroy()
    }

    private fun updateVideoControls() {
        videoRotateButton.contentDescription = getString(R.string.video_rotate_description, videoRotationDegrees)
        videoMirrorButton.contentDescription = getString(
            if (videoMirrored) R.string.video_mirror_on_description else R.string.video_mirror_off_description
        )
        videoMirrorButton.isSelected = videoMirrored
        videoMirrorButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (videoMirrored) R.color.teal_200 else R.color.white)
        )
    }

    private fun updateVideoMatrix() {
        val viewWidth = videoView.width
        val viewHeight = videoView.height
        if (videoWidth == 0 || videoHeight == 0 || viewWidth == 0 || viewHeight == 0) return

        val quarterTurn = videoRotationDegrees % 180 != 0
        val rotatedWidth = if (quarterTurn) videoHeight else videoWidth
        val rotatedHeight = if (quarterTurn) videoWidth else videoHeight
        val scale = minOf(viewWidth.toFloat() / rotatedWidth, viewHeight.toFloat() / rotatedHeight)
        videoView.imageMatrix = Matrix().apply {
            postTranslate(-videoWidth / 2f, -videoHeight / 2f)
            postRotate(videoRotationDegrees.toFloat())
            postScale(if (videoMirrored) -scale else scale, scale)
            postTranslate(viewWidth / 2f, viewHeight / 2f)
        }
    }

    private fun queueLatestVideoFrame(bitmap: Bitmap) {
        if (!acceptingVideoFrames.get()) {
            bitmap.recycle()
            return
        }

        pendingVideoBitmap.getAndSet(bitmap)?.recycle()
        if (videoFramePostScheduled.compareAndSet(false, true)) {
            videoView.post(displayLatestVideoFrame)
        }
    }

    private fun initializeJoystickStates() {
        if (isCustomMode()) {
            leftJoyStick.enable = false
            rightJoyStick.enable = false
            return
        }
        val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false) && !isRcCarMode()
        if (isGyroEnabled) {
            leftJoyStick.enable = false
            rightJoyStick.enable = false
        } else {
            leftJoyStick.enable = armSwitch.isChecked
            rightJoyStick.enable = if (isRcCarMode()) armSwitch.isChecked else true
        }
    }

    private fun startPhysicalJoystickCalibration() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE || calibrationView != null) {
            return
        }

        // Calibration must never move control channels or leave the aircraft armed.
        armSwitch.isChecked = false
        sharedPreferences.edit().putBoolean("gyro_enabled", false).apply()
        leftJoyStick.enable = false
        rightJoyStick.enable = false

        val content = findViewById<FrameLayout>(android.R.id.content)
        val overlay = PhysicalJoystickCalibrationView(
            this,
            promptForSide = { left ->
                getString(if (left) R.string.physical_calibration_left_prompt else R.string.physical_calibration_right_prompt)
            },
            invalidPrompt = { getString(R.string.physical_calibration_invalid) },
            onComplete = { left, right -> finishPhysicalJoystickCalibration(content, left, right) }
        )
        calibrationView = overlay
        content.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun finishPhysicalJoystickCalibration(
        content: FrameLayout,
        left: JoystickTravelBounds,
        right: JoystickTravelBounds
    ) {
        savePhysicalBounds(PHYSICAL_LEFT, left, content.width, content.height)
        savePhysicalBounds(PHYSICAL_RIGHT, right, content.width, content.height)
        sharedPreferences.edit().putBoolean(KEY_PHYSICAL_CALIBRATED, true).apply()
        calibrationView?.let(content::removeView)
        calibrationView = null
        initializeJoystickStates()
        applySavedPhysicalJoystickLayout()
        debugInfo(getString(R.string.physical_calibration_complete))
    }

    private fun savePhysicalBounds(prefix: String, bounds: JoystickTravelBounds, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        sharedPreferences.edit()
            .putFloat("${prefix}_min_x", bounds.minX / width)
            .putFloat("${prefix}_max_x", bounds.maxX / width)
            .putFloat("${prefix}_min_y", bounds.minY / height)
            .putFloat("${prefix}_max_y", bounds.maxY / height)
            .apply()
    }

    private fun loadPhysicalBounds(prefix: String, width: Int, height: Int): JoystickTravelBounds? {
        val keys = listOf("min_x", "max_x", "min_y", "max_y")
        if (keys.any { !sharedPreferences.contains("${prefix}_$it") }) return null
        return JoystickTravelBounds(
            sharedPreferences.getFloat("${prefix}_min_x", 0f) * width,
            sharedPreferences.getFloat("${prefix}_max_x", 0f) * width,
            sharedPreferences.getFloat("${prefix}_min_y", 0f) * height,
            sharedPreferences.getFloat("${prefix}_max_y", 0f) * height
        )
    }

    private fun applySavedPhysicalJoystickLayout() {
        if (isCustomMode() || isRcCarMode() || resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
            !sharedPreferences.getBoolean(KEY_PHYSICAL_CALIBRATED, false)
        ) {
            resetPhysicalJoystickLayout()
            return
        }

        val parent = leftJoyStick.parent as? ViewGroup ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        val left = loadPhysicalBounds(PHYSICAL_LEFT, parent.width, parent.height) ?: return
        val right = loadPhysicalBounds(PHYSICAL_RIGHT, parent.width, parent.height) ?: return
        applyPhysicalBounds(leftJoyStick, left)
        applyPhysicalBounds(rightJoyStick, right)
    }

    private fun resetPhysicalJoystickLayout() {
        if (isRcCarMode()) {
            val density = resources.displayMetrics.density
            resetJoystickLayout(leftJoyStick, (280 * density).toInt(), (144 * density).toInt())
            resetJoystickLayout(rightJoyStick, (144 * density).toInt(), (280 * density).toInt())
        } else {
            resetJoystickLayout(leftJoyStick, originalLeftJoystickWidth, originalLeftJoystickHeight)
            resetJoystickLayout(rightJoyStick, originalRightJoystickWidth, originalRightJoystickHeight)
        }
    }

    private fun resetJoystickLayout(joystick: Joystick, originalWidth: Int, originalHeight: Int) {
        val params = joystick.layoutParams
        if (params.width != originalWidth || params.height != originalHeight) {
            params.width = originalWidth
            params.height = originalHeight
            joystick.layoutParams = params
        }
        joystick.setPhysicalGeometryEnabled(false)
        joystick.translationX = 0f
        joystick.translationY = 0f
    }

    private fun applyPhysicalBounds(joystick: Joystick, bounds: JoystickTravelBounds) {
        // Joystick's active travel is 75% of its view size; the remaining area holds the knob.
        val params = joystick.layoutParams
        params.width = ceil(bounds.width * PHYSICAL_JOYSTICK_VISUAL_SCALE / 0.75f).toInt().coerceAtLeast(1)
        params.height = ceil(bounds.height * PHYSICAL_JOYSTICK_VISUAL_SCALE / 0.75f).toInt().coerceAtLeast(1)
        joystick.setPhysicalGeometryEnabled(true, PHYSICAL_JOYSTICK_VISUAL_SCALE)
        joystick.layoutParams = params
        joystick.post {
            joystick.x = bounds.centerX - joystick.width / 2f
            joystick.y = bounds.centerY - joystick.height / 2f
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val overlay = calibrationView
        if (overlay != null) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            calibrationView = null
            initializeJoystickStates()
            return
        }
        super.onBackPressed()
    }

    private fun isDualHandAirplaneMode(): Boolean =
        sharedPreferences.getString("orientation_mode", ORIENTATION_SINGLE_HAND) == ORIENTATION_DUAL_HAND_AIRPLANE

    private fun isRcCarMode(): Boolean =
        sharedPreferences.getString("orientation_mode", ORIENTATION_SINGLE_HAND) == ORIENTATION_RC_CAR

    private fun isCustomMode(): Boolean =
        ::sharedPreferences.isInitialized &&
            sharedPreferences.getString("orientation_mode", ORIENTATION_SINGLE_HAND) == ORIENTATION_CUSTOM

    private fun renderCustomControls() {
        val canvas = customCanvas ?: return
        CustomControlViews.whenCanvasReady(canvas) {
            if (!isCustomMode() || canvas.visibility != View.VISIBLE) return@whenCanvasReady
            customConfig = customConfig.asFullScreen(canvas.height,
                findViewById<View>(R.id.topControlsContainer).height)
            canvas.removeAllViews()
            for (control in customConfig.controls) {
                val view = CustomControlViews.create(this, control)
                CustomControlViews.place(canvas, view, control) {
                    updateCustomControlView(view, control)
                }
                if (view is Joystick) {
                    view.enable = !customSafetyHold && customState.isControlEnabled(control)
                    view.setOnJoystickMoveListener(object : OnJoystickMoveListener {
                        override fun onJoystickValueChanged(x: Float, y: Float) {
                            customState.move(control.id, x, y)
                            if (!customState.armed)
                                updateCustomControlView(view, control, lockedAxesOnly = true)
                        }
                    }, 10)
                    view.setOnReleaseListener { customState.release(control.id) }
                } else if (view is Button) {
                    view.isEnabled = !customSafetyHold && customState.isControlEnabled(control)
                    view.setOnClickListener {
                        CustomControlViews.styleButton(view, control,
                            customState.advanceButton(control.id))
                    }
                }
            }
        }
    }

    private fun updateCustomControlViews() {
        val canvas = customCanvas ?: return
        for (index in customConfig.controls.indices) {
            val control = customConfig.controls[index]
            val view = canvas.getChildAt(index) ?: continue
            updateCustomControlView(view, control)
        }
    }

    private fun updateCustomControlView(view: View, control: CustomControl,
        lockedAxesOnly: Boolean = false) {
        if (view is Joystick) {
            view.enable = !customSafetyHold && customState.isControlEnabled(control)
            val xAxis = if (control.type == CustomControlType.VERTICAL) null else control.first
            val yAxis = control.second ?: if (control.type == CustomControlType.VERTICAL)
                control.first else null
            fun current(axis: CustomAxis?) = axis?.position(customState.value(axis.channel)) ?: 0f
            fun reset(axis: CustomAxis?) = if (axis == null) 0f else
                axis.position(if (axis.autoReturn) axis.returnValue else customState.value(axis.channel))
            view.setDefaultPosition(reset(xAxis), reset(yAxis))
            // Do not quantize an active auxiliary axis back into its pixel position
            // on every polling callback: repeated truncation can make it drift.
            view.setXY(current(xAxis), current(yAxis),
                updateX = !lockedAxesOnly || xAxis?.channel in 1..4,
                updateY = !lockedAxesOnly || yAxis?.channel in 1..4)
        } else if (view is Button) {
            view.isEnabled = !customSafetyHold && customState.isControlEnabled(control)
            CustomControlViews.styleButton(view, control, customState.buttonPosition(control.id))
        }
    }

    private fun applyControlModeDefaults() {
        val mode = sharedPreferences.getString("orientation_mode", ORIENTATION_SINGLE_HAND)
        val leavingCustom = activeControlMode == ORIENTATION_CUSTOM && mode != ORIENTATION_CUSTOM
        val changedMode = activeControlMode != null && activeControlMode != mode
        activeControlMode = mode
        val custom = mode == ORIENTATION_CUSTOM
        customCanvas?.visibility = if (custom) View.VISIBLE else View.GONE
        leftJoyStick.visibility = if (custom) View.GONE else View.VISIBLE
        rightJoyStick.visibility = when {
            custom -> View.GONE
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT -> View.INVISIBLE
            else -> View.VISIBLE
        }
        for (id in listOf(R.id.switchCH6, R.id.switchCH7, R.id.switchCH8)) {
            findViewById<View>(id).visibility = if (custom) View.GONE else View.VISIBLE
        }
        if (custom) {
            if (changedMode) customState.reset(false) else customState.setArmed(false)
            renderCustomControls()
            return
        }
        customCanvas?.removeAllViews()
        if (leavingCustom) {
            for (channel in 9..16) crsfData.data_array[channel - 1] = channel
        }
        val airplaneMode = isDualHandAirplaneMode()
        val carMode = isRcCarMode()
        leftJoyStick.setAxisMode(if (carMode) Joystick.AxisMode.HORIZONTAL else Joystick.AxisMode.BOTH)
        rightJoyStick.setAxisMode(if (carMode) Joystick.AxisMode.VERTICAL else Joystick.AxisMode.BOTH)
        leftJoyStick.setHorizontalLocked(airplaneMode)
        if (carMode) {
            leftJoyStick.setXY(0f, 0f)
            rightJoyStick.setXY(0f, 0f)
            crsfData.data_array[0] = duty2CRSF(0.5f)
            crsfData.data_array[1] = duty2CRSF(0.5f)
            crsfData.data_array[2] = duty2CRSF(0.5f)
            crsfData.data_array[3] = duty2CRSF(0.5f)
        } else if (changedMode) {
            leftJoyStick.setXY(0f, -1f)
            rightJoyStick.setXY(0f, 0f)
            crsfData.data_array[0] = duty2CRSF(0.5f)
            crsfData.data_array[1] = duty2CRSF(0.5f)
            crsfData.data_array[2] = duty2CRSF(0f)
            crsfData.data_array[3] = duty2CRSF(0.5f)
        }
        if (airplaneMode && !sharedPreferences.getBoolean("gyro_enabled", false)) {
            crsfData.data_array[0] = duty2CRSF(0.5f)
            crsfData.data_array[3] = duty2CRSF(0.5f)
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
                putString("orientation_mode", ORIENTATION_DUAL_HAND)
                apply()
            }
        } else {
            // Currently in landscape, switch to portrait (single hand mode)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            // Save the new orientation mode to shared preferences
            with(sharedPreferences.edit()) {
                putString("orientation_mode", ORIENTATION_SINGLE_HAND)
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

    private fun prepareBatteryTelemetry() {
        val telemetry = CrsfBatteryTelemetry()
        batteryTelemetry = telemetry
        serialDriver.setReadListener { data ->
            telemetry.feed(data, SystemClock.elapsedRealtime())
        }
        serialDriver.setCloseListener {
            batteryHandler.post {
                // Ignore a queued disconnect from an earlier serial session.
                if (batteryTelemetry !== telemetry) return@post
                serialOpened = false
                batteryTelemetry = null
                resetCustomSerialState()
                findViewById<Button>(R.id.openSerialButton).isEnabled = true
                updateBatteryTelemetryView()
            }
        }
    }

    private fun updateBatteryTelemetryView() {
        val view = findViewById<TextView>(R.id.batteryTelemetryView)
        val reading = batteryTelemetry?.latest()
        val available = serialOpened && reading?.isFresh(SystemClock.elapsedRealtime()) == true
        val text: String
        val description: String
        if (available && reading != null) {
            val voltage = reading.voltageDecivolts / 10.0
            val percent = reading.remainingPercent
            text = if (percent == null) getString(R.string.battery_voltage, voltage)
                else getString(R.string.battery_voltage_percent, voltage, percent)
            description = if (percent == null)
                getString(R.string.battery_voltage_description, voltage)
            else getString(R.string.battery_percent_description, voltage, percent)
        } else {
            text = getString(R.string.battery_unavailable)
            description = getString(when {
                !serialOpened -> R.string.battery_disconnected_description
                reading == null -> R.string.battery_waiting_description
                else -> R.string.battery_stale_description
            })
        }
        if (view.text.toString() != text) view.text = text
        if (view.contentDescription != description) view.contentDescription = description
    }

    private fun openUartDevice(): Boolean {
        resetCustomSerialState()
        prepareBatteryTelemetry()
        if (!armSwitch.isChecked && !isCustomMode() && !customSafetyHold) {
            crsfData.data_array[2] = duty2CRSF(if (isRcCarMode()) 0.5f else 0f)
            crsfData.data_array[4] = duty2CRSF(0f)
        }
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
        return if (isCustomMode() || customSafetyHold) serialOpened else true
    }

    private fun uartWrite(bytearr: ByteArray, len: Int) {
        if (!serialOpened) {
            return
        }

        val ret = serialDriver.writeData(bytearr, len)
        if (ret < 0) {
            debugInfo(getString(R.string.msg_uart_disconnected))
            serialOpened = false
            batteryTelemetry = null
            updateBatteryTelemetryView()
            resetCustomSerialState()
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
            initializeJoystickStates()
        }
    }

    /** Called when the user taps the Settings button */
    fun openSettings(view: View) {
        if (isCustomMode()) armSwitch.isChecked = false
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
                if (!armSwitch.isChecked) return true
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
        if (isCustomMode() || customSafetyHold) return
        val currentTime = System.currentTimeMillis()

        // Read gyro control state from shared preferences
        val isGyroEnabled = sharedPreferences.getBoolean("gyro_enabled", false) && !isRcCarMode()

        // Update CRSF data regardless of UI throttling
        if (isGyroEnabled) {
            if (!armSwitch.isChecked) thrust = 0f
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
            val ch4RangePercentage = sharedPreferences.getInt("ch4_range", DEFAULT_CH4_RANGE) / 100f
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
            // Lock throttle at the mode's stop value even if a stale joystick
            // callback or gyro update changed the raw input while disarmed.
            crsfData.data_array[2]=duty2CRSF(if (isRcCarMode()) 0.5f else 0f)
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
