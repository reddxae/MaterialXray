package com.material.xray.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size as AndroidSize
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.material.xray.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun QrScannerOverlay(
    onQrCodeScanned: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var scanner by remember { mutableStateOf<Camera2QrScanner?>(null) }
    var cameraUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            scanner?.stop()
            scanner = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).also { textureView ->
                    scanner = Camera2QrScanner(
                        context = viewContext.applicationContext,
                        textureView = textureView,
                        onQrCodeScanned = { result ->
                            textureView.post { onQrCodeScanned(result) }
                        },
                        onCameraUnavailable = {
                            textureView.post { cameraUnavailable = true }
                        },
                    ).also { it.start() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        ScannerMask(modifier = Modifier.fillMaxSize())

        Text(
            text = stringResource(R.string.home_scan_qr_code),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )

        if (cameraUnavailable) {
            Text(
                text = stringResource(R.string.home_camera_unavailable),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }

        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 42.dp),
        ) {
            Text(stringResource(R.string.home_action_close), color = Color.White)
        }
    }
}

@Composable
private fun ScannerMask(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        drawRect(Color.Black.copy(alpha = 0.66f))

        val scanSize = size.minDimension * 0.72f
        val topLeft = Offset(
            x = (size.width - scanSize) / 2f,
            y = (size.height - scanSize) / 2f,
        )
        drawRect(
            color = Color.Transparent,
            topLeft = topLeft,
            size = Size(scanSize, scanSize),
            blendMode = BlendMode.Clear,
        )
        drawRect(
            color = Color.White,
            topLeft = topLeft,
            size = Size(scanSize, scanSize),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private class Camera2QrScanner(
    private val context: Context,
    private val textureView: TextureView,
    private val onQrCodeScanned: (String) -> Unit,
    private val onCameraUnavailable: () -> Unit,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val decoding = AtomicBoolean(false)
    private val luminanceBuffers = QrLuminanceBuffers()
    private val qrReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraId: String? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var resultDelivered = false

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
        textureView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
            }
            true
        }
    }

    fun stop() {
        textureView.setOnTouchListener(null)
        textureView.surfaceTextureListener = null
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val selection = runCatching { selectBackCamera() }
            .onFailure { Log.e(TAG, "Failed to enumerate cameras", it) }
            .getOrNull()
        if (selection == null) {
            onCameraUnavailable()
            return
        }
        cameraId = selection.id
        cameraCharacteristics = selection.characteristics
        imageReader = ImageReader.newInstance(
            selection.scanSize.width,
            selection.scanSize.height,
            ImageFormat.YUV_420_888,
            2,
        ).apply {
            setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, backgroundHandler)
        }

        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(selection.previewSize.width, selection.previewSize.height)
        previewSurface = Surface(texture)

        runCatching { cameraManager.openCamera(selection.id, cameraStateCallback, backgroundHandler) }
            .onFailure {
                Log.e(TAG, "Failed to open camera", it)
                onCameraUnavailable()
            }
    }

    private fun createCaptureSession(device: CameraDevice) {
        val surface = previewSurface ?: return
        val readerSurface = imageReader?.surface ?: return
        previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            addTarget(readerSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        device.createCaptureSession(
            listOf(surface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = previewRequestBuilder?.build() ?: return
                    session.setRepeatingRequest(request, null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            },
            backgroundHandler,
        )
    }

    private fun focusAt(viewX: Float, viewY: Float) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val characteristics = cameraCharacteristics ?: return
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAfRegions <= 0 && maxAeRegions <= 0) return

        val sensorX = (viewX / textureView.width.coerceAtLeast(1) * activeArray.width()).roundToInt() + activeArray.left
        val sensorY = (viewY / textureView.height.coerceAtLeast(1) * activeArray.height()).roundToInt() + activeArray.top
        val focusRect = meteringRectangle(activeArray, sensorX, sensorY)

        backgroundHandler?.post {
            runCatching {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), null, backgroundHandler)

                if (maxAfRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
                }
                if (maxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
                }
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), null, backgroundHandler)

                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                backgroundHandler?.postDelayed({
                    runCatching {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    }
                }, FOCUS_LOCK_MS)
            }
        }
    }

    private fun meteringRectangle(activeArray: Rect, x: Int, y: Int): MeteringRectangle {
        val side = (activeArray.width().coerceAtMost(activeArray.height()) * 0.12f).roundToInt()
        val half = side / 2
        val left = (x - half).coerceIn(activeArray.left, activeArray.right - side)
        val top = (y - half).coerceIn(activeArray.top, activeArray.bottom - side)
        return MeteringRectangle(Rect(left, top, left + side, top + side), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        if (resultDelivered || !decoding.compareAndSet(false, true)) {
            image.close()
            return
        }

        val handler = backgroundHandler
        if (handler == null) {
            image.close()
            decoding.set(false)
            return
        }
        val accepted = handler.post {
            try {
                decodeQr(image)?.let { result ->
                    if (!resultDelivered) {
                        resultDelivered = true
                        onQrCodeScanned(result)
                    }
                }
            } finally {
                image.close()
                decoding.set(false)
            }
        }
        if (!accepted) {
            image.close()
            decoding.set(false)
        }
    }

    private fun decodeQr(image: Image): String? {
        val plane = image.planes[0]
        val luminance = luminanceBuffers.copyLuminance(
            source = plane.buffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
        )
        decode(luminanceSource(luminance, image.width, image.height))?.let { return it }

        val inverted = luminanceBuffers.invert(luminance)
        return decode(luminanceSource(inverted, image.width, image.height))
    }

    private fun luminanceSource(bytes: ByteArray, width: Int, height: Int) = PlanarYUVLuminanceSource(
        bytes,
        width,
        height,
        0,
        0,
        width,
        height,
        false,
    )

    private fun decode(source: PlanarYUVLuminanceSource): String? = try {
        qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    } finally {
        qrReader.reset()
    }

    private fun selectBackCamera(): CameraSelection? {
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) return@forEach
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@forEach
            val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                ?.chooseBestSize(targetWidth = 1280, targetHeight = 720)
                ?: return@forEach
            val scanSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.chooseBestSize(targetWidth = 1280, targetHeight = 720)
                ?: return@forEach
            return CameraSelection(id, characteristics, previewSize, scanSize)
        }
        return null
    }

    private fun Array<AndroidSize>.chooseBestSize(targetWidth: Int, targetHeight: Int): AndroidSize? = minByOrNull { size ->
        abs(size.width - targetWidth) + abs(size.height - targetHeight)
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("QrScannerCamera").also { thread ->
            thread.start()
            backgroundHandler = Handler(thread.looper)
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private data class CameraSelection(
        val id: String,
        val characteristics: CameraCharacteristics,
        val previewSize: AndroidSize,
        val scanSize: AndroidSize,
    )

    private companion object {
        const val TAG = "QrScanner"
        const val FOCUS_LOCK_MS = 1_500L
    }
}
