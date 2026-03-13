package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var gyroToggle: Switch
    private lateinit var sensitivitySlider: SeekBar
    private lateinit var ch1RangeSlider: SeekBar
    private lateinit var ch2RangeSlider: SeekBar
    private lateinit var ch3RangeSlider: SeekBar
    private lateinit var ch4RangeSlider: SeekBar
    private lateinit var ch1RangeValue: TextView
    private lateinit var ch2RangeValue: TextView
    private lateinit var ch3RangeValue: TextView
    private lateinit var ch4RangeValue: TextView
    private lateinit var orientationRadioGroup: RadioGroup
    private lateinit var singleHandRadioButton: RadioButton
    private lateinit var dualHandRadioButton: RadioButton
    private lateinit var backButton: Button

    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val PREF_NAME = "rc_controller_prefs"
        private const val KEY_GYRO_ENABLED = "gyro_enabled"
        private const val KEY_GYRO_SENSITIVITY = "gyro_sensitivity"
        private const val KEY_CH1_RANGE = "ch1_range"
        private const val KEY_CH2_RANGE = "ch2_range"
        private const val KEY_CH3_RANGE = "ch3_range"
        private const val KEY_CH4_RANGE = "ch4_range"
        private const val KEY_ORIENTATION_MODE = "orientation_mode"
        private const val ORIENTATION_SINGLE_HAND = "single_hand"  // portrait
        private const val ORIENTATION_DUAL_HAND = "dual_hand"     // landscape
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        initializeViews()
        setupGyroToggle()
        setupSensitivitySlider()
        setupChannelRangeSliders()
        setupOrientationSelection()
        setupBackButton()
        loadSavedPreferences()
    }

    private fun initializeViews() {
        gyroToggle = findViewById(R.id.gyroToggle)
        sensitivitySlider = findViewById(R.id.sensitivitySlider)
        ch1RangeSlider = findViewById(R.id.ch1RangeSlider)
        ch2RangeSlider = findViewById(R.id.ch2RangeSlider)
        ch3RangeSlider = findViewById(R.id.ch3RangeSlider)
        ch4RangeSlider = findViewById(R.id.ch4RangeSlider)
        ch1RangeValue = findViewById(R.id.ch1RangeValue)
        ch2RangeValue = findViewById(R.id.ch2RangeValue)
        ch3RangeValue = findViewById(R.id.ch3RangeValue)
        ch4RangeValue = findViewById(R.id.ch4RangeValue)
        orientationRadioGroup = findViewById(R.id.orientationRadioGroup)
        singleHandRadioButton = findViewById(R.id.singleHandRadioButton)
        dualHandRadioButton = findViewById(R.id.dualHandRadioButton)
        backButton = findViewById(R.id.backButton)
    }

    private fun setupGyroToggle() {
        gyroToggle.setOnCheckedChangeListener { _, isChecked ->
            updateGyroToggleVisualFeedback(isChecked)

            // Save the setting to shared preferences
            with(sharedPreferences.edit()) {
                putBoolean(KEY_GYRO_ENABLED, isChecked)
                apply()
            }

            Toast.makeText(this,
                if (isChecked) "Gyro control enabled" else "Gyro control disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupSensitivitySlider() {
        // Set up the sensitivity slider with range 1-10
        sensitivitySlider.max = 9
        sensitivitySlider.progress = sharedPreferences.getInt(KEY_GYRO_SENSITIVITY, 5) - 1

        sensitivitySlider.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivityValue = progress + 1

                // Save the setting to shared preferences
                with(sharedPreferences.edit()) {
                    putInt(KEY_GYRO_SENSITIVITY, sensitivityValue)
                    apply()
                }

                // Show toast with current sensitivity
                Toast.makeText(this@SettingsActivity,
                    "Sensitivity: $sensitivityValue",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupChannelRangeSliders() {
        // Set up the range sliders with range 20-100 (percentage)
        val defaultRange = 100  // Default to 100% range

        // CH1 Range Slider
        ch1RangeSlider.max = 80  // From 20 to 100 (80 steps)
        ch1RangeSlider.progress = sharedPreferences.getInt(KEY_CH1_RANGE, defaultRange) - 20
        ch1RangeValue.text = "${sharedPreferences.getInt(KEY_CH1_RANGE, defaultRange)}%"

        ch1RangeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rangeValue = progress + 20  // Convert back to percentage (20-100)

                // Update the displayed value
                ch1RangeValue.text = "${rangeValue}%"

                // Save the setting to shared preferences
                with(sharedPreferences.edit()) {
                    putInt(KEY_CH1_RANGE, rangeValue)
                    apply()
                }

                // Show toast with current range
                Toast.makeText(this@SettingsActivity,
                    "CH1 Range: $rangeValue%",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // CH2 Range Slider
        ch2RangeSlider.max = 80  // From 20 to 100 (80 steps)
        ch2RangeSlider.progress = sharedPreferences.getInt(KEY_CH2_RANGE, defaultRange) - 20
        ch2RangeValue.text = "${sharedPreferences.getInt(KEY_CH2_RANGE, defaultRange)}%"

        ch2RangeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rangeValue = progress + 20  // Convert back to percentage (20-100)

                // Update the displayed value
                ch2RangeValue.text = "${rangeValue}%"

                // Save the setting to shared preferences
                with(sharedPreferences.edit()) {
                    putInt(KEY_CH2_RANGE, rangeValue)
                    apply()
                }

                // Show toast with current range
                Toast.makeText(this@SettingsActivity,
                    "CH2 Range: $rangeValue%",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // CH3 Range Slider
        ch3RangeSlider.max = 80  // From 20 to 100 (80 steps)
        ch3RangeSlider.progress = sharedPreferences.getInt(KEY_CH3_RANGE, defaultRange) - 20
        ch3RangeValue.text = "${sharedPreferences.getInt(KEY_CH3_RANGE, defaultRange)}%"

        ch3RangeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rangeValue = progress + 20  // Convert back to percentage (20-100)

                // Update the displayed value
                ch3RangeValue.text = "${rangeValue}%"

                // Save the setting to shared preferences
                with(sharedPreferences.edit()) {
                    putInt(KEY_CH3_RANGE, rangeValue)
                    apply()
                }

                // Show toast with current range
                Toast.makeText(this@SettingsActivity,
                    "CH3 Range: $rangeValue%",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // CH4 Range Slider
        ch4RangeSlider.max = 80  // From 20 to 100 (80 steps)
        ch4RangeSlider.progress = sharedPreferences.getInt(KEY_CH4_RANGE, defaultRange) - 20
        ch4RangeValue.text = "${sharedPreferences.getInt(KEY_CH4_RANGE, defaultRange)}%"

        ch4RangeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rangeValue = progress + 20  // Convert back to percentage (20-100)

                // Update the displayed value
                ch4RangeValue.text = "${rangeValue}%"

                // Save the setting to shared preferences
                with(sharedPreferences.edit()) {
                    putInt(KEY_CH4_RANGE, rangeValue)
                    apply()
                }

                // Show toast with current range
                Toast.makeText(this@SettingsActivity,
                    "CH4 Range: $rangeValue%",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupOrientationSelection() {
        // Set up the orientation radio group
        val selectedOrientation = sharedPreferences.getString(KEY_ORIENTATION_MODE, ORIENTATION_SINGLE_HAND)

        when (selectedOrientation) {
            ORIENTATION_DUAL_HAND -> dualHandRadioButton.isChecked = true
            else -> singleHandRadioButton.isChecked = true  // Default to single hand
        }

        orientationRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val orientation = when (checkedId) {
                R.id.dualHandRadioButton -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ORIENTATION_DUAL_HAND
                }
                else -> { // Default to single hand
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    ORIENTATION_SINGLE_HAND
                }
            }

            // Save the setting to shared preferences
            with(sharedPreferences.edit()) {
                putString(KEY_ORIENTATION_MODE, orientation)
                apply()
            }

            // Show toast with current selection
            Toast.makeText(this@SettingsActivity,
                if (orientation == ORIENTATION_SINGLE_HAND) "Single Hand Mode Selected" else "Dual Hand Mode Selected",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupBackButton() {
        backButton.setOnClickListener {
            // Before closing the settings activity, ensure the main activity's orientation is updated
            val orientationMode = if (dualHandRadioButton.isChecked) "dual_hand" else "single_hand"

            // Save the setting to shared preferences
            with(sharedPreferences.edit()) {
                putString("orientation_mode", orientationMode)
                apply()
            }

            finish() // Close the settings activity and return to main activity
        }
    }

    private fun loadSavedPreferences() {
        val isGyroEnabled = sharedPreferences.getBoolean(KEY_GYRO_ENABLED, false)
        gyroToggle.isChecked = isGyroEnabled
        updateGyroToggleVisualFeedback(isGyroEnabled)
    }

    private fun updateGyroToggleVisualFeedback(isChecked: Boolean) {
        if (isChecked) {
            gyroToggle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            gyroToggle.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
}