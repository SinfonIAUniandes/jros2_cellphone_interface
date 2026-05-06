package com.jros2.cellphone_interface

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import sensor_msgs.Imu
import sensor_msgs.MagneticField
import sensor_msgs.FluidPressure
import sensor_msgs.Illuminance
import sensor_msgs.NavSatFix
import sensor_msgs.NavSatStatus
import sensor_msgs.BatteryState
import std_msgs.Float32
import std_msgs.String as RosString

/**
 * Bridges Android hardware sensors to ROS 2 topics using standard sensor_msgs types.
 *
 * Each sensor can be independently enabled/disabled. The bridge manages its own
 * SensorManager listeners, LocationManager, and BatteryManager polling.
 */
class SensorBridge(private val context: Context) {

    companion object {
        private const val TAG = "SensorBridge"
        private const val FRAME_PREFIX = "phone"
    }

    // ── ROS 2 ────────────────────────────────────────────────────────
    private var rosNode: ROS2Node? = null
    private var imuPublisher: ROS2Publisher<Imu>? = null
    private var magPublisher: ROS2Publisher<MagneticField>? = null
    private var pressurePublisher: ROS2Publisher<FluidPressure>? = null
    private var lightPublisher: ROS2Publisher<Illuminance>? = null
    private var gpsPublisher: ROS2Publisher<NavSatFix>? = null
    private var proximityPublisher: ROS2Publisher<Float32>? = null
    private var batteryPublisher: ROS2Publisher<BatteryState>? = null
    // Diagnostic: simple chatter publisher to test DDS discovery
    private var chatterPublisher: ROS2Publisher<RosString>? = null

    // ── Android Sensors ──────────────────────────────────────────────
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── Coroutine scope for battery polling ──────────────────────────
    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var batteryJob: Job? = null
    private var chatterJob: Job? = null

    // ── Observable state for the UI ──────────────────────────────────

    data class SensorState(
        val isRunning: Boolean = false,
        val imuEnabled: Boolean = true,
        val magEnabled: Boolean = true,
        val pressureEnabled: Boolean = true,
        val lightEnabled: Boolean = true,
        val gpsEnabled: Boolean = true,
        val proximityEnabled: Boolean = true,
        val batteryEnabled: Boolean = true,
        // Latest values
        val imuAccel: FloatArray = FloatArray(3),
        val imuGyro: FloatArray = FloatArray(3),
        val imuOrientation: FloatArray = FloatArray(4), // quaternion x,y,z,w
        val magValues: FloatArray = FloatArray(3),
        val pressureValue: Float = 0f,
        val lightValue: Float = 0f,
        val gpsLat: Double = 0.0,
        val gpsLon: Double = 0.0,
        val gpsAlt: Double = 0.0,
        val proximityValue: Float = 0f,
        val batteryPercent: Float = 0f,
        val batteryCharging: Boolean = false,
        // Message counts
        val imuCount: Long = 0,
        val magCount: Long = 0,
        val pressureCount: Long = 0,
        val lightCount: Long = 0,
        val gpsCount: Long = 0,
        val proximityCount: Long = 0,
        val batteryCount: Long = 0,
        val totalCount: Long = 0,
        // Log
        val logMessages: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    // ── Reusable ROS 2 messages (avoid GC pressure) ──────────────────
    private val imuMsg = Imu()
    private val magMsg = MagneticField()
    private val pressureMsg = FluidPressure()
    private val lightMsg = Illuminance()
    private val gpsMsg = NavSatFix()
    private val proximityMsg = Float32()
    private val batteryMsg = BatteryState()

    // ── Throttle tracking ────────────────────────────────────────────
    private var lastImuPublishNs = 0L
    private var lastMagPublishNs = 0L
    private var lastPressurePublishNs = 0L
    private var lastLightPublishNs = 0L
    private var lastProximityPublishNs = 0L

    // Minimum intervals in nanoseconds
    private val imuIntervalNs = 20_000_000L      // 50 Hz
    private val magIntervalNs = 100_000_000L      // 10 Hz
    private val pressureIntervalNs = 200_000_000L // 5 Hz
    private val lightIntervalNs = 200_000_000L    // 5 Hz
    private val proximityIntervalNs = 200_000_000L// 5 Hz

    // ── Temporary storage for IMU fusion ─────────────────────────────
    private val latestAccel = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private val latestRotation = FloatArray(4) // x, y, z, w
    @Volatile private var hasAccel = false
    @Volatile private var hasGyro = false

    // ── Public API ───────────────────────────────────────────────────

    fun start() {
        if (_state.value.isRunning) return
        log("Starting ROS 2 sensor bridge...")

        bridgeScope.launch {
            try {
                log("Creating ROS 2 node on domain 0...")
                rosNode = ROS2Node("phone_sensor_node", 0)
                log("ROS 2 node created successfully")

                // Create publishers one by one with logging
                imuPublisher = rosNode?.createPublisher(ROS2Topic("/phone/imu", Imu::class.java))
                log("Publisher created: /phone/imu")
                magPublisher = rosNode?.createPublisher(ROS2Topic("/phone/magnetic_field", MagneticField::class.java))
                log("Publisher created: /phone/magnetic_field")
                pressurePublisher = rosNode?.createPublisher(ROS2Topic("/phone/pressure", FluidPressure::class.java))
                log("Publisher created: /phone/pressure")
                lightPublisher = rosNode?.createPublisher(ROS2Topic("/phone/illuminance", Illuminance::class.java))
                log("Publisher created: /phone/illuminance")
                gpsPublisher = rosNode?.createPublisher(ROS2Topic("/phone/gps", NavSatFix::class.java))
                log("Publisher created: /phone/gps")
                proximityPublisher = rosNode?.createPublisher(ROS2Topic("/phone/proximity", Float32::class.java))
                log("Publisher created: /phone/proximity")
                batteryPublisher = rosNode?.createPublisher(ROS2Topic("/phone/battery", BatteryState::class.java))
                log("Publisher created: /phone/battery")

                // Diagnostic chatter publisher (same as old working app)
                chatterPublisher = rosNode?.createPublisher(ROS2Topic("/chatter", RosString::class.java))
                log("Publisher created: /chatter (diagnostic)")

                log("All 8 publishers created")

                // Register sensor listeners on main thread (Android requirement)
                withContext(Dispatchers.Main) {
                    registerSensorListeners()
                    registerGpsListener()
                }

                // Start battery polling
                startBatteryPolling()

                // Start chatter heartbeat (proves DDS discovery works)
                startChatterHeartbeat()

                _state.value = _state.value.copy(isRunning = true)
                log("Sensor bridge active — publishing to /phone/*")
            } catch (e: Exception) {
                log("ERROR starting bridge: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Failed to start", e)
            }
        }
    }

    fun stop() {
        if (!_state.value.isRunning) return
        log("Stopping sensor bridge...")

        sensorManager.unregisterListener(imuListener)
        sensorManager.unregisterListener(magListener)
        sensorManager.unregisterListener(pressureListener)
        sensorManager.unregisterListener(lightListener)
        sensorManager.unregisterListener(proximityListener)

        try { locationManager.removeUpdates(gpsListener) } catch (_: SecurityException) {}

        batteryJob?.cancel()
        chatterJob?.cancel()

        rosNode?.close()
        rosNode = null

        _state.value = _state.value.copy(isRunning = false)
        log("Sensor bridge stopped")
    }

    fun destroy() {
        stop()
        bridgeScope.cancel()
    }

    fun toggleSensor(sensor: String, enabled: Boolean) {
        _state.value = when (sensor) {
            "imu" -> _state.value.copy(imuEnabled = enabled)
            "mag" -> _state.value.copy(magEnabled = enabled)
            "pressure" -> _state.value.copy(pressureEnabled = enabled)
            "light" -> _state.value.copy(lightEnabled = enabled)
            "gps" -> _state.value.copy(gpsEnabled = enabled)
            "proximity" -> _state.value.copy(proximityEnabled = enabled)
            "battery" -> _state.value.copy(batteryEnabled = enabled)
            else -> _state.value
        }
    }

    // ── Private: Sensor Registration ─────────────────────────────────

    private fun registerSensorListeners() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (accel != null) {
            sensorManager.registerListener(imuListener, accel, SensorManager.SENSOR_DELAY_GAME)
            log("Accelerometer registered")
        } else log("⚠ Accelerometer not available")

        if (gyro != null) {
            sensorManager.registerListener(imuListener, gyro, SensorManager.SENSOR_DELAY_GAME)
            log("Gyroscope registered")
        } else log("⚠ Gyroscope not available")

        if (rotation != null) {
            sensorManager.registerListener(imuListener, rotation, SensorManager.SENSOR_DELAY_GAME)
            log("Rotation vector registered")
        } else log("⚠ Rotation vector not available")

        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (mag != null) {
            sensorManager.registerListener(magListener, mag, SensorManager.SENSOR_DELAY_NORMAL)
            log("Magnetometer registered")
        } else log("⚠ Magnetometer not available")

        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressure != null) {
            sensorManager.registerListener(pressureListener, pressure, SensorManager.SENSOR_DELAY_NORMAL)
            log("Barometer registered")
        } else log("⚠ Barometer not available")

        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light != null) {
            sensorManager.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL)
            log("Light sensor registered")
        } else log("⚠ Light sensor not available")

        val prox = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (prox != null) {
            sensorManager.registerListener(proximityListener, prox, SensorManager.SENSOR_DELAY_NORMAL)
            log("Proximity sensor registered")
        } else log("⚠ Proximity sensor not available")
    }

    private fun registerGpsListener() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1 second minimum interval
                    0f,    // 0 meter minimum distance
                    gpsListener
                )
                log("GPS listener registered")
            } else {
                log("⚠ GPS provider not enabled")
            }
        } catch (e: SecurityException) {
            log("⚠ GPS permission not granted")
        }
    }

    private fun startBatteryPolling() {
        batteryJob = bridgeScope.launch {
            while (isActive) {
                if (_state.value.batteryEnabled) {
                    publishBattery()
                }
                delay(5000) // 0.2 Hz
            }
        }
    }

    private fun startChatterHeartbeat() {
        chatterJob = bridgeScope.launch {
            var count = 0
            while (isActive) {
                try {
                    val msg = RosString()
                    msg.data.setLength(0)
                    msg.data.append("Sensor bridge heartbeat: $count")
                    chatterPublisher?.publish(msg)
                    count++
                } catch (e: Exception) {
                    log("ERROR publishing chatter: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    // ── Private: Stamp helper ────────────────────────────────────────

    private fun stampHeader(header: std_msgs.Header, frameId: String) {
        val nowMs = System.currentTimeMillis()
        header.stamp.sec = (nowMs / 1000).toInt()
        header.stamp.nanosec = ((nowMs % 1000) * 1_000_000).toInt()
        header.setFrameId("${FRAME_PREFIX}_$frameId")
    }

    // ── IMU Listener (accel + gyro + rotation) ───────────────────────

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_state.value.imuEnabled) return

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, latestAccel, 0, 3)
                    hasAccel = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    System.arraycopy(event.values, 0, latestGyro, 0, 3)
                    hasGyro = true
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    // Rotation vector: x, y, z, w (or sometimes just x, y, z)
                    if (event.values.size >= 4) {
                        latestRotation[0] = event.values[0] // x
                        latestRotation[1] = event.values[1] // y
                        latestRotation[2] = event.values[2] // z
                        latestRotation[3] = event.values[3] // w
                    } else if (event.values.size >= 3) {
                        latestRotation[0] = event.values[0]
                        latestRotation[1] = event.values[1]
                        latestRotation[2] = event.values[2]
                        // Compute w from the unit quaternion constraint
                        val sumSq = event.values[0] * event.values[0] +
                                event.values[1] * event.values[1] +
                                event.values[2] * event.values[2]
                        latestRotation[3] = if (sumSq < 1f) Math.sqrt((1f - sumSq).toDouble()).toFloat() else 0f
                    }
                }
            }

            // Publish at throttled rate when we have at least accel data
            if (hasAccel) {
                val now = System.nanoTime()
                if (now - lastImuPublishNs >= imuIntervalNs) {
                    lastImuPublishNs = now
                    publishImu()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun publishImu() {
        stampHeader(imuMsg.header, "imu")

        // Orientation quaternion
        imuMsg.orientation.x = latestRotation[0].toDouble()
        imuMsg.orientation.y = latestRotation[1].toDouble()
        imuMsg.orientation.z = latestRotation[2].toDouble()
        imuMsg.orientation.w = latestRotation[3].toDouble()

        // Angular velocity (rad/s)
        imuMsg.angularVelocity.x = latestGyro[0].toDouble()
        imuMsg.angularVelocity.y = latestGyro[1].toDouble()
        imuMsg.angularVelocity.z = latestGyro[2].toDouble()

        // Linear acceleration (m/s^2)
        imuMsg.linearAcceleration.x = latestAccel[0].toDouble()
        imuMsg.linearAcceleration.y = latestAccel[1].toDouble()
        imuMsg.linearAcceleration.z = latestAccel[2].toDouble()

        // Set covariance to unknown if no rotation sensor
        if (latestRotation[0] == 0f && latestRotation[1] == 0f &&
            latestRotation[2] == 0f && latestRotation[3] == 0f) {
            imuMsg.orientationCovariance[0] = -1.0
        }

        imuPublisher?.publish(imuMsg)

        val s = _state.value
        _state.value = s.copy(
            imuAccel = latestAccel.copyOf(),
            imuGyro = latestGyro.copyOf(),
            imuOrientation = latestRotation.copyOf(),
            imuCount = s.imuCount + 1,
            totalCount = s.totalCount + 1
        )
    }

    // ── Magnetometer Listener ────────────────────────────────────────

    private val magListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_state.value.magEnabled) return
            val now = System.nanoTime()
            if (now - lastMagPublishNs < magIntervalNs) return
            lastMagPublishNs = now

            stampHeader(magMsg.header, "magnetometer")

            // Android reports in µT, ROS 2 expects Tesla
            magMsg.magneticField.x = event.values[0].toDouble() * 1e-6
            magMsg.magneticField.y = event.values[1].toDouble() * 1e-6
            magMsg.magneticField.z = event.values[2].toDouble() * 1e-6

            magPublisher?.publish(magMsg)

            val s = _state.value
            _state.value = s.copy(
                magValues = event.values.copyOf(),
                magCount = s.magCount + 1,
                totalCount = s.totalCount + 1
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Pressure Listener ────────────────────────────────────────────

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_state.value.pressureEnabled) return
            val now = System.nanoTime()
            if (now - lastPressurePublishNs < pressureIntervalNs) return
            lastPressurePublishNs = now

            stampHeader(pressureMsg.header, "barometer")

            // Android reports in hPa (mbar), ROS 2 expects Pa
            pressureMsg.fluidPressure = event.values[0].toDouble() * 100.0

            pressurePublisher?.publish(pressureMsg)

            val s = _state.value
            _state.value = s.copy(
                pressureValue = event.values[0],
                pressureCount = s.pressureCount + 1,
                totalCount = s.totalCount + 1
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Light Listener ───────────────────────────────────────────────

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_state.value.lightEnabled) return
            val now = System.nanoTime()
            if (now - lastLightPublishNs < lightIntervalNs) return
            lastLightPublishNs = now

            stampHeader(lightMsg.header, "light_sensor")
            lightMsg.illuminance = event.values[0].toDouble()

            lightPublisher?.publish(lightMsg)

            val s = _state.value
            _state.value = s.copy(
                lightValue = event.values[0],
                lightCount = s.lightCount + 1,
                totalCount = s.totalCount + 1
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Proximity Listener ───────────────────────────────────────────

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!_state.value.proximityEnabled) return
            val now = System.nanoTime()
            if (now - lastProximityPublishNs < proximityIntervalNs) return
            lastProximityPublishNs = now

            proximityMsg.setData(event.values[0])
            proximityPublisher?.publish(proximityMsg)

            val s = _state.value
            _state.value = s.copy(
                proximityValue = event.values[0],
                proximityCount = s.proximityCount + 1,
                totalCount = s.totalCount + 1
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── GPS Listener ─────────────────────────────────────────────────

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!_state.value.gpsEnabled) return

            stampHeader(gpsMsg.header, "gps")
            gpsMsg.latitude = location.latitude
            gpsMsg.longitude = location.longitude
            gpsMsg.altitude = location.altitude

            // Set status to GPS fix
            gpsMsg.status.setStatus(NavSatStatus.STATUS_FIX)
            gpsMsg.status.setService(NavSatStatus.SERVICE_GPS)

            // Set covariance from accuracy
            if (location.hasAccuracy()) {
                val acc = location.accuracy.toDouble()
                val variance = acc * acc
                gpsMsg.positionCovariance[0] = variance
                gpsMsg.positionCovariance[4] = variance
                gpsMsg.positionCovariance[8] = variance * 4 // vertical less accurate
                gpsMsg.positionCovarianceType = NavSatFix.COVARIANCE_TYPE_DIAGONAL_KNOWN
            }

            gpsPublisher?.publish(gpsMsg)

            val s = _state.value
            _state.value = s.copy(
                gpsLat = location.latitude,
                gpsLon = location.longitude,
                gpsAlt = location.altitude,
                gpsCount = s.gpsCount + 1,
                totalCount = s.totalCount + 1
            )
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ── Battery Polling ──────────────────────────────────────────────

    private fun publishBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return

        stampHeader(batteryMsg.header, "battery")

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) level.toFloat() / scale else Float.NaN
        batteryMsg.percentage = percentage

        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        batteryMsg.voltage = if (voltage > 0) voltage / 1000f else Float.NaN

        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        batteryMsg.temperature = if (temp > 0) temp / 10f else Float.NaN

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        batteryMsg.powerSupplyStatus = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryState.POWER_SUPPLY_STATUS_CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryState.POWER_SUPPLY_STATUS_DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryState.POWER_SUPPLY_STATUS_FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryState.POWER_SUPPLY_STATUS_NOT_CHARGING
            else -> BatteryState.POWER_SUPPLY_STATUS_UNKNOWN
        }

        batteryMsg.present = true
        batteryMsg.powerSupplyTechnology = BatteryState.POWER_SUPPLY_TECHNOLOGY_LION
        batteryMsg.current = Float.NaN
        batteryMsg.charge = Float.NaN
        batteryMsg.capacity = Float.NaN
        batteryMsg.designCapacity = Float.NaN

        batteryPublisher?.publish(batteryMsg)

        val s = _state.value
        _state.value = s.copy(
            batteryPercent = percentage * 100f,
            batteryCharging = charging,
            batteryCount = s.batteryCount + 1,
            totalCount = s.totalCount + 1
        )
    }

    // ── Logging ──────────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val s = _state.value
        val logs = s.logMessages.toMutableList()
        logs.add(0, msg)
        if (logs.size > 100) logs.removeAt(logs.lastIndex)
        _state.value = s.copy(logMessages = logs)
    }
}
