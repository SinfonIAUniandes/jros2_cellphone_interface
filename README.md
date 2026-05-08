# jros2 Cellphone Sensor Bridge

Android (Jetpack Compose) application that exports live phone sensor telemetry to ROS 2 using standard `sensor_msgs`, `std_msgs` message types, and custom `mobile_sensor_msgs` definitions over the IHMC `jros2-android` stack (Fast DDS + JavaCPP JNI).

---

## Table of Contents
1. [What This Project Does](#what-this-project-does)
2. [Latest Release](#latest-release)
3. [Sensor Mapping & File Registry](#sensor-mapping--file-registry)
4. [Prerequisites & Build Instructions](#prerequisites--build-instructions)
5. [Permissions Requirements](#permissions-requirements)
6. [Architecture & How It Works](#architecture--how-it-works)
7. [Project Directory Layout](#project-directory-layout)
8. [Step-by-Step Guide: Adding a New Sensor](#step-by-step-guide-adding-a-new-sensor)
9. [Verification from ROS 2](#verification-from-ros-2)

---

## What This Project Does

- Initializes a fully compliant ROS 2 Node (`phone_sensor_node`) directly on your physical Android smartphone.
- Streams real-time, high-frequency telemetry from **11+ hardware/software sensors and input devices** on the phone to external ROS 2 environments.
- Features a premium, reactive, dark-themed sensor dashboard UI using Jetpack Compose.
- Employs an Android multicast Wi-Fi lock to ensure robust, real-time discovery of DDS participants.

---

## Latest Release

The latest APK release is **v1.1.0**.

- Release page: [v.1.1.0](https://github.com/SinfonIAUniandes/jros2_cellphone_interface/releases/tag/v.1.1.0)
- Direct APK download: [app-debug.apk](https://github.com/SinfonIAUniandes/jros2_cellphone_interface/releases/download/v.1.1.0/app-debug.apk)

### Installation Notes

- Download the APK from the direct link above.
- Install it with `adb install app-debug.apk`.
- Grant Camera and Network permissions when prompted.

### Network Notes

- Streaming raw `sensor_msgs/Image` over Wi-Fi uses significant bandwidth.
- A dedicated 5 GHz Wi-Fi subnet is recommended when possible.
- If DDS discovery drops or the stream lags, lower the camera resolution in the app settings.

### Prerequisites

- Android 12 or newer.
- Wi-Fi with multicast support enabled.
- ROS 2 Jazzy compatibility.
- Part of the jros2-android ecosystem.

---

## Sensor Mapping & File Registry

The following table documents each supported sensor, its default topic name, its message definition, target publish rate, and the exact `.kt` file where the logic resides:

| Sensor Type | ROS 2 Topic Name | Message Type | Target Rate | Implementing File Path |
| :--- | :--- | :--- | :--- | :--- |
| **IMU** | `/phone/imu` | `sensor_msgs/Imu` | 50 Hz | `ImuSensor.kt` |
| **Magnetometer** | `/phone/magnetic_field` | `sensor_msgs/MagneticField` | 10 Hz | `MagnetometerSensor.kt` |
| **Barometer** | `/phone/pressure` | `sensor_msgs/FluidPressure` | 5 Hz | `PressureSensor.kt` |
| **Ambient Light** | `/phone/illuminance` | `sensor_msgs/Illuminance` | 5 Hz | `LightSensor.kt` |
| **GPS Fix** | `/phone/gps` | `sensor_msgs/NavSatFix` | 1 Hz | `GpsSensor.kt` |
| **Proximity** | `/phone/proximity` | `std_msgs/Float32` | 5 Hz | `ProximitySensor.kt` |
| **Battery State** | `/phone/battery` | `sensor_msgs/BatteryState` | 0.2 Hz | `BatterySensor.kt` |
| **Touch Screen** | `/phone/touch` | `mobile_sensor_msgs/TouchArray` | 30 Hz | `TouchSensor.kt` |
| **Biometric Auth** | `/phone/biometric` | `mobile_sensor_msgs/BiometricAuth` | Event-driven | `BiometricSensor.kt` |
| **Camera** | `/phone/camera/front/image` and `/phone/camera/back/image` | `sensor_msgs/Image` | Streaming | `DualCameraSensor.kt` |

The camera bridge supports front, back, or dual streaming depending on the device and the selected camera mode. Related runtime settings include camera mode, rotation, color, and resolution.

---

## Prerequisites & Build Instructions

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

### 3) Install via ADB
Push the built binary to your connected physical Android device:
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Permissions Requirements

The following permissions are registered inside [`AndroidManifest.xml`](file:///c:/Users/David.DESKTOP-A6NC9IE/Desktop/Cuevas/WearROS2/jros2_cellphone_interface/app/src/main/AndroidManifest.xml) and handled automatically:
*   `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`: Standard DDS local communication over UDP.
*   `CHANGE_WIFI_MULTICAST_STATE`: Enables Wi-Fi Multicast lock required by Fast DDS for network discovery.
*   `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`: Required for GPS coordinate retrieval.
*   `CAMERA`: Required for front/back camera streaming and `sensor_msgs/Image` publishing.
*   `HIGH_SAMPLING_RATE_SENSORS`: Enables high-frequency (>20Hz) sensor updates on Android 12+.

---

## Architecture & How It Works

This project is built using a highly decoupled, modular architecture designed for high-performance and easy maintainability:

```
                  ┌──────────────────────────────────────────────┐
                  │                 MainActivity                 │
                  │              (Jetpack Compose)               │
                  └──────────────────────┬───────────────────────┘
                                         │ Observes State & Toggles
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │                 SensorBridge                 │
                  │             (Central Coordinator)            │
                  └──────────────────────┬───────────────────────┘
                                         │ Manages Lifecycle
                                         ▼
                 ┌────────────────────────────────────────────────┐
                 │          List<PhoneSensor> Interfaces          │
                 │   (Imu, Magnetometer, Battery, GPS, etc.)      │
                 └───────────────────────┬────────────────────────┘
                                         │ Fills & Publishes Messages
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │            us.ihmc:jros2-android             │
                  │             (DDS Native Layer)               │
                  └──────────────────────────────────────────────┘
```

1. **The Core Interface (`PhoneSensor.kt`)**: 
   Every sensor in the application is a self-contained module that implements the `PhoneSensor` interface. This class defines the metadata (UI color, icon, name, topic name) as well as the lifecycle hooks (`start` and `stop`), and exposes local UI state flow flows (`enabled`, `messageCount`, `displayValue`).
   
2. **The Coordinator (`SensorBridge.kt`)**: 
   A central registry that instantiates the ROS 2 node, retains the list of active `PhoneSensor` implementations, and delegates startup/shutdown events to each individual sensor. It also exposes diagnostic logs to the UI.

3. **Dynamic UI Rendering (`MainActivity.kt`)**: 
   Instead of hardcoded panels, the Compose UI reads the list of sensors from `SensorBridge` and dynamically generates elegant, individual sensor toggle cards using a `LazyVerticalGrid`. Any new sensor added to the registry is automatically drawn in the UI.

4. **Native Bindings Layer (`jros2-android`)**: 
   Interfaces with Fast DDS through native `.so` shared objects compiled via Android NDK/CMake.

---

## Project Directory Layout

```text
jros2_cellphone_interface/
├── app/
│   ├── src/main/
│   │   ├── java/com/jros2/cellphone_interface/
│   │   │   ├── MainActivity.kt         # Entry Activity and main Jetpack Compose UI
│   │   │   ├── SensorBridge.kt         # Central coordinator managing the ROS 2 lifecycle
│   │   │   ├── ui/theme/
│   │   │   │   ├── Color.kt            # Palette specifying gorgeous dark UI tones
│   │   │   │   └── Theme.kt            # Sets up the custom dark color themes
│   │   │   └── sensors/
│   │   │       ├── PhoneSensor.kt      # General interface all sensors must implement
│   │   │       ├── ImuSensor.kt        # Combines accelerometer, gyro & rot vector
│   │   │       ├── MagnetometerSensor.kt# Streams raw magnetic values
│   │   │       ├── PressureSensor.kt   # Barometric pressure to FluidPressure
│   │   │       ├── LightSensor.kt      # Illuminance measurements in Lux
│   │   │       ├── GpsSensor.kt        # Location coordinate calculations
│   │   │       ├── ProximitySensor.kt  # Proximity threshold alerts
│   │   │       ├── BatterySensor.kt    # Polling updates for battery charge & voltage
│   │   │       ├── DualCameraSensor.kt # Front/back camera streaming as sensor_msgs/Image
│   │   │       ├── TouchSensor.kt      # Multi-touch screen input events
│   │   │       └── BiometricSensor.kt  # Biometric authentication state & callbacks
│   │   └── AndroidManifest.xml         # Hardware uses-permissions specifications
│   └── build.gradle.kts                # Application dependencies config
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Step-by-Step Guide: Adding a New Sensor

Thanks to the decoupled design, adding a brand new sensor takes only two steps:

### Step 1: Create your Sensor Class
Create a new file in the `sensors/` directory (e.g., `AmbientTempSensor.kt`) that implements the `PhoneSensor` interface.

Here is a minimalist template for an ambient temperature sensor:
```kotlin
package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import std_msgs.Float32 // Or your choice of ROS 2 message definition

class AmbientTempSensor : PhoneSensor {
    override val id = "temperature"
    override val name = "Temperature"
    override val icon = "🌡️"
    override val color = Color(0xFFFF5722) // Choose a distinctive color
    override val topicName = "/phone/ambient_temp"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("0.0 °C")
    override val displayValue: StateFlow<String> = _value

    private val msg = Float32()
    private var publisher: ROS2Publisher<Float32>? = null
    private var sensorManager: SensorManager? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!enabled.value) return
            
            // Populate and publish the ROS 2 Message
            msg.setData(event.values[0])
            publisher?.publish(msg)

            // Update local state flows
            _count.value++
            _value.value = "%.1f °C".format(event.values[0])
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, Float32::class.java))
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun stop() {
        sensorManager?.unregisterListener(listener)
        publisher = null
    }
}
```

### Step 2: Register it in the Central Registry
Open [`SensorBridge.kt`](file:///c:/Users/David.DESKTOP-A6NC9IE/Desktop/Cuevas/WearROS2/jros2_cellphone_interface/app/src/main/java/com/jros2/cellphone_interface/SensorBridge.kt) and instantiate your sensor inside the `sensors` list:

```kotlin
val sensors = listOf(
    ImuSensor(),
    MagnetometerSensor(),
    PressureSensor(),
    LightSensor(),
    GpsSensor(),
    ProximitySensor(),
    BatterySensor(),
    AmbientTempSensor() // Just append it here!
)
```

The system will **automatically initialize the publishers**, register listeners, and **create a gorgeous new card in your Compose dashboard grid!**

---

## Verification from ROS 2

Once your app is compiled, running, and the bridge is active (LIVE status), verify it from a machine on the same Wi-Fi subnet with:

```bash
# Verify your machine discovers the smartphone node topics
ros2 topic list

# Observe live IMU stream updates
ros2 topic echo /phone/imu

# Check frequency rate (should be close to ~50 Hz)
ros2 topic hz /phone/imu

# Inspect GPS status
ros2 topic echo /phone/gps
```
