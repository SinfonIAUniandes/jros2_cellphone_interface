package com.jros2.cellphone_interface.sensors

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.ihmc.jros2.ROS2Node

/**
 * Interface for any phone sensor that publishes to a ROS 2 topic.
 *
 * To add a new sensor:
 *   1. Implement this interface
 *   2. Add an instance to the list in SensorBridgeManager
 */
interface PhoneSensor {
    val id: String
    val name: String
    val icon: String
    val color: Color
    var topicName: String

    val enabled: MutableStateFlow<Boolean>
    val messageCount: StateFlow<Long>
    val displayValue: StateFlow<String>

    /** Create publisher and register listeners. Called on Main thread. */
    fun start(node: ROS2Node, context: Context)

    /** Unregister listeners and clean up. */
    fun stop()
}

/** Populate a std_msgs/Header with current wall-clock time and frame ID. */
fun stampHeader(header: std_msgs.Header, frameId: String) {
    val nowMs = System.currentTimeMillis()
    header.stamp.sec = (nowMs / 1000).toInt()
    header.stamp.nanosec = ((nowMs % 1000) * 1_000_000).toInt()
    header.setFrameId(frameId)
}
