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
import com.jros2.cellphone_interface.ui.theme.*
import sensor_msgs.MagneticField

class MagnetometerSensor : PhoneSensor {
    override val id = "mag"
    override val name = "Magnetometer"
    override val icon = "🧭"
    override val color: Color = MagColor
    override var topicName = "magnetic_field"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("0.0, 0.0, 0.0 µT")
    override val displayValue: StateFlow<String> = _value

    private val msg = MagneticField()
    private var publisher: ROS2Publisher<MagneticField>? = null
    private var sensorManager: SensorManager? = null
    private var lastNs = 0L
    private val intervalNs = 100_000_000L // 10 Hz

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!enabled.value) return
            val now = System.nanoTime()
            if (now - lastNs < intervalNs) return
            lastNs = now

            stampHeader(msg.header, "phone_magnetometer")
            // Android µT → ROS 2 Tesla
            msg.magneticField.x = event.values[0].toDouble() * 1e-6
            msg.magneticField.y = event.values[1].toDouble() * 1e-6
            msg.magneticField.z = event.values[2].toDouble() * 1e-6
            publisher?.publish(msg)
            _count.value++
            _value.value = "%.1f, %.1f, %.1f µT".format(event.values[0], event.values[1], event.values[2])
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, MagneticField::class.java))
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun stop() {
        sensorManager?.unregisterListener(listener)
        publisher = null
    }
}
