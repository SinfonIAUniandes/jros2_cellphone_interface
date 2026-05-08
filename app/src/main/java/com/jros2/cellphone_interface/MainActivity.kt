package com.jros2.cellphone_interface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jros2.cellphone_interface.sensors.BiometricAuthSensor
import com.jros2.cellphone_interface.sensors.PhoneSensor
import com.jros2.cellphone_interface.ui.theme.*

class MainActivity : FragmentActivity() {

    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var sensorBridge: SensorBridge

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Sensors depending on permissions will activate if granted.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        enableRosDiscovery()
        requestRuntimePermissions()

        val settingsManager = SettingsManager(this)
        sensorBridge = SensorBridge(this)

        setContent {
            Jros2_cellphone_interfaceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    SensorDashboard(sensorBridge, settingsManager)
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val camGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val missingPermissions = mutableListOf<String>()
        if (!fineGranted) {
            missingPermissions += Manifest.permission.ACCESS_FINE_LOCATION
            missingPermissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (!micGranted) {
            missingPermissions += Manifest.permission.RECORD_AUDIO
        }
        if (!camGranted) {
            missingPermissions += Manifest.permission.CAMERA
        }

        if (missingPermissions.isNotEmpty()) {
            permissionRequest.launch(missingPermissions.toTypedArray())
        }
    }

    private fun enableRosDiscovery() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("ros2_multicast_lock")
        multicastLock.setReferenceCounted(true)
        multicastLock.acquire()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::sensorBridge.isInitialized) {
            sensorBridge.dispatchTouchToSensors(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorBridge.destroy()
        if (::multicastLock.isInitialized && multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}

// ── Dashboard Composable ─────────────────────────────────────────────

@Composable
fun SensorDashboard(bridge: SensorBridge, settingsManager: SettingsManager) {
    val isRunning by bridge.isRunning.collectAsState()
    val logs by bridge.logMessages.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        HeaderSection(isRunning, onSettingsClick = { showSettings = true })

        Spacer(modifier = Modifier.height(16.dp))

        // ── Master Toggle ────────────────────────────────────────────
        MasterToggle(
            isRunning = isRunning,
            onToggle = { if (isRunning) bridge.stop() else bridge.start() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Sensor Cards Grid ────────────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(bridge.sensors, key = { it.id }) { sensor ->
                SensorCardItem(sensor, isRunning)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Log Console ──────────────────────────────────────────────
        LogConsole(
            messages = logs,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
    }

    if (showSettings) {
        SettingsDialog(
            settingsManager = settingsManager,
            sensors = bridge.sensors,
            onDismiss = { showSettings = false },
            onSave = {
                // If running, we must stop it so settings apply on next start
                if (bridge.isRunning.value) {
                    bridge.stop()
                }
                showSettings = false
            }
        )
    }
}

@Composable
fun SensorCardItem(sensor: PhoneSensor, isRunning: Boolean) {
    val enabled by sensor.enabled.collectAsState()
    val value by sensor.displayValue.collectAsState()
    val count by sensor.messageCount.collectAsState()
    val context = LocalContext.current
    val biometricSensor = sensor as? BiometricAuthSensor

    val borderColor by animateColorAsState(
        when {
            isRunning && enabled -> sensor.color.copy(alpha = 0.6f)
            else -> DarkCardBorder
        },
        label = "border"
    )
    val cardBg by animateColorAsState(
        when {
            isRunning && enabled -> sensor.color.copy(alpha = 0.06f)
            else -> DarkCard
        },
        label = "cardBg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (biometricSensor != null) 170.dp else 140.dp)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = sensor.icon, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = sensor.name,
                        color = if (enabled) sensor.color else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { sensor.enabled.value = it },
                    modifier = Modifier.height(20.dp),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = sensor.color.copy(alpha = 0.4f),
                        checkedThumbColor = sensor.color,
                        uncheckedTrackColor = TextMuted.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted
                    )
                )
            }

            Text(
                text = value,
                color = if (enabled && isRunning) TextPrimary else TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            if (biometricSensor != null) {
                OutlinedButton(
                    onClick = { biometricSensor.triggerAuthentication(context) },
                    enabled = isRunning && enabled,
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = biometricSensor.color,
                        disabledContentColor = TextMuted
                    )
                ) {
                    Text("Authenticate", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (isRunning && enabled) {
                Text(
                    text = "$count msgs",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ── Header ───────────────────────────────────────────────────────────

@Composable
fun HeaderSection(isRunning: Boolean, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ROS 2 Sensor Bridge",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text(text = "⚙️", fontSize = 18.sp)
                }
            }
            Text(
                text = "Phone → /phone/* topics",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        val statusColor by animateColorAsState(
            if (isRunning) GreenActive else TextMuted,
            label = "statusColor"
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isRunning) pulseAlpha else 1f))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isRunning) "LIVE" else "OFF",
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ── Master Toggle ────────────────────────────────────────────────────

@Composable
fun MasterToggle(isRunning: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        if (isRunning) GreenActive else CyanAccent,
        label = "toggleBg"
    )

    Button(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = if (isRunning) "⏹  STOP ALL SENSORS" else "▶  START SENSOR BRIDGE",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

// ── Log Console ──────────────────────────────────────────────────────

@Composable
fun LogConsole(messages: List<String>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E14),
                        Color(0xFF0D1117)
                    )
                ),
                RoundedCornerShape(10.dp)
            )
            .border(1.dp, DarkCardBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = "─── LOG ───",
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        color = GreenActive.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Settings Dialog ──────────────────────────────────────────────────

@Composable
fun SettingsDialog(
    settingsManager: SettingsManager,
    sensors: List<PhoneSensor>,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var domainId by remember { mutableStateOf(settingsManager.domainId.toString()) }
    var namespace by remember { mutableStateOf(settingsManager.namespace) }
    val context = LocalContext.current
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val supportsConcurrent = remember {
        try {
            cameraManager.concurrentCameraIds.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    var selectedCameraMode by remember { mutableStateOf(settingsManager.cameraMode) }
    val topicNames = remember {
        mutableStateMapOf<String, String>().apply {
            sensors.forEach { sensor ->
                this[sensor.id] = settingsManager.getTopicName(sensor.id, sensor.topicName)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "ROS 2 Settings",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    item {
                        OutlinedTextField(
                            value = domainId,
                            onValueChange = { domainId = it },
                            label = { Text("ROS_DOMAIN_ID") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = DarkCardBorder
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = namespace,
                            onValueChange = { namespace = it },
                            label = { Text("Node Namespace") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CyanAccent,
                                unfocusedBorderColor = DarkCardBorder
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Source Mode",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val modes = listOf(
                                "back" to "Back 📷",
                                "front" to "Front 🤳",
                                "dual" to "Dual 👥"
                            )
                            modes.forEach { (modeKey, modeName) ->
                                val isEnabled = modeKey != "dual" || supportsConcurrent
                                val isSelected = selectedCameraMode == modeKey

                                Button(
                                    onClick = { selectedCameraMode = modeKey },
                                    enabled = isEnabled,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) CyanAccent else DarkCardBorder,
                                        contentColor = if (isSelected) Color.Black else TextSecondary,
                                        disabledContainerColor = DarkCardBorder.copy(alpha = 0.3f),
                                        disabledContentColor = TextMuted.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = if (modeKey == "dual" && !supportsConcurrent) "$modeName (N/A)" else modeName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Sensor Topics",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(sensors) { sensor ->
                        OutlinedTextField(
                            value = topicNames[sensor.id] ?: "",
                            onValueChange = { topicNames[sensor.id] = it },
                            label = { Text(sensor.name) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = sensor.color,
                                unfocusedBorderColor = DarkCardBorder
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextMuted)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            settingsManager.domainId = domainId.toIntOrNull() ?: 0
                            settingsManager.namespace = namespace
                            settingsManager.cameraMode = selectedCameraMode
                            topicNames.forEach { (id, name) ->
                                settingsManager.setTopicName(id, name)
                            }
                            onSave()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Text("Save & Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}