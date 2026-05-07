package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.view.MotionEvent
import androidx.compose.ui.graphics.Color
import com.jros2.cellphone_interface.ui.theme.TouchColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mobile_sensor_msgs.TouchArray
import mobile_sensor_msgs.TouchPoint
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic

class TouchScreenSensor : PhoneSensor {
    override val id = "touch"
    override val name = "Touch"
    override val icon = "👆"
    override val color: Color = TouchColor
    override var topicName = "touch"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("—")
    override val displayValue: StateFlow<String> = _value

    private var publisher: ROS2Publisher<TouchArray>? = null
    private var lastMovePublishNs = 0L
    private val moveThrottleNs = 33_000_000L

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, TouchArray::class.java))
        _value.value = if (enabled.value) "Listening" else "Disabled"
    }

    fun onMotionEvent(event: MotionEvent) {
        if (!enabled.value || publisher == null) return

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_MOVE) {
            val now = System.nanoTime()
            if (now - lastMovePublishNs < moveThrottleNs) return
            lastMovePublishNs = now
        }

        val msg = TouchArray()
        stampHeader(msg.header, "phone_touch")
        val touches = msg.touches
        touches.clear()

        val n = event.pointerCount
        for (i in 0 until n) {
            val tp = TouchPoint()
            tp.id = event.getPointerId(i)
            tp.x = event.getRawX(i)
            tp.y = event.getRawY(i)
            tp.pressure = event.getPressure(i)
            tp.majorAxis = event.getTouchMajor(i)
            touches.add(tp)
        }

        publisher?.publish(msg)
        _count.value++
        _value.value = if (n <= 1) "1 @ (${event.getRawX(0).toInt()},${event.getRawY(0).toInt()})"
        else "$n fingers"
    }

    override fun stop() {
        publisher = null
        lastMovePublishNs = 0L
    }
}
