# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android application for controlling RC aircraft and other devices using the CRSF (Crossfire) protocol. The app provides intuitive joystick controls, gyroscope-based flight control, and configurable channel switches for a comprehensive RC experience.

## Architecture

### Core Components
- **MainActivity.kt**: Main application logic, handles sensors, joystick input, and serial communication
- **Joystick.kt**: Custom joystick view with configurable properties and touch handling
- **CRSFData.kt**: Implementation of CRSF protocol for communication with RC hardware
- **ch34x module**: USB-to-serial driver for CH34x chips

### Channel Mapping
- CH1: Roll (left joystick X-axis)
- CH2: Pitch (left joystick Y-axis)
- CH3: Throttle (right joystick Y-axis)
- CH4: Yaw (right joystick X-axis)
- CH5: Arm/Disarm
- CH6/CH7/CH8: Three-position switches

### Key Features
- Dual joystick control with customizable sensitivity
- Gyroscope flight mode using phone's sensors
- Three-position switches with color-coded visual feedback
- Portrait and landscape orientation support
- Direct USB communication via CH34x driver

## Development Commands

### Building the Project
```bash
# Build the project
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

### Running Tests
```bash
# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest
```

### Common Development Tasks
- Build and install: `./gradlew installDebug`
- Clean build: `./gradlew clean`
- Check code quality: `./gradlew lint`

## Known Issues

- The `openUartDevice()` function in MainActivity contains blocking loops that freeze the UI when clicking the Connect button. The problematic sections use `sleep(10)` in loops and need to be moved to background threads.
- Sensors register with `SENSOR_DELAY_FASTEST` which may consume more battery than needed; consider using `SENSOR_DELAY_NORMAL` for less frequent updates.

## Important Files

- `/app/src/main/java/com/example/myapplication/MainActivity.kt` - Main application logic
- `/app/src/main/java/Joystick.kt` - Custom joystick implementation
- `/app/src/main/java/CRSFData.kt` - CRSF protocol implementation
- `/app/src/main/res/layout-port/activity_main.xml` - Portrait layout
- `/app/src/main/res/layout-land/activity_main.xml` - Landscape layout

## Dependencies

- Android SDK (compileSdk 33, minSdk 21)
- androidx.core:core-ktx:1.3.2
- androidx.appcompat:appcompat:1.2.0
- com.google.android.material:material:1.3.0
- androidx.constraintlayout:constraintlayout:2.0.4
- CH34x USB driver module