# jros2 Cellphone Sensor Bridge

Android (Jetpack Compose) application that exports phone sensor data to ROS 2 using standard `sensor_msgs` message types over the IHMC `jros2-android` stack (Fast DDS + JavaCPP JNI).

## What This Project Does

- Creates an Android ROS 2 node (`phone_sensor_node`).
- Publishes **7 hardware sensors** to standard ROS 2 topics.
- Dark-themed sensor dashboard with per-sensor toggles and live readings.
- Keeps DDS discovery working on Android Wi-Fi by acquiring a multicast lock.

## Published Topics

| Android Sensor | ROS 2 Topic | Message Type | Rate |
|---|---|---|---|
| Accelerometer + Gyroscope + Rotation Vector | `/phone/imu` | `sensor_msgs/Imu` | 50 Hz |
| Magnetometer | `/phone/magnetic_field` | `sensor_msgs/MagneticField` | 10 Hz |
| Barometer (Pressure) | `/phone/pressure` | `sensor_msgs/FluidPressure` | 5 Hz |
| Ambient Light | `/phone/illuminance` | `sensor_msgs/Illuminance` | 5 Hz |
| GPS Location | `/phone/gps` | `sensor_msgs/NavSatFix` | 1 Hz |
| Proximity | `/phone/proximity` | `std_msgs/Float32` | 5 Hz |
| Battery | `/phone/battery` | `sensor_msgs/BatteryState` | 0.2 Hz |

## Architecture

### High-level Layers

1. **UI Layer** (Compose)
   - `MainActivity` hosts the sensor dashboard with a master start/stop toggle.
   - Individual sensor cards show name, latest value, message count, and enable/disable switch.
   - Log console at bottom shows bridge status messages.

2. **Sensor Bridge Layer** (`SensorBridge.kt`)
   - Central coordinator that owns the `ROS2Node` and all 7 publishers.
   - Registers Android `SensorEventListener`s for IMU, magnetometer, barometer, light, and proximity.
   - Uses `LocationManager` for GPS and `BatteryManager` for battery state.
   - Throttles each sensor to its configured publish rate.
   - Fills ROS 2 messages with proper headers (timestamp, frame_id) and publishes.
   - Exposes observable `StateFlow` for the UI to display live values.

3. **ROS 2 / JNI Layer**
   - `jros2-android` provides JavaCPP bindings (`fastddsjava`) and native libs:
     - `libjnifastddsjava.so`
     - `libfastdds.so`
     - `libfastcdr.so`

4. **Network Layer**
   - DDS discovery and traffic over Wi-Fi/UDP.
   - Android multicast lock is required for discovery reliability.

### Unit Conversions

| Sensor | Android Unit | ROS 2 Unit | Conversion |
|---|---|---|---|
| Magnetometer | µT | Tesla | × 10⁻⁶ |
| Barometer | hPa | Pascal | × 100 |
| Battery Voltage | mV | V | ÷ 1000 |
| Battery Temperature | 0.1°C | °C | ÷ 10 |

## Repository Layout

```text
jros2_cellphone_interface/
  app/
    src/main/java/com/jros2/cellphone_interface/
      MainActivity.kt         # Compose UI dashboard
      SensorBridge.kt          # Sensor ↔ ROS 2 bridge
      ui/theme/                # Dark color scheme
    src/main/AndroidManifest.xml
    build.gradle.kts
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
```

## Prerequisites

- JDK 17 (required by AGP/Gradle in this project).
- Android SDK platform used by this app (`compileSdk = 36`, `minSdk = 33`).
- Local sibling checkout of `jros2` (same parent workspace).

## Build Instructions

This application relies heavily on the `us.ihmc:jros2-android` AAR dependency, which provides the Fast-DDS native JNI libraries. 

### 1) Compile and Publish `jros2`

Before building this app, you must compile the native C++ libraries and publish the Android AAR to your local Maven repository. 

**Please refer to the "Compiling from Source" section in the jros2 for detailed instructions on compiling the native layer.**

Once compiled, ensure the Android artifact is published:
```powershell
# Inside the jros2/android directory
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew -p . publishReleasePublicationToMavenLocal
```

### 2) Build this Android App

Once `us.ihmc:jros2-android` is safely in your `mavenLocal()`, you can build the application:

```powershell
cd ..\jros2_cellphone_interface
.\gradlew clean assembleDebug
```

### 3) Install on Phone

Locate the generated APK and push it to your Android device via ADB:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Permissions

The app requests the following permissions:

| Permission | Purpose |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | DDS/ROS 2 networking |
| `CHANGE_WIFI_MULTICAST_STATE` | DDS discovery over Wi-Fi |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS sensor data |
| `HIGH_SAMPLING_RATE_SENSORS` | IMU at high frequency (Android 12+) |

## Verification from ROS 2

Once the app is running with sensors enabled, verify from any ROS 2 machine on the same network:

```bash
# List all published topics
ros2 topic list

# Expected output includes:
#   /phone/imu
#   /phone/magnetic_field
#   /phone/pressure
#   /phone/illuminance
#   /phone/gps
#   /phone/proximity
#   /phone/battery

# Echo live IMU data
ros2 topic echo /phone/imu

# Check publish rate
ros2 topic hz /phone/imu
# Expected: average rate ~50 Hz

# Echo GPS
ros2 topic echo /phone/gps

# Check battery
ros2 topic echo /phone/battery
```

## Main Dependencies

- AndroidX + Compose Material3
- Kotlin Coroutines Android
- `us.ihmc:jros2-android:1.1.6`
- `us.ihmc:log-tools:0.6.5`

## License / Upstream

`jros2` and related native wrappers come from IHMC ecosystem components. Check upstream `jros2` repository licensing and notices for distribution/compliance details.
