package com.jros2.cellphone_interface

import android.content.Context
import android.util.Log
import com.jros2.cellphone_interface.sensors.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.ihmc.jros2.ROS2Node

class SensorBridge(private val context: Context) {
    companion object {
        private const val TAG = "SensorBridge"
    }

    private var rosNode: ROS2Node? = null
    private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings = SettingsManager(context)

    val sensors = listOf(
        ImuSensor(),
        MagnetometerSensor(),
        PressureSensor(),
        LightSensor(),
        GpsSensor(),
        ProximitySensor(),
        BatterySensor()
    )

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    fun start() {
        if (_isRunning.value) return
        log("Starting ROS 2 sensor bridge...")

        bridgeScope.launch {
            try {
                val domainId = settings.domainId
                log("Creating ROS 2 node on domain $domainId...")
                rosNode = ROS2Node("phone_sensor_node", domainId)
                log("ROS 2 node created successfully")

                val ns = settings.namespace

                withContext(Dispatchers.Main) {
                    sensors.forEach { sensor ->
                        try {
                            var rawTopic = settings.getTopicName(sensor.id, sensor.topicName)
                            if (ns.isNotBlank()) {
                                rawTopic = if (rawTopic.startsWith("/")) "/$ns$rawTopic" else "/$ns/$rawTopic"
                            }
                            sensor.topicName = rawTopic
                            
                            sensor.start(rosNode!!, context)
                            log("Started ${sensor.name} on ${sensor.topicName}")
                        } catch (e: Exception) {
                            log("Failed to start ${sensor.name}: ${e.message}")
                        }
                    }
                }

                _isRunning.value = true
                log("Sensor bridge active")
            } catch (e: Exception) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Failed to start bridge", e)
            }
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        log("Stopping sensor bridge...")
        
        sensors.forEach { it.stop() }
        
        rosNode?.close()
        rosNode = null
        
        _isRunning.value = false
        log("Sensor bridge stopped")
    }

    fun destroy() {
        stop()
        bridgeScope.cancel()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val logs = _logMessages.value.toMutableList()
        logs.add(0, msg)
        if (logs.size > 100) logs.removeAt(logs.lastIndex)
        _logMessages.value = logs
    }
}
