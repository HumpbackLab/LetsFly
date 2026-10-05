package com.humpbacklab.letsfly

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

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
    private lateinit var showValuesToggle: Switch
    private lateinit var orientationRadioGroup: RadioGroup
    private lateinit var singleHandRadioButton: RadioButton
    private lateinit var dualHandRadioButton: RadioButton
    private lateinit var dualHandAirplaneRadioButton: RadioButton
    private lateinit var rcCarRadioButton: RadioButton
    private lateinit var physicalJoystickCalibrationButton: Button
    private lateinit var resetPhysicalJoysticksButton: Button
    private lateinit var backButton: Button
    private lateinit var videoChannelSpinner: Spinner
    private lateinit var videoChannelApplyButton: Button
    private lateinit var videoCurrentChannel: TextView
    private lateinit var videoChannelStatus: TextView
    private lateinit var videoWifiSettingsButton: Button
    private lateinit var wifiScanButton: Button
    private lateinit var wifiScanStatus: TextView
    private lateinit var wifiRecommendationButton: Button
    private lateinit var wifiChannelDetailsToggle: TextView
    private lateinit var wifiChannelDetailsContainer: LinearLayout
    private lateinit var wifiChannelUsageContainer: LinearLayout
    private var videoChannelClient: ApfpvChannelClient? = null
    private var videoChannelSessionId = 0
    private var videoWifiReady = false
    private var videoChannelFailed = false
    private var videoPacketVersion: Int? = null
    private var invalidVideoConfigSeen = false
    private var currentVideoChannel: Int? = null
    private var requestedVideoChannel: Int? = null
    private var videoChannelOptions = ApfpvProtocol.WIFI_CHANNELS_2_4_GHZ
    private var videoStatusGeneration = 0
    private var wifiScanRequested = false
    private var wifiScanAvailable = false
    private var wifiAccessPoints = emptyList<WifiChannelAdvisor.AccessPoint>()
    private var wifiRecommendedChannel: Int? = null
    private var wifiChannelDetailsExpanded = false
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!wifiScanRequested || intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            wifiScanRequested = false
            wifiScanButton.isEnabled = true
            showWifiScanResults(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
        }
    }

    private val settingsSections = listOf(
        Triple(R.id.gyroSectionLabel, R.id.gyroControlContainer, R.string.section_gyro_control),
        Triple(R.id.channelRangeLabel, R.id.channelRangeContainer, R.string.section_channel_ranges),
        Triple(R.id.displayValuesLabel, R.id.displayValuesContainer, R.string.section_debug_options),
        Triple(R.id.additionalSettingsLabel, R.id.additionalSettingsContainer, R.string.section_additional_settings),
        Triple(R.id.orientationLabel, R.id.orientationRadioGroup, R.string.section_display_mode),
        Triple(R.id.videoChannelLabel, R.id.videoChannelContainer, R.string.section_video_channel)
    )

    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val PREF_NAME = "rc_controller_prefs"
        private const val KEY_GYRO_ENABLED = "gyro_enabled"
        private const val KEY_GYRO_SENSITIVITY = "gyro_sensitivity"
        private const val KEY_CH1_RANGE = "ch1_range"
        private const val KEY_CH2_RANGE = "ch2_range"
        private const val KEY_CH3_RANGE = "ch3_range"
        private const val KEY_CH4_RANGE = "ch4_range"
        private const val DEFAULT_CH4_RANGE = 50
        private const val KEY_ORIENTATION_MODE = "orientation_mode"
        private const val KEY_SHOW_VALUES = "show_values"
        private const val ORIENTATION_SINGLE_HAND = "single_hand"  // portrait
        private const val ORIENTATION_DUAL_HAND = "dual_hand"     // landscape
        private const val ORIENTATION_DUAL_HAND_AIRPLANE = "dual_hand_airplane"
        private const val ORIENTATION_RC_CAR = "rc_car"
        private const val WIFI_SCAN_PERMISSION_REQUEST = 3107
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        initializeViews()
        setupCollapsibleSections(savedInstanceState)
        setupGyroToggle()
        setupSensitivitySlider()
        setupChannelRangeSliders()
        setupShowValuesToggle()
        setupOrientationSelection()
        setupPhysicalJoystickCalibration()
        setupResetPhysicalJoysticks()
        setupBackButton()
        setupVideoChannelControls()
        setupWifiScanControls()
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
        showValuesToggle = findViewById(R.id.showValuesToggle)
        orientationRadioGroup = findViewById(R.id.orientationRadioGroup)
        singleHandRadioButton = findViewById(R.id.singleHandRadioButton)
        dualHandRadioButton = findViewById(R.id.dualHandRadioButton)
        dualHandAirplaneRadioButton = findViewById(R.id.dualHandAirplaneRadioButton)
        rcCarRadioButton = findViewById(R.id.rcCarRadioButton)
        physicalJoystickCalibrationButton = findViewById(R.id.physicalJoystickCalibrationButton)
        resetPhysicalJoysticksButton = findViewById(R.id.resetPhysicalJoysticksButton)
        backButton = findViewById(R.id.backButton)
        videoChannelSpinner = findViewById(R.id.videoChannelSpinner)
        videoChannelApplyButton = findViewById(R.id.videoChannelApplyButton)
        videoCurrentChannel = findViewById(R.id.videoCurrentChannel)
        videoChannelStatus = findViewById(R.id.videoChannelStatus)
        videoWifiSettingsButton = findViewById(R.id.videoWifiSettingsButton)
        wifiScanButton = findViewById(R.id.wifiScanButton)
        wifiScanStatus = findViewById(R.id.wifiScanStatus)
        wifiRecommendationButton = findViewById(R.id.wifiRecommendationButton)
        wifiChannelDetailsToggle = findViewById(R.id.wifiChannelDetailsToggle)
        wifiChannelDetailsContainer = findViewById(R.id.wifiChannelDetailsContainer)
        wifiChannelUsageContainer = findViewById(R.id.wifiChannelUsageContainer)
    }

    private fun setupCollapsibleSections(savedInstanceState: Bundle?) {
        for ((labelId, containerId, titleId) in settingsSections) {
            val label = findViewById<TextView>(labelId)
            val container = findViewById<View>(containerId)
            val title = getString(titleId)
            val key = "section_$containerId"
            fun setExpanded(expanded: Boolean) {
                container.visibility = if (expanded) View.VISIBLE else View.GONE
                label.text = "$title ${if (expanded) "▾" else "▸"}"
                label.contentDescription = getString(
                    if (expanded) R.string.section_collapse_description
                    else R.string.section_expand_description,
                    title
                )
                label.isActivated = expanded
            }
            label.isFocusable = true
            label.gravity = Gravity.CENTER_VERTICAL
            label.minimumHeight = (48 * resources.displayMetrics.density).toInt()
            label.setBackgroundResource(android.R.drawable.list_selector_background)
            setExpanded(savedInstanceState?.getBoolean(key) ?: false)
            label.setOnClickListener { setExpanded(container.visibility != View.VISIBLE) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        for ((_, containerId, _) in settingsSections) {
            outState.putBoolean(
                "section_$containerId", findViewById<View>(containerId).visibility == View.VISIBLE
            )
        }
        super.onSaveInstanceState(outState)
    }

    private fun setupVideoChannelControls() {
        showVideoChannelOptions(videoChannelOptions, null)
        videoWifiSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        videoChannelApplyButton.setOnClickListener {
            val selected = videoChannelOptions.getOrNull(videoChannelSpinner.selectedItemPosition)
                ?: return@setOnClickListener
            if (selected == currentVideoChannel) {
                videoChannelStatus.setText(R.string.video_channel_already_current)
                return@setOnClickListener
            }
            if (videoChannelClient?.requestChannel(selected) != true) {
                videoChannelStatus.setText(R.string.video_channel_unavailable)
                return@setOnClickListener
            }
            requestedVideoChannel = selected
            videoChannelApplyButton.isEnabled = false
            videoChannelStatus.text = getString(R.string.video_channel_sending, selected)
            scheduleVideoStatusTimeout(videoChannelSessionId, 10000)
        }
    }

    private fun showVideoChannelOptions(channels: List<Int>, selected: Int?) {
        videoChannelOptions = channels
        val labels = channels.map { channel ->
            val frequency = if (channel <= 13) 2407 + channel * 5 else 5000 + channel * 5
            getString(R.string.video_channel_option, channel, frequency)
        }
        videoChannelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        selected?.let { channel ->
            val index = channels.indexOf(channel)
            if (index >= 0) videoChannelSpinner.setSelection(index)
        }
    }

    private fun setupWifiScanControls() {
        wifiScanButton.setOnClickListener { refreshWifiScan() }
        wifiRecommendationButton.setOnClickListener {
            wifiRecommendedChannel?.let { selectSuggestedChannel(it) }
        }
        wifiChannelDetailsToggle.setOnClickListener {
            wifiChannelDetailsExpanded = !wifiChannelDetailsExpanded
            updateWifiChannelDetailsVisibility()
        }
    }

    private fun updateWifiChannelDetailsVisibility() {
        wifiChannelDetailsToggle.setText(
            if (wifiChannelDetailsExpanded) R.string.video_channel_details_hide
            else R.string.video_channel_details_show
        )
        wifiChannelDetailsContainer.visibility =
            if (wifiChannelDetailsExpanded && wifiChannelDetailsToggle.visibility == View.VISIBLE)
                View.VISIBLE else View.GONE
    }

    private fun clearWifiScanResults() {
        wifiScanAvailable = false
        wifiAccessPoints = emptyList()
        wifiRecommendedChannel = null
        wifiRecommendationButton.visibility = View.GONE
        wifiChannelDetailsToggle.visibility = View.GONE
        wifiChannelDetailsExpanded = false
        updateWifiChannelDetailsVisibility()
        wifiChannelUsageContainer.removeAllViews()
    }

    private fun refreshWifiScan() {
        clearWifiScanResults()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(permissions, WIFI_SCAN_PERMISSION_REQUEST)
            return
        }
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            location.isLocationEnabled
        } else {
            Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }
        if (!locationEnabled) {
            wifiScanStatus.setText(R.string.video_channel_scan_location)
            return
        }
        wifiScanStatus.setText(R.string.video_channel_scan_scanning)
        wifiScanButton.isEnabled = false
        wifiScanRequested = true
        try {
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (!started) {
                wifiScanRequested = false
                wifiScanButton.isEnabled = true
                showWifiScanResults(false)
            } else {
                wifiScanStatus.postDelayed({
                    if (wifiScanRequested) {
                        wifiScanRequested = false
                        wifiScanButton.isEnabled = true
                        showWifiScanResults(false)
                    }
                }, 7000)
            }
        } catch (_: SecurityException) {
            wifiScanRequested = false
            wifiScanButton.isEnabled = true
            wifiScanStatus.setText(R.string.video_channel_scan_error)
        }
    }

    @Suppress("DEPRECATION")
    private fun showWifiScanResults(updated: Boolean) {
        try {
            // Exclude our own hotspot only after APFPV has confirmed this Wi-Fi is the camera.
            val ownBssid = if (currentVideoChannel != null) wifiManager.connectionInfo?.bssid
                else null
            val nowMicros = SystemClock.elapsedRealtimeNanos() / 1000
            val availableResults = wifiManager.scanResults.asSequence()
                .filter { it.frequency in 2412..2472 || it.frequency in 5180..5825 }
                .filter { !it.BSSID.equals(ownBssid, ignoreCase = true) }
                .distinctBy { it.BSSID }
                .toList()
            val results = availableResults
                .filter { it.timestamp <= 0L ||
                    nowMicros - it.timestamp in 0..120_000_000L }
            if (results.isEmpty() && (availableResults.isNotEmpty() || !updated)) {
                clearWifiScanResults()
                wifiScanStatus.setText(
                    if (availableResults.isEmpty()) R.string.video_channel_scan_unavailable
                    else R.string.video_channel_scan_stale
                )
                return
            }
            wifiAccessPoints = results.map { result ->
                val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    when (result.channelWidth) {
                        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                        ScanResult.CHANNEL_WIDTH_80MHZ,
                        ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
                        ScanResult.CHANNEL_WIDTH_160MHZ -> 160
                        else -> 20
                    }
                } else 20
                val center = if (width > 20 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    result.centerFreq0 > 0
                ) result.centerFreq0 else result.frequency
                WifiChannelAdvisor.AccessPoint(result.frequency, result.level, width, center)
            }
            wifiScanAvailable = true
            wifiScanStatus.text = when {
                results.isEmpty() -> getString(R.string.video_channel_scan_empty)
                updated -> getString(R.string.video_channel_scan_complete, results.size)
                else -> getString(R.string.video_channel_scan_cached, results.size)
            }
            renderWifiChannelAnalysis()
        } catch (_: SecurityException) {
            clearWifiScanResults()
            wifiScanStatus.setText(R.string.video_channel_scan_error)
        }
    }

    private fun renderWifiChannelAnalysis() {
        if (!wifiScanAvailable) return
        val assessment = WifiChannelAdvisor.assess(
            videoChannelOptions, wifiAccessPoints, currentVideoChannel
        )
        val recommendation = assessment.recommendedChannel
        wifiRecommendedChannel = recommendation
        wifiRecommendationButton.visibility = if (recommendation == null) View.GONE else View.VISIBLE
        wifiChannelDetailsToggle.visibility =
            if (wifiAccessPoints.isEmpty()) View.GONE else View.VISIBLE
        updateWifiChannelDetailsVisibility()
        if (recommendation != null) {
            wifiRecommendationButton.text = getString(
                if (recommendation == currentVideoChannel)
                    R.string.video_channel_recommendation_current
                else R.string.video_channel_recommendation,
                recommendation
            )
            wifiRecommendationButton.isEnabled = currentVideoChannel != null &&
                recommendation != currentVideoChannel
        }
        wifiChannelUsageContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val maxInterference = assessment.loads.maxOfOrNull { it.interference } ?: 0.0
        for (load in assessment.loads) {
            val strongestSignal = load.strongestSignalDbm?.let {
                getString(R.string.video_channel_usage_signal, it)
            } ?: getString(R.string.video_channel_usage_no_signal)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (40 * density).roundToInt()
                isEnabled = currentVideoChannel != null
                isFocusable = true
                contentDescription = getString(
                    R.string.video_channel_usage_row,
                    load.channel, load.nearbyCount, strongestSignal
                )
                setOnClickListener { selectSuggestedChannel(load.channel) }
            }
            row.addView(TextView(this).apply {
                text = getString(R.string.video_channel_usage_channel, load.channel)
            }, LinearLayout.LayoutParams((75 * density).roundToInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = if (maxInterference == 0.0) 0 else
                    (load.interference / maxInterference * 100).roundToInt()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, (12 * density).roundToInt(), 1f))
            row.addView(TextView(this).apply {
                text = getString(R.string.video_channel_usage_count,
                    load.nearbyCount, strongestSignal)
                gravity = Gravity.END
                setTextSize(12f)
            }, LinearLayout.LayoutParams((82 * density).roundToInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT))
            wifiChannelUsageContainer.addView(row)
        }
    }

    private fun selectSuggestedChannel(channel: Int) {
        val index = videoChannelOptions.indexOf(channel)
        if (index < 0 || currentVideoChannel == null) return
        videoChannelSpinner.setSelection(index)
        wifiScanStatus.text = getString(R.string.video_channel_recommendation_selected, channel)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != WIFI_SCAN_PERMISSION_REQUEST) return
        val fineIndex = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (fineIndex >= 0 && grantResults.getOrNull(fineIndex) ==
            PackageManager.PERMISSION_GRANTED
        ) refreshWifiScan()
        else wifiScanStatus.setText(R.string.video_channel_scan_permission)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onStop() {
        wifiScanRequested = false
        wifiScanButton.isEnabled = true
        unregisterReceiver(wifiScanReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        clearWifiScanResults()
        wifiScanStatus.setText(R.string.video_channel_scan_prompt)
        currentVideoChannel = null
        requestedVideoChannel = null
        videoWifiReady = false
        videoChannelFailed = false
        videoPacketVersion = null
        invalidVideoConfigSeen = false
        ++videoStatusGeneration
        videoCurrentChannel.setText(R.string.video_channel_connecting)
        videoChannelStatus.setText(R.string.video_channel_instructions)
        videoWifiSettingsButton.visibility = View.GONE
        videoChannelApplyButton.isEnabled = false
        val sessionId = ++videoChannelSessionId
        videoChannelClient = ApfpvChannelClient(
            context = applicationContext,
            onWifiReady = { runOnUiThread {
                if (sessionId == videoChannelSessionId) {
                    videoWifiReady = true
                    videoChannelFailed = false
                    videoPacketVersion = null
                    invalidVideoConfigSeen = false
                    videoWifiSettingsButton.visibility = View.GONE
                    videoChannelStatus.setText(
                        if (requestedVideoChannel == null) R.string.video_channel_waiting
                        else R.string.video_channel_wait_reconnect
                    )
                    scheduleVideoStatusTimeout(sessionId, 5000)
                }
            } },
            onNetworkUnavailable = { runOnUiThread {
                if (sessionId == videoChannelSessionId) {
                    videoWifiReady = false
                    currentVideoChannel = null
                    videoCurrentChannel.setText(R.string.video_channel_not_detected)
                    videoChannelApplyButton.isEnabled = false
                    videoChannelStatus.setText(
                        if (requestedVideoChannel == null) R.string.video_channel_unavailable
                        else R.string.video_channel_wait_reconnect
                    )
                    videoWifiSettingsButton.visibility = View.VISIBLE
                }
            } },
            onPacketVersion = { version -> runOnUiThread {
                if (sessionId == videoChannelSessionId) {
                    videoPacketVersion = version
                    if (currentVideoChannel == null && requestedVideoChannel == null) {
                        videoChannelStatus.text = getString(R.string.video_channel_packet_seen, version)
                    }
                }
            } },
            onInvalidConfig = { runOnUiThread {
                if (sessionId == videoChannelSessionId) invalidVideoConfigSeen = true
            } },
            onChannel = { channel -> runOnUiThread {
                if (sessionId != videoChannelSessionId) return@runOnUiThread
                val previousChannel = currentVideoChannel
                currentVideoChannel = channel
                videoWifiSettingsButton.visibility = View.GONE
                videoCurrentChannel.text = getString(R.string.video_channel_current, channel)
                val channels = if (channel <= 13) ApfpvProtocol.WIFI_CHANNELS_2_4_GHZ
                    else ApfpvProtocol.WIFI_CHANNELS_5_GHZ
                if (channels != videoChannelOptions) showVideoChannelOptions(channels, channel)
                else if (previousChannel == null && requestedVideoChannel == null) {
                    videoChannelSpinner.setSelection(videoChannelOptions.indexOf(channel))
                }
                if (requestedVideoChannel == channel) {
                    requestedVideoChannel = null
                    videoChannelSpinner.setSelection(videoChannelOptions.indexOf(channel))
                    videoChannelStatus.text = getString(R.string.video_channel_confirmed, channel)
                } else if (requestedVideoChannel != null && previousChannel == null) {
                    val requested = requestedVideoChannel
                    requestedVideoChannel = null
                    videoChannelStatus.text = getString(
                        R.string.video_channel_not_changed, channel, requested
                    )
                } else if (requestedVideoChannel == null && previousChannel == null) {
                    videoChannelStatus.setText(R.string.video_channel_instructions)
                }
                videoChannelApplyButton.isEnabled = requestedVideoChannel == null
                if (wifiScanAvailable) renderWifiChannelAnalysis()
            } },
            onCommandSent = { channel -> runOnUiThread {
                if (sessionId == videoChannelSessionId && requestedVideoChannel == channel) {
                    videoChannelStatus.text = getString(R.string.video_channel_sent, channel)
                }
            } },
            onError = { wifiReady -> runOnUiThread {
                if (sessionId == videoChannelSessionId) {
                    videoChannelFailed = true
                    if (requestedVideoChannel == null) {
                        videoChannelStatus.setText(
                            if (wifiReady) R.string.video_channel_socket_error
                            else R.string.video_channel_unavailable
                        )
                    }
                    videoChannelApplyButton.isEnabled = false
                }
            } }
        ).also { it.start() }
    }

    private fun scheduleVideoStatusTimeout(sessionId: Int, delayMs: Long) {
        val generation = ++videoStatusGeneration
        videoCurrentChannel.postDelayed({
            if (sessionId != videoChannelSessionId || generation != videoStatusGeneration ||
                videoChannelFailed
            ) return@postDelayed
            if (requestedVideoChannel != null) {
                videoChannelStatus.setText(R.string.video_channel_wait_reconnect)
                videoWifiSettingsButton.visibility = View.VISIBLE
            } else if (currentVideoChannel != null) {
                return@postDelayed
            } else if (videoPacketVersion != null) {
                videoChannelStatus.text = getString(
                    if (invalidVideoConfigSeen) R.string.video_channel_invalid_config
                    else R.string.video_channel_no_config,
                    videoPacketVersion
                )
            } else {
                videoChannelStatus.setText(
                    if (videoWifiReady) R.string.video_channel_no_response
                    else R.string.video_channel_unavailable
                )
                videoWifiSettingsButton.visibility = View.VISIBLE
            }
        }, delayMs)
    }

    override fun onPause() {
        ++videoChannelSessionId
        ++videoStatusGeneration
        videoChannelClient?.stop()
        videoChannelClient = null
        super.onPause()
    }

    private fun setupGyroToggle() {
        gyroToggle.setOnCheckedChangeListener { _, isChecked ->
            updateGyroToggleVisualFeedback(isChecked)

            // Save the setting to shared preferences
            with(sharedPreferences.edit()) {
                putBoolean(KEY_GYRO_ENABLED, isChecked)
                apply()
            }
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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // CH4 Range Slider
        ch4RangeSlider.max = 80  // From 20 to 100 (80 steps)
        ch4RangeSlider.progress = sharedPreferences.getInt(KEY_CH4_RANGE, DEFAULT_CH4_RANGE) - 20
        ch4RangeValue.text = "${sharedPreferences.getInt(KEY_CH4_RANGE, DEFAULT_CH4_RANGE)}%"

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
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupShowValuesToggle() {
        // Set the initial state of the toggle based on saved preference
        showValuesToggle.isChecked = sharedPreferences.getBoolean(KEY_SHOW_VALUES, false)

        showValuesToggle.setOnCheckedChangeListener { _, isChecked ->
            // Save the setting to shared preferences
            with(sharedPreferences.edit()) {
                putBoolean(KEY_SHOW_VALUES, isChecked)
                apply()
            }
        }
    }

    private fun setupOrientationSelection() {
        // Set up the orientation radio group
        val selectedOrientation = sharedPreferences.getString(KEY_ORIENTATION_MODE, ORIENTATION_SINGLE_HAND)

        when (selectedOrientation) {
            ORIENTATION_DUAL_HAND -> dualHandRadioButton.isChecked = true
            ORIENTATION_DUAL_HAND_AIRPLANE -> dualHandAirplaneRadioButton.isChecked = true
            ORIENTATION_RC_CAR -> rcCarRadioButton.isChecked = true
            else -> singleHandRadioButton.isChecked = true  // Default to single hand
        }

        orientationRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val orientation = when (checkedId) {
                R.id.dualHandRadioButton -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ORIENTATION_DUAL_HAND
                }
                R.id.dualHandAirplaneRadioButton -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ORIENTATION_DUAL_HAND_AIRPLANE
                }
                R.id.rcCarRadioButton -> {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ORIENTATION_RC_CAR
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
        }
    }

    private fun setupBackButton() {
        backButton.setOnClickListener {
            // Before closing the settings activity, ensure the main activity's orientation is updated
            val orientationMode = when (orientationRadioGroup.checkedRadioButtonId) {
                R.id.dualHandRadioButton -> ORIENTATION_DUAL_HAND
                R.id.dualHandAirplaneRadioButton -> ORIENTATION_DUAL_HAND_AIRPLANE
                R.id.rcCarRadioButton -> ORIENTATION_RC_CAR
                else -> ORIENTATION_SINGLE_HAND
            }

            // Save the setting to shared preferences
            with(sharedPreferences.edit()) {
                putString(KEY_ORIENTATION_MODE, orientationMode)
                apply()
            }

            finish() // Close the settings activity and return to main activity
        }
    }

    private fun setupPhysicalJoystickCalibration() {
        physicalJoystickCalibrationButton.setOnClickListener {
            // Two clip-on sticks require the landscape layout. Returning to MainActivity
            // starts a full-screen capture before either joystick view is repositioned.
            sharedPreferences.edit()
                .putBoolean(MainActivity.KEY_PHYSICAL_CALIBRATION_REQUESTED, true)
                .putBoolean(KEY_GYRO_ENABLED, false)
                .putString(KEY_ORIENTATION_MODE, ORIENTATION_DUAL_HAND)
                .apply()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            finish()
        }
    }

    private fun setupResetPhysicalJoysticks() {
        resetPhysicalJoysticksButton.setOnClickListener {
            sharedPreferences.edit()
                .remove("physical_joystick_calibrated")
                .remove("physical_joystick_calibration_requested")
                .remove("physical_left_min_x")
                .remove("physical_left_max_x")
                .remove("physical_left_min_y")
                .remove("physical_left_max_y")
                .remove("physical_right_min_x")
                .remove("physical_right_max_x")
                .remove("physical_right_min_y")
                .remove("physical_right_max_y")
                .apply()
            Toast.makeText(this, R.string.physical_joystick_reset_complete, Toast.LENGTH_SHORT).show()
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
