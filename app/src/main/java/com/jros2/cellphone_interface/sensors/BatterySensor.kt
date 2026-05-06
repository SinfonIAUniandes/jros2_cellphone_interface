package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import com.jros2.cellphone_interface.ui.theme.BatteryColor
import sensor_msgs.BatteryState

class BatterySensor : PhoneSensor {
    override val id = "battery"
    override val name = "Battery"
    override val icon = "🔋"
    override val color: Color = BatteryColor
    override var topicName = "battery"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("0%")
    override val displayValue: StateFlow<String> = _value

    private val msg = BatteryState()
    private var publisher: ROS2Publisher<BatteryState>? = null
    private var job: Job? = null

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, BatteryState::class.java))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        job = scope.launch {
            while (isActive) {
                if (enabled.value) {
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    if (intent != null) {
                        stampHeader(msg.header, "phone_battery")
                        
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val pct = if (level >= 0 && scale > 0) level.toFloat() / scale else Float.NaN
                        msg.percentage = pct

                        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                        msg.voltage = if (voltage > 0) voltage / 1000f else Float.NaN

                        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                        msg.temperature = if (temp > 0) temp / 10f else Float.NaN

                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        msg.powerSupplyStatus = when (status) {
                            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryState.POWER_SUPPLY_STATUS_CHARGING
                            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryState.POWER_SUPPLY_STATUS_DISCHARGING
                            BatteryManager.BATTERY_STATUS_FULL -> BatteryState.POWER_SUPPLY_STATUS_FULL
                            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryState.POWER_SUPPLY_STATUS_NOT_CHARGING
                            else -> BatteryState.POWER_SUPPLY_STATUS_UNKNOWN
                        }
                        
                        msg.present = true
                        msg.powerSupplyTechnology = BatteryState.POWER_SUPPLY_TECHNOLOGY_LION
                        msg.current = Float.NaN
                        msg.charge = Float.NaN
                        msg.capacity = Float.NaN
                        msg.designCapacity = Float.NaN

                        publisher?.publish(msg)
                        _count.value++
                        
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                        _value.value = "%.0f%%".format(pct * 100f) + if (isCharging) " ⚡" else ""
                    }
                }
                delay(5000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        publisher = null
    }
}
