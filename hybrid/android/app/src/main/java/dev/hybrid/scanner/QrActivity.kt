package dev.hybrid.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.max
import kotlin.math.roundToInt

/** Local pairing camera with Camera2 autofocus and a separate QR decoder thread. */
class QrActivity : Activity(), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preview: TextureView
    private lateinit var message: TextView
    private lateinit var marker: View
    private lateinit var decoder: HandlerThread
    private lateinit var decodeHandler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var size: Size? = null
    private var crop: Rect? = null
    private val screenToBuffer = Matrix()
    private var hasTransform = false
    private var opening = false
    private var resumed = false
    private var generation = 0
    private var focusRevision = 0
    private var retryCount = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        decoder = HandlerThread("Pairing QR decoder").apply { start() }
        decodeHandler = Handler(decoder.looper)
        preview = TextureView(this).apply {
            surfaceTextureListener = this@QrActivity
            contentDescription = "Камера QR. Коснитесь кода для фокусировки."
            isClickable = true
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    view.performClick()
                    focusAt(event.x, event.y)
                }
                true
            }
        }
        marker = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xe0151e2a.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            text = "Коснитесь QR-кода на экране, чтобы навести фокус"
        }
        val back = Button(this).apply {
            text = "Назад"
            setOnClickListener { finish() }
        }
        val manual = Button(this).apply {
            text = "Ввести код"
            setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(MANUAL, true))
                finish()
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(marker, FrameLayout.LayoutParams(dp(52), dp(52)))
            addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(back, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            addView(manual, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        retryCount = 0
        open()
    }

    override fun onPause() {
        resumed = false
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        decoder.quitSafely()
        super.onDestroy()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = open()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateTransform()
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        closeCamera()
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    @SuppressLint("MissingPermission")
    private fun open() {
        if (!resumed || !preview.isAvailable || opening || camera != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message.text = "Разрешите доступ к камере или введите код вручную"
            return
        }
        val ticket = ++generation
        opening = true
        try {
            val manager = getSystemService(CameraManager::class.java)
            val rear = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
                .filter { (_, info) -> info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            val selected = rear.firstOrNull { (_, info) ->
                supports(info, CaptureRequest.CONTROL_AF_MODE_AUTO)
            } ?: rear.firstOrNull() ?: error("Задняя камера недоступна")
            characteristics = selected.second
            manager.openCamera(selected.first, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!resumed || !preview.isAvailable || ticket != generation) {
                        device.close()
                        return
                    }
                    opening = false
                    camera = device
                    configure(device, selected.second, ticket)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (ticket == generation) cameraError(false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (ticket == generation) cameraError(
                        error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                            error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                    )
                }
            }, main)
        } catch (error: CameraAccessException) {
            cameraError(error.reason == CameraAccessException.CAMERA_IN_USE ||
                error.reason == CameraAccessException.MAX_CAMERAS_IN_USE)
        } catch (error: Exception) {
            cameraError(false)
        }
    }

    private fun configure(device: CameraDevice, info: CameraCharacteristics, ticket: Int) {
        try {
            val sizes = requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val available = sizes.getOutputSizes(SurfaceTexture::class.java).toSet()
            val common = sizes.getOutputSizes(ImageFormat.YUV_420_888).filter { it in available }
            val chosen = common.filter { it.width <= 1280 && it.height <= 960 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: common.minByOrNull { it.width.toLong() * it.height }
                ?: error("No common preview and YUV size")
            size = chosen
            crop = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            updateTransform()
            val texture = requireNotNull(preview.surfaceTexture)
            texture.setDefaultBufferSize(chosen.width, chosen.height)
            val target = Surface(texture).also { previewSurface = it }
            val yuv = ImageReader.newInstance(chosen.width, chosen.height, ImageFormat.YUV_420_888, 2)
                .also { reader = it }
            var lastDecode = 0L
            val qr = QRCodeReader()
            yuv.setOnImageAvailableListener({ input ->
                input.acquireLatestImage()?.use { image ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDecode < 120) return@use
                    lastDecode = now
                    val w = image.width
                    val h = image.height
                    val source = PlanarYUVLuminanceSource(plane(image, 0, w, h), w, h, 0, 0, w, h, false)
                    val binary = BinaryBitmap(HybridBinarizer(source))
                    val text = try {
                        qr.decode(binary).text
                    } catch (_: ReaderException) {
                        try { qr.decode(BinaryBitmap(HybridBinarizer(source.invert()))).text }
                        catch (_: ReaderException) { null }
                    }
                    if (text != null) main.post { onQr(ticket, text) }
                }
            }, decodeHandler)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(target)
                addTarget(yuv.surface)
                set(CaptureRequest.CONTROL_AF_MODE, when {
                    supports(info, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    supports(info, CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                        CaptureRequest.CONTROL_AF_MODE_AUTO
                    else -> CaptureRequest.CONTROL_AF_MODE_OFF
                })
            }
            request = builder
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(target, yuv.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (ticket != generation || !resumed) {
                        configured.close()
                        return
                    }
                    session = configured
                    try {
                        configured.setRepeatingRequest(builder.build(), null, main)
                    } catch (_: CameraAccessException) {
                        cameraError(false)
                    } catch (_: IllegalStateException) {
                        cameraError(false)
                    }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (ticket == generation) cameraError(false)
                }
            }, main)
        } catch (_: Exception) {
            cameraError(false)
        }
    }

    private fun supports(info: CameraCharacteristics, mode: Int) =
        info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(mode) == true

    private fun updateTransform() {
        val buffer = size ?: return
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w <= 0f || h <= 0f) return
        @Suppress("DEPRECATION")
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val angle = (sensor - rotation + 360) % 360
        val bw = buffer.width.toFloat()
        val bh = buffer.height.toFloat()
        val sideways = angle == 90 || angle == 270
        val scale = max(w / (if (sideways) bh else bw), h / (if (sideways) bw else bh))
        val bufferToScreen = Matrix().apply {
            setTranslate(-bw / 2, -bh / 2)
            postRotate(angle.toFloat())
            postScale(scale, scale)
            postTranslate(w / 2, h / 2)
        }
        hasTransform = bufferToScreen.invert(screenToBuffer)
        preview.setTransform(Matrix().apply {
            setScale(bw / w, bh / h)
            postConcat(bufferToScreen)
        })
    }

    private fun focusAt(x: Float, y: Float) {
        val info = characteristics ?: return
        val capture = session ?: return
        val builder = request ?: return
        val mode = when {
            supports(info, CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            supports(info, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> {
                message.text = "Автофокус недоступен. Попробуйте отодвинуть телефон."
                return
            }
        }
        val area = crop
        val buffer = size
        val useRegion = hasTransform && area != null && buffer != null &&
            (info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
        val revision = ++focusRevision
        val ticket = generation
        marker.x = x - dp(26)
        marker.y = y - dp(26)
        marker.visibility = View.VISIBLE
        message.text = if (useRegion) "Навожу фокус…" else "Автофокус камеры…"
        try {
            builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            capture.capture(builder.build(), null, main)
            if (useRegion && area != null && buffer != null) {
                val point = floatArrayOf(x, y)
                screenToBuffer.mapPoints(point)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region(area, buffer, point)))
            }
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            capture.capture(builder.build(), null, main)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            val waiting = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    if (ticket != generation || revision != focusRevision) return
                    when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> finishFocus(ticket, revision, "Фокус установлен")
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
                            finishFocus(ticket, revision, "Не удалось сфокусироваться. Отодвиньте телефон и коснитесь кода ещё раз.")
                    }
                }
            }
            capture.setRepeatingRequest(builder.build(), waiting, main)
            main.postDelayed({
                finishFocus(ticket, revision, "Коснитесь кода ещё раз, если он размытый")
            }, 2800)
        } catch (_: CameraAccessException) {
            finishFocus(ticket, revision, "Камера не смогла навести фокус")
        } catch (_: IllegalStateException) {
            finishFocus(ticket, revision, "Камера не смогла навести фокус")
        } catch (_: IllegalArgumentException) {
            finishFocus(ticket, revision, "Камера не поддерживает фокусировку в этой области")
        }
    }

    private fun region(sensor: Rect, buffer: Size, point: FloatArray): MeteringRectangle {
        // Camera2 fits output streams to a centered patch of the current sensor crop.
        val scale = minOf(sensor.width().toFloat() / buffer.width, sensor.height().toFloat() / buffer.height)
        val x = sensor.exactCenterX() + (point[0] - buffer.width / 2f) * scale
        val y = sensor.exactCenterY() + (point[1] - buffer.height / 2f) * scale
        val half = (minOf(sensor.width(), sensor.height()) / 14).coerceAtLeast(1)
        val cx = x.roundToInt().coerceIn(sensor.left + half, sensor.right - half)
        val cy = y.roundToInt().coerceIn(sensor.top + half, sensor.bottom - half)
        return MeteringRectangle(cx - half, cy - half, 2 * half, 2 * half,
            MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun finishFocus(ticket: Int, revision: Int, text: String) {
        if (ticket != generation || revision != focusRevision) return
        focusRevision++
        marker.visibility = View.INVISIBLE
        message.text = text
        val builder = request ?: return
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        try { session?.setRepeatingRequest(builder.build(), null, main) }
        catch (_: CameraAccessException) { }
        catch (_: IllegalStateException) { }
    }

    private fun onQr(ticket: Int, text: String) {
        if (ticket != generation || !resumed || isFinishing) return
        if (runCatching { Pairing.parse(text) }.isFailure) {
            message.text = "Наведите камеру на QR-код из окна Scan Engine"
            return
        }
        setResult(RESULT_OK, Intent().putExtra(CODE, text))
        finish()
    }

    private fun cameraError(busy: Boolean) {
        closeCamera()
        if (busy && resumed && retryCount++ < 5) {
            val ticket = generation
            main.postDelayed({ if (ticket == generation) open() }, 250)
        } else {
            message.text = "QR-камера недоступна. Можно ввести код вручную."
        }
    }

    private fun closeCamera() {
        generation++
        opening = false
        hasTransform = false
        marker.visibility = View.INVISIBLE
        session?.close()
        session = null
        camera?.close()
        camera = null
        request = null
        previewSurface?.release()
        previewSurface = null
        reader?.let { imageReader ->
            imageReader.setOnImageAvailableListener(null, null)
            decodeHandler.post { imageReader.close() }
        }
        reader = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val CODE = "pairing_code"
        const val MANUAL = "pairing_manual"
    }
}
