package com.jros2.cellphone_interface

import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jros2.cellphone_interface.ui.theme.Jros2_cellphone_interfaceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import std_msgs.String as RosString

class MainActivity : ComponentActivity() {

    private lateinit var multicastLock: WifiManager.MulticastLock
    private var rosNode: ROS2Node? = null
    private var publisher: ROS2Publisher<RosString>? = null

    // UI State management
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())
    private var publishJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Critical for Android DDS Discovery over Wi-Fi
        enableRosDiscovery()

        setContent {
            Jros2_cellphone_interfaceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChatterApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Composable
    fun ChatterApp(modifier: Modifier = Modifier) {
        var isPublishing by remember { mutableStateOf(false) }
        var isSubscribing by remember { mutableStateOf(false) }
        val messages = remember { mutableStateListOf<String>() }

        // 2. Initialize ROS Node once when the Composable enters the screen
        LaunchedEffect(Unit) {
            ioScope.launch {
                // Change domain ID if your Ubuntu machine uses a different one
                rosNode = ROS2Node("android_chatter_node", 0)
                val topic = ROS2Topic("/chatter", RosString::class.java)

                publisher = rosNode?.createPublisher(topic)

                rosNode?.createSubscription(topic) { reader ->
                    val msg = reader.read() ?: return@createSubscription
                    if (isSubscribing) {
                        mainScope.launch {
                            messages.add(0, "RX <- ${msg.data}")
                            if (messages.size > 50) messages.removeAt(messages.lastIndex)
                        }
                    }
                }
            }
        }

        // 3. Handle Publisher Toggle
        LaunchedEffect(isPublishing) {
            if (isPublishing) {
                publishJob = ioScope.launch {
                    var count = 0
                    while (isActive) {
                        val msg = RosString().apply { data.setLength(0); data.append("Hello from Android: $count") }
                        publisher?.publish(msg)
                        mainScope.launch {
                            messages.add(0, "TX -> ${msg.data}")
                            if (messages.size > 50) messages.removeAt(messages.lastIndex)
                        }
                        count++
                        delay(1000) // Publish at 1Hz
                    }
                }
            } else {
                publishJob?.cancel()
            }
        }

        // UI Layout
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ROS 2 Chatter Interface",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { isPublishing = !isPublishing },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPublishing) Color(0xFFE57373) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isPublishing) "Stop Publishing" else "Start Publishing")
                }

                Button(
                    onClick = { isSubscribing = !isSubscribing },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSubscribing) Color(0xFF81C784) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isSubscribing) "Listening..." else "Start Listening")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Message Console
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(messages) { msg ->
                        Text(
                            text = msg,
                            color = Color(0xFF00FF00), // Classic terminal green
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    private fun enableRosDiscovery() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ros2_multicast_lock")
        multicastLock.setReferenceCounted(true)
        multicastLock.acquire()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        ioScope.cancel()
        rosNode?.close()

        if (::multicastLock.isInitialized && multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}