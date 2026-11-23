package dev.hybrid.scanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScannerActivity : Activity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: CameraRenderer
    private lateinit var depthPreview: ImageView
    private lateinit var geometry: GeometryView
    private lateinit var info: TextView
    private lateinit var engine: TextView
    private lateinit var modeLabel: TextView
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var startButton: Button
    private lateinit var scans: File
    private var ar: Session? = null
    private var surfaceRunning = false
    private var installRequested = false
    private var resumeRetries = 0
    @Volatile private var foreground = false
    @Volatile private var cameraReady = false
    @Volatile private var supportsDepth = false
    @Volatile private var mode = "RGB"
    @Volatile private var active: ScanStore? = null
    @Volatile private var paired: ScanNetwork? = null
    @Volatile private var engineLabel = "ПК не подключён"
    @Volatile private var trackingLabel = "Камера запускается…"
    @Volatile private var failure = ""
    @Volatile private var depthQuality = "—"
    private val disk = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadScheduledExecutor()
    private var capturedAt = 0L
    private var depthPreviewAt = 0L
    private var attemptedTimestamp = 0L
    private var previousDepth = 0L
    private var lastStatusAt = 0L
    private var currentCaptureId: String? = null
    private var synchronizedSession: String? = null
    private var synchronizedRevision = -1
    @Volatile private var sensorAngle = 90
    private var networkSeen: ScanNetwork? = null
    private var pointsRevision = -1
    private var meshRevision = -1
    @Volatile private var previewSession: String? = null
    @Volatile private var offlinePreview: File? = null
    private var saveSource: File? = null
    private var backoffUntil = 0L
    private var retryMs = 300L
    private var lastError = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scans = File(filesDir, "scans").apply { mkdirs() }
        setup()
        net.scheduleWithFixedDelay({ if (foreground) pump() }, 100, 155, TimeUnit.MILLISECONDS)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun setup() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff101c2a.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val heading = label("3D Scanner", 24)
        root.addView(heading)
        engine = label(engineLabel, 14)
        root.addView(engine)
        root.addView(row(button("QR") {
            openQr()
        }, button("Код ПК") { pairDialog() }, button("Сеансы") { showScans() }, button("О приложении") { about() }))
        val view = FrameLayout(this)
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }
        renderer = CameraRenderer({ windowManager.defaultDisplay.rotation }, ::cameraFrame) { error ->
            cameraReady = false
            trackingLabel = "Камера: ${error.javaClass.simpleName}. Перезапустите приложение."
            runOnUiThread { refresh() }
        }
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        view.addView(surface)
        depthPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xff0c1520.toInt())
            visibility = View.GONE
        }
        view.addView(depthPreview)
        geometry = GeometryView(this).apply { visibility = View.GONE }
        view.addView(geometry)
        modeLabel = label("RGB", 13).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xe6192939.toInt())
        }
        view.addView(modeLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row(button("RGB") { setMode("RGB") }, button("Depth") { setMode("Depth") },
            button("Точки") { setMode("Points") }, button("Mesh") { setMode("Mesh") }))
        info = label("", 13)
        root.addView(info)
        startButton = button("Начать") { startScan() }
        pauseButton = button("Пауза") {
            active?.let { scan ->
                scan.setLocalPhase(if (scan.phase == Phase.PAUSED) Phase.SCANNING else Phase.PAUSED)
            }
            refresh()
        }
        stopButton = button("Завершить") {
            active?.setLocalPhase(Phase.STOPPED)
            refresh()
        }
        root.addView(row(startButton, pauseButton, stopButton))
        setContentView(root)
        refresh()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(Color.WHITE)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        minimumWidth = 0
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { action() }
    }
    private fun row(vararg controls: View) = LinearLayout(this).apply {
        for (control in controls) addView(control, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        foreground = true
        resumeRetries = 0
        openCamera()
    }

    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 100) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else {
            trackingLabel = "Разрешите камеру в настройках Android"
            refresh()
        }
    }

    private fun openCamera() {
        if (!foreground || surfaceRunning ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (ar == null) {
                val arCore = ArCoreApk.getInstance()
                val availability = arCore.checkAvailability(this)
                if (availability.isTransient) {
                    surface.postDelayed({ openCamera() }, 350)
                    return
                }
                if (!availability.isSupported) {
                    trackingLabel = "ARCore недоступен на этом устройстве"
                    refresh()
                    return
                }
                if (arCore.requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    trackingLabel = "Установите или обновите Сервисы Google Play для AR"
                    refresh()
                    return
                }
                val session = Session(this)
                ar = session
                val config = Config(session)
                supportsDepth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                if (supportsDepth) config.depthMode = Config.DepthMode.AUTOMATIC
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                session.configure(config)
                sensorAngle = runCatching {
                    val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
                    manager.getCameraCharacteristics(session.cameraConfig.cameraId)
                        .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                }.getOrNull() ?: 90
            }
            val session = requireNotNull(ar)
            session.resume()
            renderer.session = session
            surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            surface.onResume()
            surfaceRunning = true
            cameraReady = true
            refresh()
        } catch (error: Exception) {
            cameraReady = false
            trackingLabel = "ARCore: ${error.javaClass.simpleName}"
            if (error is com.google.ar.core.exceptions.CameraNotAvailableException && resumeRetries++ < 5) {
                surface.postDelayed({ openCamera() }, 250)
            }
            refresh()
        }
    }

    override fun onPause() {
        foreground = false
        cameraReady = false
        if (surfaceRunning) {
            surface.onPause()
            renderer.session = null
            ar?.pause()
            surfaceRunning = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        net.shutdownNow()
        paired?.close()
        disk.shutdown()
        ar?.close()
        super.onDestroy()
    }

    private fun refresh() {
        val scan = active
        engine.text = engineLabel
        val phase = when (scan?.phase) {
            Phase.SCANNING -> "Сканирование"
            Phase.PAUSED -> "Пауза"
            Phase.STOPPED -> "Завершён"
            null -> "Готовность"
        }
        info.text = "$phase · $trackingLabel\nDepth: $depthQuality · Записано ${scan?.saved?.get() ?: 0}" +
            " · ACK ${scan?.acknowledged?.get() ?: 0}" + if (failure.isEmpty()) "" else "\n$failure"
        startButton.isEnabled = cameraReady && supportsDepth && (scan == null || scan.phase == Phase.STOPPED)
        pauseButton.isEnabled = scan != null && scan.phase != Phase.STOPPED
        stopButton.isEnabled = pauseButton.isEnabled
        pauseButton.text = if (scan?.phase == Phase.PAUSED) "Продолжить" else "Пауза"
    }

    private fun about() {
        AlertDialog.Builder(this).setTitle("Hybrid 3D Scanner")
            .setMessage("Камера и глубина используются для сканирования. Кадры хранятся на телефоне и передаются только выбранному ПК по локальной сети. ARCore предоставляется Google, его использование регулируется Условиями Google и Политикой конфиденциальности Google.")
            .setPositiveButton("Условия Google") { _, _ -> openPolicy("https://policies.google.com/terms") }
            .setNeutralButton("Конфиденциальность") { _, _ -> openPolicy("https://policies.google.com/privacy") }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun openPolicy(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { toast(url) }
    }

    private fun setMode(value: String) {
        mode = value
        previewSession = null
        modeLabel.text = when (value) {
            "Depth" -> "Raw Depth · один кадр"
            "Points" -> "Накопленное облако · с ПК"
            "Mesh" -> "Поверхность TSDF · с ПК"
            else -> "RGB"
        }
        depthPreview.visibility = if (mode == "Depth") View.VISIBLE else View.GONE
        geometry.visibility = if (mode == "Points" || mode == "Mesh") View.VISIBLE else View.GONE
        geometry.geometry = null
        val local = offlinePreview
        if (local != null && value in setOf("Points", "Mesh")) {
            val mesh = value == "Mesh"
            modeLabel.text = if (mesh) "Сохранённая поверхность" else "Сохранённое облако"
            disk.execute {
                val data = runCatching {
                    val file = File(local, if (mesh) "mesh.json" else "points.json")
                    Geometry.parse(JSONObject(file.readText()), mesh)
                }.getOrNull()
                runOnUiThread {
                    if (mode == value && offlinePreview == local) {
                        geometry.geometry = data
                        if (data == null) toast("Файл предпросмотра пока не сохранён")
                    }
                }
            }
        }
    }

    private fun pairDialog() {
        val input = EditText(this).apply {
            hint = "h3s1:IP:порт:токен"
            isSingleLine = true
        }
        AlertDialog.Builder(this).setTitle("Код подключения к ПК").setView(input)
            .setPositiveButton("Подключить") { _, _ -> connect(input.text.toString()) }
            .setNegativeButton("Отмена", null).show()
    }

    @Suppress("DEPRECATION")
    private fun openQr() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(Intent(this, QrActivity::class.java), 102)
        } else {
            pairDialog()
        }
    }

    private fun connect(text: String) {
        try {
            val pair = Pairing.parse(text)
            val old = paired
            paired = ScanNetwork(pair)
            old?.close()
            engineLabel = "${pair.host}:${pair.port} · подключение"
            failure = ""
        } catch (error: IllegalArgumentException) {
            toast(error.message.orEmpty())
        }
        refresh()
    }

    private fun startScan() {
        val previous = active
        if (previous?.writing?.get() == true) return toast("Завершается запись последнего кадра")
        if (previous != null && paired != null && !previous.finalizedOnPc) {
            return toast("Дождитесь завершения текущего сеанса на ПК")
        }
        createScan(UUID.randomUUID().toString())
    }

    private fun createScan(id: String) {
        offlinePreview = null
        active = ScanStore(File(scans, id), Phase.SCANNING)
        setMode(mode)
        failure = ""
        refresh()
    }

    private fun cameraFrame(frame: Frame) {
        val now = SystemClock.elapsedRealtime()
        val tracking = frame.camera.trackingState
        if (now-lastStatusAt > 400) {
            trackingLabel = if (!supportsDepth) "Depth API недоступен" else if (tracking == TrackingState.TRACKING)
                "Tracking" else "Tracking: ${frame.camera.trackingFailureReason.name}"
            lastStatusAt = now
            runOnUiThread { refresh() }
        }
        if (!supportsDepth || tracking != TrackingState.TRACKING) return
        val scan = active
        if (scan == null || scan.phase != Phase.SCANNING) {
            showDepthThrottled(frame, now)
            return
        }
        // A new Raw Depth estimate can be fresh for only one ARCore frame.
        // Check each distinct AR frame after the interval since the last saved measurement.
        if (now-capturedAt < 95 || frame.timestamp == attemptedTimestamp) return
        attemptedTimestamp = frame.timestamp
        if (scan.id != currentCaptureId) {
            currentCaptureId = scan.id
            previousDepth = 0
        }
        if (scan.saved.get() >= 5000 || scan.bytes >= ScanStore.LIMIT) {
            scan.setLocalPhase(Phase.STOPPED)
            failure = "Достигнут лимит хранения сеанса"
            return
        }
        if (!scan.writing.compareAndSet(false, true)) return
        val measurement = try {
            capture(frame, scan.id, scan.next.get(), previousDepth)
        } catch (_: NotYetAvailableException) {
            null
        } catch (error: Exception) {
            failure = "RGB-D: ${error.message}"
            null
        }
        if (measurement == null) {
            scan.writing.set(false)
            showDepthThrottled(frame, now)
            return
        }
        capturedAt = now
        previousDepth = measurement.metadata.getLong("timestamp_ns")
        scan.next.incrementAndGet()
        disk.execute {
            try {
                scan.save(measurement)
                val values = measurement.confidence
                val good = values.count { (it.toInt() and 255) >= 96 }
                depthQuality = "${100 * good / values.size}% уверенных"
                if (mode == "Depth") renderDepth(measurement.depth, values,
                    measurement.metadata.getInt("width"), measurement.metadata.getInt("height"))
            } catch (error: Exception) {
                scan.setLocalPhase(Phase.PAUSED)
                failure = "Локальная запись: ${error.message}"
            } finally {
                scan.writing.set(false)
            }
        }
    }

    private fun showDepthThrottled(frame: Frame, now: Long) {
        if (mode != "Depth" || now-depthPreviewAt < 125) return
        depthPreviewAt = now
        try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                frame.acquireRawDepthConfidenceImage().use { conf ->
                    renderDepth(plane(depth, 0, depth.width, depth.height, 2),
                        plane(conf, 0, depth.width, depth.height), depth.width, depth.height)
                }
            }
        } catch (_: NotYetAvailableException) {
            // Wait for ARCore to provide depth.
        }
    }

    private fun renderDepth(depth: ByteArray, confidence: ByteArray, width: Int, height: Int) {
        val colors = IntArray(width*height)
        for (i in colors.indices) {
            val distance = (depth[2*i].toInt() and 255) or ((depth[2*i+1].toInt() and 255) shl 8)
            val c = confidence[i].toInt() and 255
            val t = ((distance-500)*255/2500).coerceIn(0, 255)
            colors[i] = if (distance == 0 || c < 96) Color.BLACK else Color.rgb(255-t, c, t)
        }
        val native = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val upright = Bitmap.createBitmap(native, 0, 0, width, height,
            android.graphics.Matrix().apply { postRotate(sensorAngle.toFloat()) }, true)
        runOnUiThread { if (mode == "Depth") depthPreview.setImageBitmap(upright) }
    }

    private fun pump() {
        val link = paired ?: return
        if (networkSeen !== link) {
            networkSeen = link
            synchronizedSession = null
            pointsRevision = -1
            meshRevision = -1
            backoffUntil = 0
            retryMs = 300
        }
        if (SystemClock.elapsedRealtime() < backoffUntil) return
        try {
            var state = link.state()
            val scan = active
            val sid = state.optString("session")
            val waiting = scan == null || scan.id != sid && scan.finalizedOnPc
            if (waiting && cameraReady && supportsDepth && state.optString("phase") == "scanning") {
                val remoteId = UUID.fromString(sid).toString()
                if (!File(scans, remoteId).exists()) {
                    runOnUiThread { if (active === scan) createScan(remoteId) }
                }
            } else if (scan != null) {
                state = sync(link, scan, state)
            }
            if (paired !== link) return
            engineLabel = "Scan Engine 0.1 · ${state.optString("phase")} · Fusion ${state.optInt("integrated")}" +
                " · ${state.optDouble("integration_ms", 0.0)} мс"
            val engineError = state.optString("error")
            if (engineError.isNotEmpty()) setNetworkError(engineError)
            else clearNetworkError()
            if (offlinePreview == null && (mode == "Points" || mode == "Mesh")) showGeometry(link, state)
            retryMs = 300
        } catch (error: Exception) {
            if (paired !== link || !foreground) return
            val queueFull = error is HttpFailure && error.code == 429
            engineLabel = if (queueFull) "Очередь ПК занята · кадры на телефоне" else "Ожидание Scan Engine"
            setNetworkError(error.message ?: error.javaClass.simpleName)
            if (error is HttpFailure && error.code == 507) {
                active?.takeIf { it.phase == Phase.SCANNING }?.setLocalPhase(Phase.PAUSED)
            }
            backoffUntil = SystemClock.elapsedRealtime() + retryMs
            retryMs = (retryMs * 2).coerceAtMost(4000)
        }
        runOnUiThread { refresh() }
    }

    private fun setNetworkError(message: String) {
        lastError = message
        failure = message
    }

    private fun clearNetworkError() {
        if (failure == lastError) failure = ""
        lastError = ""
    }

    private fun sync(link: ScanNetwork, scan: ScanStore, initial: JSONObject): JSONObject {
        var state = initial
        var remotePhase = state.optString("phase")
        val switched = synchronizedSession != scan.id
        if (state.optString("session") != scan.id) {
            state = link.command("scan.start", scan.id)
            remotePhase = state.optString("phase")
        }
        if (remotePhase == "restoring" || remotePhase == "error") return state
        if (switched) {
            if (state.optInt("accepted") < scan.acknowledged.get()) scan.resetAcknowledged()
            synchronizedSession = scan.id
            synchronizedRevision = -1
        }
        if (remotePhase == "stopping" && scan.phase != Phase.STOPPED) scan.setLocalPhase(Phase.STOPPED)
        if (remotePhase != "stopped") scan.finalizedOnPc = false
        val revision = scan.revision.get()
        var pending = scan.pending()
        if (revision != synchronizedRevision || (remotePhase == "stopped" && pending.isNotEmpty())) {
            val command = when {
                remotePhase == "stopped" && (pending.isNotEmpty() || scan.phase != Phase.STOPPED) -> "scan.resume"
                scan.phase == Phase.SCANNING && remotePhase == "paused" -> "scan.resume"
                scan.phase == Phase.PAUSED && remotePhase == "scanning" -> "scan.pause"
                scan.phase == Phase.STOPPED && pending.isEmpty() && !scan.writing.get() &&
                    remotePhase != "stopped" -> "scan.stop"
                else -> null
            }
            if (command != null) {
                state = link.command(command, scan.id)
                remotePhase = state.optString("phase")
            }
            if (scan.phase != Phase.STOPPED || remotePhase == "stopped") synchronizedRevision = revision
        } else if (scan.revision.get() == revision) {
            val updated = when (remotePhase) {
                "paused" -> Phase.PAUSED
                "stopped" -> Phase.STOPPED
                else -> Phase.SCANNING
            }
            scan.phase = updated
        }
        if (remotePhase in setOf("scanning", "paused", "stopping")) {
            for (file in pending.take(3)) {
                if (paired !== link || active !== scan) break
                link.upload(scan.id, file)
                scan.acknowledge(file)
            }
            if (scan.phase == Phase.STOPPED && !scan.writing.get()) {
                pending = scan.pending()
                if (pending.isEmpty()) {
                    state = link.command("scan.stop", scan.id)
                    synchronizedRevision = scan.revision.get()
                }
            }
        }
        scan.finalizedOnPc = state.optString("phase") == "stopped" &&
            state.optInt("pending") == 0 && !scan.writing.get() && scan.pending().isEmpty()
        if (scan.finalizedOnPc && !File(scan.folder, "preview.ready").exists()) {
            for (kind in listOf("points", "mesh")) {
                val geometry = link.get("/v1/preview/$kind")
                if (geometry.optString("session") != scan.id) return state
                val target = File(scan.folder, "$kind.json")
                val temp = File(scan.folder, "$kind.json.tmp")
                temp.writeText(geometry.toString())
                check(temp.renameTo(target))
            }
            File(scan.folder, "preview.ready").createNewFile()
        }
        return state
    }

    private fun showGeometry(link: ScanNetwork, state: JSONObject) {
        val selected = mode
        val mesh = selected == "Mesh"
        val sid = state.optString("session")
        val key = if (mesh) "mesh_revision" else "point_revision"
        val expected = if (mesh) maxOf(state.optInt(key), link.meshNotice) else state.optInt(key)
        val old = if (mesh) meshRevision else pointsRevision
        val loaded = previewSession == "$sid:$selected" && old == expected
        if (loaded) return
        val json = link.get(if (mesh) "/v1/preview/mesh" else "/v1/preview/points")
        if (json.optString("session") != sid) return
        val data = Geometry.parse(json, mesh)
        if (mesh) meshRevision = json.getInt("revision") else pointsRevision = json.getInt("revision")
        previewSession = "$sid:$selected"
        runOnUiThread { if (mode == selected && paired === link) geometry.geometry = data }
    }

    private fun showScans() {
        val folders = scans.listFiles { file -> file.isDirectory && runCatching {
            UUID.fromString(file.name).toString() == file.name
        }.getOrDefault(false) }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (folders.isEmpty()) return toast("Сохранённых сеансов пока нет")
        val date = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val labels = folders.map { "${date.format(java.util.Date(it.lastModified()))} · ${it.name.take(8)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Локальные сеансы").setItems(labels) { _, index ->
            sessionActions(folders[index])
        }.show()
    }

    private fun sessionActions(folder: File) {
        val actions = arrayOf("Передать / восстановить на ПК", "Открыть локальный предпросмотр",
            "Сохранить PLY", "Сохранить GLB", "Удалить локальные кадры")
        AlertDialog.Builder(this).setTitle(folder.name.take(8)).setItems(actions) { _, index ->
            when (index) {
                0 -> {
                    val current = active
                    if (current != null && (current.phase != Phase.STOPPED || current.writing.get())) {
                        toast("Сначала завершите текущий захват")
                    } else {
                        active = if (current?.id == folder.name) current else ScanStore(folder, Phase.STOPPED)
                        offlinePreview = null
                        setMode("Points")
                        refresh()
                    }
                }
                1 -> {
                    if (File(folder, "preview.ready").isFile) {
                        offlinePreview = folder
                        setMode("Points")
                    } else toast("Сначала завершите реконструкцию на ПК")
                }
                2 -> export(folder, "ply")
                3 -> export(folder, "glb")
                4 -> AlertDialog.Builder(this).setMessage("Удалить этот сеанс с телефона?")
                    .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                        if (active?.id == folder.name) toast("Сначала выберите другой сеанс или перезапустите приложение")
                        else { folder.deleteRecursively(); toast("Сеанс удалён с телефона") }
                    }.show()
            }
        }.show()
    }

    private fun export(folder: File, format: String) {
        val destination = File(folder, "scan.$format")
        if (destination.isFile) return chooseDestination(destination)
        val link = paired ?: return toast("Подключите Scan Engine")
        if (active?.id != folder.name || active?.phase != Phase.STOPPED) {
            return toast("Передайте этот сеанс на ПК и завершите его")
        }
        net.execute {
            try {
                link.command("scan.export", folder.name, format)
                var remaining = 20
                fun checkExport() {
                    try {
                        link.download(folder.name, format, destination)
                        runOnUiThread { chooseDestination(destination) }
                    } catch (error: HttpFailure) {
                        val status = runCatching { link.get("/v1/status").optString("error") }.getOrDefault("")
                        if (error.code == 404 && status.isEmpty() && --remaining > 0) {
                            net.schedule({ checkExport() }, 500, TimeUnit.MILLISECONDS)
                        } else runOnUiThread { toast(status.ifEmpty { error.message.orEmpty() }) }
                    } catch (error: Exception) {
                        runOnUiThread { toast(error.message.orEmpty()) }
                    }
                }
                checkExport()
            } catch (error: Exception) {
                runOnUiThread { toast(error.message.orEmpty()) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseDestination(file: File) {
        saveSource = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "scan-${file.parentFile?.name?.take(8)}.${file.extension}")
        }, 101)
    }

    @Deprecated("Platform result callback")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 102) {
            if (result == RESULT_OK && data != null) {
                if (data.getBooleanExtra(QrActivity.MANUAL, false)) pairDialog()
                else data.getStringExtra(QrActivity.CODE)?.let(::connect)
            }
            return
        }
        val source = saveSource ?: return
        if (request != 101 || result != RESULT_OK) return
        val uri = data?.data ?: return
        disk.execute {
            try {
                requireNotNull(contentResolver.openOutputStream(uri)).use { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
                runOnUiThread { toast("Экспорт сохранён") }
            } catch (error: IOException) {
                runOnUiThread { toast(error.message.orEmpty()) }
            }
        }
    }
}
