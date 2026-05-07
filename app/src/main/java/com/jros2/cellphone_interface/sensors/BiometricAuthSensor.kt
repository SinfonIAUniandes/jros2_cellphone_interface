package com.jros2.cellphone_interface.sensors

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jros2.cellphone_interface.ui.theme.BiometricColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mobile_sensor_msgs.BiometricAuth
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic

class BiometricAuthSensor : PhoneSensor {
    override val id = "biometric_auth"
    override val name = "Biometric Auth"
    override val icon = "🔐"
    override val color: Color = BiometricColor
    override var topicName = "biometric_auth"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("Waiting")
    override val displayValue: StateFlow<String> = _value

    private var publisher: ROS2Publisher<BiometricAuth>? = null
    private var prompt: BiometricPrompt? = null

    override fun start(node: ROS2Node, context: Context) {
        publisher = node.createPublisher(ROS2Topic(topicName, BiometricAuth::class.java))
        _value.value = if (enabled.value) "Ready (tap Authenticate)" else "Disabled"
    }

    fun triggerAuthentication(context: Context) {
        if (!enabled.value) {
            _value.value = "Disabled"
            return
        }

        if (publisher == null) {
            _value.value = "Bridge not running"
            return
        }

        val activity = context as? FragmentActivity
        if (activity == null) {
            _value.value = "Activity context required"
            return
        }

        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            _value.value = "Biometric unavailable"
            publishAuthResult(success = false, userId = 0)
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                _value.value = "Authentication success"
                publishAuthResult(success = true, userId = 0)
            }

            override fun onAuthenticationFailed() {
                _value.value = "Authentication failed"
                publishAuthResult(success = false, userId = 0)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                _value.value = "Error: $errString"
                publishAuthResult(success = false, userId = 0)
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric authentication")
            .setSubtitle("Authenticate to publish BiometricAuth")
            .setNegativeButtonText("Cancel")
            .build()
        prompt?.authenticate(promptInfo)
        _value.value = "Prompt shown"
    }

    private fun publishAuthResult(success: Boolean, userId: Int) {
        val msg = BiometricAuth()
        stampHeader(msg.header, "phone_biometric")
        msg.success = success
        msg.userId = userId.toByte()
        publisher?.publish(msg)
        _count.value++
    }

    override fun stop() {
        prompt?.cancelAuthentication()
        prompt = null
        publisher = null
    }
}
