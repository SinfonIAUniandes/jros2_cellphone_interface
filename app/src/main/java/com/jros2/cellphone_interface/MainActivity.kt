package com.jros2.cellphone_interface

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.core.content.ContextCompat
import com.jros2.cellphone_interface.ui.theme.*

class MainActivity : ComponentActivity() {

    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var sensorBridge: SensorBridge

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            // GPS will auto-register when bridge starts
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // DDS Discovery over Wi-Fi
        enableRosDiscovery()

        // Request location permissions
        requestLocationPermissions()

        // Create sensor bridge
        sensorBridge = SensorBridge(this)

        setContent {
            Jros2_cellphone_interfaceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    SensorDashboard(sensorBridge)
                }
            }
        }
    }

    private fun requestLocationPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
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
        sensorBridge.destroy()
        if (::multicastLock.isInitialized && multicastLock.isHeld) {
            multicastLock.release()
        }
    }
}

// ── Dashboard Composable ─────────────────────────────────────────────

@Composable
fun SensorDashboard(bridge: SensorBridge) {
    val state by bridge.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        HeaderSection(state)

        Spacer(modifier = Modifier.height(16.dp))

        // ── Master Toggle ────────────────────────────────────────────
        MasterToggle(
            isRunning = state.isRunning,
            onToggle = { if (state.isRunning) bridge.stop() else bridge.start() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Sensor Cards Grid ────────────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SensorCard(
                    name = "IMU",
                    icon = "🔄",
                    color = ImuColor,
                    enabled = state.imuEnabled,
                    isRunning = state.isRunning,
                    count = state.imuCount,
                    value = "ax=%.1f ay=%.1f az=%.1f".format(
                        state.imuAccel[0], state.imuAccel[1], state.imuAccel[2]
                    ),
                    onToggle = { bridge.toggleSensor("imu", it) }
                )
            }
            item {
                SensorCard(
                    name = "Magnetometer",
                    icon = "🧭",
                    color = MagColor,
                    enabled = state.magEnabled,
                    isRunning = state.isRunning,
                    count = state.magCount,
                    value = "x=%.1f y=%.1f z=%.1f µT".format(
                        state.magValues[0], state.magValues[1], state.magValues[2]
                    ),
                    onToggle = { bridge.toggleSensor("mag", it) }
                )
            }
            item {
                SensorCard(
                    name = "Barometer",
                    icon = "🌡",
                    color = PressureColor,
                    enabled = state.pressureEnabled,
                    isRunning = state.isRunning,
                    count = state.pressureCount,
                    value = "%.1f hPa".format(state.pressureValue),
                    onToggle = { bridge.toggleSensor("pressure", it) }
                )
            }
            item {
                SensorCard(
                    name = "Light",
                    icon = "💡",
                    color = LightColor,
                    enabled = state.lightEnabled,
                    isRunning = state.isRunning,
                    count = state.lightCount,
                    value = "%.0f lux".format(state.lightValue),
                    onToggle = { bridge.toggleSensor("light", it) }
                )
            }
            item {
                SensorCard(
                    name = "GPS",
                    icon = "📍",
                    color = GpsColor,
                    enabled = state.gpsEnabled,
                    isRunning = state.isRunning,
                    count = state.gpsCount,
                    value = if (state.gpsCount > 0)
                        "%.5f°, %.5f°".format(state.gpsLat, state.gpsLon)
                    else "Waiting for fix...",
                    onToggle = { bridge.toggleSensor("gps", it) }
                )
            }
            item {
                SensorCard(
                    name = "Proximity",
                    icon = "📏",
                    color = ProximityColor,
                    enabled = state.proximityEnabled,
                    isRunning = state.isRunning,
                    count = state.proximityCount,
                    value = "%.1f cm".format(state.proximityValue),
                    onToggle = { bridge.toggleSensor("proximity", it) }
                )
            }
            item {
                SensorCard(
                    name = "Battery",
                    icon = if (state.batteryCharging) "🔌" else "🔋",
                    color = BatteryColor,
                    enabled = state.batteryEnabled,
                    isRunning = state.isRunning,
                    count = state.batteryCount,
                    value = "%.0f%%".format(state.batteryPercent) +
                            if (state.batteryCharging) " ⚡" else "",
                    onToggle = { bridge.toggleSensor("battery", it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Log Console ──────────────────────────────────────────────
        LogConsole(
            messages = state.logMessages,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
    }
}

// ── Header ───────────────────────────────────────────────────────────

@Composable
fun HeaderSection(state: SensorBridge.SensorState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "ROS 2 Sensor Bridge",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Phone → /phone/* topics",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        // Status pill
        val statusColor by animateColorAsState(
            if (state.isRunning) GreenActive else TextMuted,
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
            // Pulsing dot
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
                    .background(statusColor.copy(alpha = if (state.isRunning) pulseAlpha else 1f))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (state.isRunning) "LIVE" else "OFF",
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    // Stats bar
    if (state.isRunning) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatChip(label = "Total msgs", value = formatCount(state.totalCount))
            StatChip(label = "IMU msgs", value = formatCount(state.imuCount))
            StatChip(label = "GPS fixes", value = formatCount(state.gpsCount))
        }
    }
}

@Composable
fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = CyanAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = TextMuted, fontSize = 10.sp)
    }
}

fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
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

// ── Sensor Card ──────────────────────────────────────────────────────

@Composable
fun SensorCard(
    name: String,
    icon: String,
    color: Color,
    enabled: Boolean,
    isRunning: Boolean,
    count: Long,
    value: String,
    onToggle: (Boolean) -> Unit
) {
    val borderColor by animateColorAsState(
        when {
            isRunning && enabled -> color.copy(alpha = 0.6f)
            else -> DarkCardBorder
        },
        label = "border"
    )
    val cardBg by animateColorAsState(
        when {
            isRunning && enabled -> color.copy(alpha = 0.06f)
            else -> DarkCard
        },
        label = "cardBg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
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
            // Top row: icon + name + toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = icon, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = name,
                        color = if (enabled) color else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.height(20.dp),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = color.copy(alpha = 0.4f),
                        checkedThumbColor = color,
                        uncheckedTrackColor = TextMuted.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted
                    )
                )
            }

            // Value
            Text(
                text = value,
                color = if (enabled && isRunning) TextPrimary else TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Message count
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