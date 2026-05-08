package com.jros2.cellphone_interface.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.jros2.cellphone_interface.SettingsManager
import com.jros2.cellphone_interface.ui.theme.CameraColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sensor_msgs.Image
import us.ihmc.jros2.ROS2Node
import us.ihmc.jros2.ROS2Publisher
import us.ihmc.jros2.ROS2Topic

class DualCameraSensor : PhoneSensor {
    override val id = "camera_dual"
    override val name = "Camera"
    override val icon = "📷"
    override val color: Color = CameraColor
    override var topicName = "camera"

    override val enabled = MutableStateFlow(true)
    private val _count = MutableStateFlow(0L)
    override val messageCount: StateFlow<Long> = _count
    private val _value = MutableStateFlow("Idle")
    override val displayValue: StateFlow<String> = _value

    private var frontPublisher: ROS2Publisher<Image>? = null
    private var backPublisher: ROS2Publisher<Image>? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var frontStream: CameraStream? = null
    private var backStream: CameraStream? = null

    private var frontFrames = 0L
    private var backFrames = 0L

    override fun start(node: ROS2Node, context: Context) {
        val settings = SettingsManager(context)
        val mode = settings.cameraMode

        if (!enabled.value) {
            _value.value = "Disabled"
            return
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _value.value = "Camera permission missing"
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val frontId = findCamera(manager, CameraCharacteristics.LENS_FACING_FRONT)
        val backId = findCamera(manager, CameraCharacteristics.LENS_FACING_BACK)
        if (frontId == null && backId == null) {
            _value.value = "No cameras found"
            return
        }

        // Check concurrent camera support
        val supportsConcurrent = try {
            val concurrentSets = manager.concurrentCameraIds
            concurrentSets.any { set ->
                frontId != null && backId != null && set.contains(frontId) && set.contains(backId)
            }
        } catch (e: Exception) {
            false
        }

        // Determine which cameras to open based on selected mode and hardware support
        val (openFront, openBack) = when (mode) {
            "front" -> (frontId != null) to false
            "dual" -> {
                if (supportsConcurrent) {
                    (frontId != null) to (backId != null)
                } else {
                    // Fallback to back camera if concurrent is unsupported
                    false to (backId != null)
                }
            }
            else -> false to (backId != null) // Default to "back"
        }

        if (!openFront && !openBack) {
            _value.value = "No camera available"
            return
        }

        if (openFront) {
            frontPublisher = node.createPublisher(ROS2Topic("$topicName/front/image", Image::class.java))
        }
        if (openBack) {
            backPublisher = node.createPublisher(ROS2Topic("$topicName/back/image", Image::class.java))
        }

        cameraThread = HandlerThread("camera-sensor-thread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        frontFrames = 0L
        backFrames = 0L
        
        frontStream = if (openFront && frontId != null) openStream(context, manager, frontId, true) else null
        backStream = if (openBack && backId != null) openStream(context, manager, backId, false) else null

        _value.value = when {
            openFront && openBack -> "Streaming front+back"
            openFront -> "Streaming front"
            mode == "dual" && !supportsConcurrent -> "Streaming back (dual unsupported)"
            else -> "Streaming back"
        }
    }

    override fun stop() {
        frontStream?.close()
        backStream?.close()
        frontStream = null
        backStream = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        frontPublisher = null
        backPublisher = null
        _value.value = "Idle"
    }

    private fun findCamera(manager: CameraManager, lensFacing: Int): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == lensFacing) {
                return id
            }
        }
        return null
    }

    private fun openStream(context: Context, manager: CameraManager, cameraId: String, isFront: Boolean): CameraStream {
        val handler = cameraHandler ?: error("Camera handler not initialized")
        val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        val stream = CameraStream(cameraId, isFront, reader)
        val publisher = if (isFront) frontPublisher else backPublisher
        val frameId = if (isFront) "phone_camera_front" else "phone_camera_back"

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val msg = toRosImage(image, frameId)
                publisher?.publish(msg)
                _count.value++
                if (isFront) frontFrames++ else backFrames++
                _value.value = "F:$frontFrames B:$backFrames"
            } finally {
                image.close()
            }
        }, handler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                stream.device = camera
                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }

                camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        stream.session = session
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _value.value = "Camera session failed"
                    }
                }, handler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                _value.value = "Camera error $error"
                camera.close()
            }
        }, handler)

        return stream
    }

    private fun toRosImage(image: android.media.Image, frameId: String): Image {
        val msg = Image()
        stampHeader(msg.header, frameId)

        val width = image.width
        val height = image.height
        msg.setWidth(width)
        msg.setHeight(height)
        msg.setEncoding("mono8")
        msg.setIsBigendian(0)
        msg.setStep(width)

        val yPlane = image.planes[0]
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val src = yPlane.buffer
        val data = msg.getData()
        data.clear()

        if (pixelStride == 1 && rowStride == width) {
            val mono = ByteArray(width * height)
            src.get(mono, 0, mono.size)
            for (b in mono) {
                data.add(b)
            }
        } else {
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    data.add(src.get(rowStart + col * pixelStride))
                }
            }
        }
        return msg
    }

    private data class CameraStream(
        val cameraId: String,
        val isFront: Boolean,
        val reader: ImageReader,
        var device: CameraDevice? = null,
        var session: CameraCaptureSession? = null
    ) {
        fun close() {
            session?.close()
            device?.close()
            reader.close()
        }
    }
}
