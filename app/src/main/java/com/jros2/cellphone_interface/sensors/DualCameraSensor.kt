package com.jros2.cellphone_interface.sensors

import android.content.Context
import java.nio.ByteBuffer
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
        
        val colorMode = settings.cameraColor
        val rotation = settings.cameraRotation
        val resolution = settings.cameraResolution
        val (streamW, streamH) = when (resolution) {
            "low" -> 320 to 240
            "high" -> 1280 to 720
            else -> 640 to 480 // "med"
        }

        frontStream = if (openFront && frontId != null) openStream(context, manager, frontId, true, colorMode, rotation, streamW, streamH) else null
        backStream = if (openBack && backId != null) openStream(context, manager, backId, false, colorMode, rotation, streamW, streamH) else null

        _value.value = when {
            openFront && openBack -> "Streaming front+back"
            openFront -> "Streaming front"
            mode == "dual" && !supportsConcurrent -> "Streaming back (dual unsupported)"
            else -> "Streaming back"
        }
    }

    override fun stop() {
        try {
            frontStream?.close()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            backStream?.close()
        } catch (e: Exception) {
            // Ignore
        }
        frontStream = null
        backStream = null

        try {
            cameraThread?.quitSafely()
            cameraThread?.join(500)
        } catch (e: Exception) {
            // Ignore
        }
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

    private fun openStream(
        context: Context, 
        manager: CameraManager, 
        cameraId: String, 
        isFront: Boolean,
        colorMode: Boolean,
        rotation: Int,
        width: Int,
        height: Int
    ): CameraStream {
        val handler = cameraHandler ?: error("Camera handler not initialized")
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        val stream = CameraStream(cameraId, isFront, reader)
        val publisher = if (isFront) frontPublisher else backPublisher

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processAndPublishImage(image, publisher, isFront, colorMode, rotation)
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

    private fun processAndPublishImage(
        image: android.media.Image, 
        publisher: ROS2Publisher<Image>?, 
        isFront: Boolean,
        colorMode: Boolean,
        rotation: Int
    ) {
        if (publisher == null) return

        val width = image.width
        val height = image.height

        val msg = Image()
        val frameId = if (isFront) "phone_camera_front" else "phone_camera_back"
        stampHeader(msg.header, frameId)

        // Target dimensions after rotation
        val rotWidth = if (rotation == 90 || rotation == 270) height else width
        val rotHeight = if (rotation == 90 || rotation == 270) width else height

        msg.setWidth(rotWidth)
        msg.setHeight(rotHeight)

        if (colorMode) {
            // Color Mode (RGB8)
            msg.setEncoding("rgb8")
            msg.setIsBigendian(0)
            msg.setStep(rotWidth * 3)

            val rgbBytes = ByteArray(width * height * 3)
            yuvToRgb8(image, rgbBytes)

            val rotatedBytes = ByteArray(rotWidth * rotHeight * 3)
            rotateImageBytes(rgbBytes, width, height, rotatedBytes, rotation, pixelSize = 3)

            val data = msg.getData()
            data.clear()
            for (b in rotatedBytes) {
                data.add(b)
            }
        } else {
            // Grayscale Mode (Mono8)
            msg.setEncoding("mono8")
            msg.setIsBigendian(0)
            msg.setStep(rotWidth)

            val monoBytes = ByteArray(width * height)
            yuvToMono8(image, monoBytes)

            val rotatedBytes = ByteArray(rotWidth * rotHeight)
            rotateImageBytes(monoBytes, width, height, rotatedBytes, rotation, pixelSize = 1)

            val data = msg.getData()
            data.clear()
            for (b in rotatedBytes) {
                data.add(b)
            }
        }

        publisher.publish(msg)
        _count.value++
        if (isFront) frontFrames++ else backFrames++
        _value.value = "F:$frontFrames B:$backFrames"
    }

    private fun yuvToRgb8(image: android.media.Image, rgbBytes: ByteArray) {
        val width = image.width
        val height = image.height
        
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        
        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        
        var rgbIndex = 0
        
        for (y in 0 until height) {
            val yRowStart = y * yRowStride
            val uvRowStart = (y / 2) * uRowStride
            val vRowStart = (y / 2) * vRowStride
            
            for (x in 0 until width) {
                val yIndex = yRowStart + x * yPixelStride
                val uIndex = uvRowStart + (x / 2) * uPixelStride
                val vIndex = vRowStart + (x / 2) * vPixelStride
                
                val yVal = if (yIndex in 0 until yLimit) (yBuffer.get(yIndex).toInt() and 0xFF) else 0
                val uVal = if (uIndex in 0 until uLimit) (uBuffer.get(uIndex).toInt() and 0xFF) - 128 else 0
                val vVal = if (vIndex in 0 until vLimit) (vBuffer.get(vIndex).toInt() and 0xFF) - 128 else 0
                
                // YUV to RGB Conversion Formula
                val r = (yVal + 1.402 * vVal).toInt()
                val g = (yVal - 0.34414 * uVal - 0.71414 * vVal).toInt()
                val b = (yVal + 1.772 * uVal).toInt()
                
                rgbBytes[rgbIndex++] = r.coerceIn(0, 255).toByte()
                rgbBytes[rgbIndex++] = g.coerceIn(0, 255).toByte()
                rgbBytes[rgbIndex++] = b.coerceIn(0, 255).toByte()
            }
        }
    }

    private fun yuvToMono8(image: android.media.Image, monoBytes: ByteArray) {
        val width = image.width
        val height = image.height
        
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit = yBuffer.limit()
        
        var monoIndex = 0
        
        for (y in 0 until height) {
            val yRowStart = y * yRowStride
            for (x in 0 until width) {
                val yIndex = yRowStart + x * yPixelStride
                val yVal = if (yIndex in 0 until yLimit) yBuffer.get(yIndex) else 0.toByte()
                monoBytes[monoIndex++] = yVal
            }
        }
    }

    private fun rotateImageBytes(
        input: ByteArray,
        w: Int,
        h: Int,
        output: ByteArray,
        rotation: Int,
        pixelSize: Int
    ) {
        if (rotation == 0) {
            System.arraycopy(input, 0, output, 0, input.size)
            return
        }

        when (rotation) {
            90 -> {
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        val srcIdx = (row * w + col) * pixelSize
                        val destRow = col
                        val destCol = h - 1 - row
                        val destIdx = (destRow * h + destCol) * pixelSize
                        
                        for (i in 0 until pixelSize) {
                            output[destIdx + i] = input[srcIdx + i]
                        }
                    }
                }
            }
            180 -> {
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        val srcIdx = (row * w + col) * pixelSize
                        val destRow = h - 1 - row
                        val destCol = w - 1 - col
                        val destIdx = (destRow * w + destCol) * pixelSize
                        
                        for (i in 0 until pixelSize) {
                            output[destIdx + i] = input[srcIdx + i]
                        }
                    }
                }
            }
            270 -> {
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        val srcIdx = (row * w + col) * pixelSize
                        val destRow = w - 1 - col
                        val destCol = row
                        val destIdx = (destRow * h + destCol) * pixelSize
                        
                        for (i in 0 until pixelSize) {
                            output[destIdx + i] = input[srcIdx + i]
                        }
                    }
                }
            }
            else -> {
                System.arraycopy(input, 0, output, 0, input.size)
            }
        }
    }

    private data class CameraStream(
        val cameraId: String,
        val isFront: Boolean,
        val reader: ImageReader,
        var device: CameraDevice? = null,
        var session: CameraCaptureSession? = null
    ) {
        fun close() {
            try {
                reader.setOnImageAvailableListener(null, null)
            } catch (e: Exception) {
                // Ignore
            }
            try {
                session?.stopRepeating()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                session?.close()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                device?.close()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                reader.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
