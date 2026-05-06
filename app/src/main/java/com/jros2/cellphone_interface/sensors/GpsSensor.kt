package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic
import com.jros2.cellphone_interface.ui.theme.GpsColor
import sensor_msgs.NavSatFix
import sensor_msgs.NavSatStatus

class GpsSensor : PhoneSensor {
    override val id = "gps"
    override val name = "GPS"
    override val icon = "📍"
    override val color: Color = GpsColor
    override var topicName = "gps"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("Waiting for fix...")
    override val displayValue: StateFlow<String> = _value

    private val msg = NavSatFix()
    private var publisher: ROS2Publisher<NavSatFix>? = null
    private var locationManager: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!enabled.value) return

            stampHeader(msg.header, "phone_gps")
            msg.latitude = location.latitude
            msg.longitude = location.longitude
            msg.altitude = location.altitude

            msg.status.setStatus(NavSatStatus.STATUS_FIX)
            msg.status.setService(NavSatStatus.SERVICE_GPS)

            if (location.hasAccuracy()) {
                val acc = location.accuracy.toDouble()
                val variance = acc * acc
                msg.positionCovariance[0] = variance
                msg.positionCovariance[4] = variance
                msg.positionCovariance[8] = variance * 4
                msg.positionCovarianceType = NavSatFix.COVARIANCE_TYPE_DIAGONAL_KNOWN
            }

            publisher?.publish(msg)
            _count.value++
            _value.value = "%.5f°, %.5f°".format(location.latitude, location.longitude)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, NavSatFix::class.java))
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    listener
                )
            }
        } catch (e: SecurityException) {
            _value.value = "Permission denied"
        }
    }

    override fun stop() {
        try {
            locationManager?.removeUpdates(listener)
        } catch (e: SecurityException) {}
        publisher = null
    }
}
