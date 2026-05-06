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
import com.jros2.cellphone_interface.ui.theme.LightColor
import sensor_msgs.Illuminance

class LightSensor : PhoneSensor {
    override val id = "light"
    override val name = "Light"
    override val icon = "💡"
    override val color: Color = LightColor
    override var topicName = "illuminance"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("0 lux")
    override val displayValue: StateFlow<String> = _value

    private val msg = Illuminance()
    private var publisher: ROS2Publisher<Illuminance>? = null
    private var sensorManager: SensorManager? = null
    private var lastNs = 0L
    private val intervalNs = 200_000_000L // 5 Hz

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!enabled.value) return
            val now = System.nanoTime()
            if (now - lastNs < intervalNs) return
            lastNs = now

            stampHeader(msg.header, "phone_light_sensor")
            msg.illuminance = event.values[0].toDouble()
            publisher?.publish(msg)
            _count.value++
            _value.value = "%.0f lux".format(event.values[0])
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, Illuminance::class.java))
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun stop() {
        sensorManager?.unregisterListener(listener)
        publisher = null
    }
}
