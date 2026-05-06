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
import com.jros2.cellphone_interface.ui.theme.ImuColor
import sensor_msgs.Imu

class ImuSensor : PhoneSensor {
    override val id = "imu"
    override val name = "IMU"
    override val icon = "🔄"
    override val color: Color = ImuColor
    override var topicName = "imu"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("ax=0.0 ay=0.0 az=0.0")
    override val displayValue: StateFlow<String> = _value

    private val msg = Imu()
    private var publisher: ROS2Publisher<Imu>? = null
    private var sensorManager: SensorManager? = null

    // Throttle at 50 Hz
    private var lastPublishNs = 0L
    private val intervalNs = 20_000_000L

    // Fusion buffers
    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val rot = FloatArray(4) // x, y, z, w
    @Volatile private var hasAccel = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!enabled.value) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accel, 0, 3)
                    hasAccel = true
                }
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, gyro, 0, 3)
                Sensor.TYPE_ROTATION_VECTOR -> {
                    if (event.values.size >= 4) {
                        rot[0] = event.values[0]; rot[1] = event.values[1]
                        rot[2] = event.values[2]; rot[3] = event.values[3]
                    } else if (event.values.size >= 3) {
                        rot[0] = event.values[0]; rot[1] = event.values[1]; rot[2] = event.values[2]
                        val sq = rot[0]*rot[0] + rot[1]*rot[1] + rot[2]*rot[2]
                        rot[3] = if (sq < 1f) Math.sqrt((1f - sq).toDouble()).toFloat() else 0f
                    }
                }
            }
            if (!hasAccel) return
            val now = System.nanoTime()
            if (now - lastPublishNs < intervalNs) return
            lastPublishNs = now
            publish()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun publish() {
        stampHeader(msg.header, "phone_imu")
        msg.orientation.x = rot[0].toDouble()
        msg.orientation.y = rot[1].toDouble()
        msg.orientation.z = rot[2].toDouble()
        msg.orientation.w = rot[3].toDouble()
        msg.angularVelocity.x = gyro[0].toDouble()
        msg.angularVelocity.y = gyro[1].toDouble()
        msg.angularVelocity.z = gyro[2].toDouble()
        msg.linearAcceleration.x = accel[0].toDouble()
        msg.linearAcceleration.y = accel[1].toDouble()
        msg.linearAcceleration.z = accel[2].toDouble()
        publisher?.publish(msg)
        _count.value++
        _value.value = "ax=%.1f ay=%.1f az=%.1f".format(accel[0], accel[1], accel[2])
    }

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, Imu::class.java))
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR).forEach { type ->
            sensorManager?.getDefaultSensor(type)?.let {
                sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun stop() {
        sensorManager?.unregisterListener(listener)
        publisher = null
    }
}
