package io.github.dmulder.glashutte

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import io.github.dmulder.glashutte.glasses.GlassesUsb
import io.github.dmulder.glashutte.glasses.InputDiagnostics
import io.github.dmulder.glashutte.glasses.InputDiagnostics.Path
import io.github.dmulder.glashutte.glasses.InputExperiments
import io.github.dmulder.glashutte.privileged.PrivilegedService
import io.github.dmulder.glashutte.glasses.LatencyExperiments
import io.github.dmulder.glashutte.glasses.PredictionMode
import io.github.dmulder.glashutte.glasses.ProjectionExperiments
import io.github.dmulder.glashutte.glasses.TrackingExperiments
import io.github.dmulder.glashutte.spatial.WorkspaceController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Separate launcher entry ("Glashutte Diagnostics") showing which stage of the head-tracking
 * pipeline fails, with a one-tap log export. Everything it shows lives in
 * [GlassesDiagnostics], so it is the same data the in-glasses overlay and `GlashutteDiag`
 * logcat lines carry.
 */
class DiagnosticsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val drm = DrmSection(this)
    private lateinit var reportView: TextView
    private lateinit var helperView: TextView
    private lateinit var hereView: TextView
    private val pathViews = LinkedHashMap<Path, TextView>()
    private var keysSeenHere = 0
    private var lastKeyHere = "none"
    private var mouseEventsSeenHere = 0
    private var tick = 0
    private val refresh = object : Runnable {
        override fun run() {
            if (tick++ % 2 == 0 && PrivilegedService.state == PrivilegedService.State.READY) {
                PrivilegedService.refreshHelperReport()
            }
            renderInputSummary()
            reportView.text = GlassesDiagnostics.report(includeEvents = true, includeSdkLog = false)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlassesDiagnostics.init(applicationContext)
        LatencyExperiments.init(applicationContext)
        GlassesUsb.inventory(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 16, 20))
            setPadding(dp(12), dp(32), dp(12), dp(8))
        }

        root.addView(label("Glashutte diagnostics", 18f, bold = true))
        addInputSection(root)
        drm.addTo(root)
        root.addView(label("Head tracking: open this while the glasses are connected and the workspace is up, wait ~15 s, then Share log.", 12f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Share log") { shareLog() }, weighted())
        buttons.addView(button("Copy") { copyReport() }, weighted())
        buttons.addView(button("Restart tracking") { restartTracking() }, weighted())
        root.addView(buttons)

        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons2.addView(button("Camera permission") { requestCamera() }, weighted())
        buttons2.addView(button("Probe USB") { probeUsb() }, weighted())
        root.addView(buttons2)

        root.addView(label("Experiments (apply on Restart tracking; defaults match upstream UxSpace)", 12f, bold = true))
        root.addView(pathSpinner())
        root.addView(
            check("Pass an app cache dir to SDK initialize() (calibration)", TrackingExperiments.initCacheDir(this)) {
                TrackingExperiments.setInitCacheDir(this, it)
            },
        )
        root.addView(
            check("Show diagnostics overlay on the glasses", TrackingExperiments.glassesOverlay(this)) {
                TrackingExperiments.setGlassesOverlay(this, it)
            },
        )
        root.addView(
            check(
                "Calibrated projection: real glasses FOV, zoom scales the scene not the projection " +
                    "(OFF = legacy 55° projection zoom; applies live)",
                ProjectionExperiments.calibratedProjection(this),
            ) {
                ProjectionExperiments.setCalibratedProjection(this, it)
                GlassesDiagnostics.i(TAG, "diagnostics screen: calibrated projection set to $it")
            },
        )

        root.addView(
            label(
                "Latency experiments (apply live, no restart; watch the 'latency:' line under " +
                    "logcat tag GlashutteDiag. Defaults: pull-on-render ON, prediction OFF, highest refresh ON)",
                12f, bold = true,
            ),
        )
        val latency = LatencyExperiments.current()
        root.addView(
            check("Atomic pose, pulled on the render thread each frame", latency.pullOnRender) { on ->
                LatencyExperiments.update(this) { it.copy(pullOnRender = on) }
            },
        )
        root.addView(predictionModeSpinner())
        root.addView(predictionAmountSpinner())
        root.addView(
            check("Select the glasses display's highest refresh rate", latency.highestRefresh) { on ->
                LatencyExperiments.update(this) { it.copy(highestRefresh = on) }
            },
        )

        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.rgb(220, 220, 220))
            setTextIsSelectable(true)
        }
        root.addView(reportView)
        // The screen has grown well past one page, so the whole thing scrolls.
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onResume() {
        super.onResume()
        drm.onResume()
        handler.post(refresh)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            keysSeenHere++
            lastKeyHere = "${KeyEvent.keyCodeToString(event.keyCode)} from '${event.device?.name ?: "?"}'"
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) mouseEventsSeenHere++
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * The three input tests. Each starts a clean, labelled test of one path, sends the user to
     * the Glashutte screen to exercise it, and shows the first stage that fails. Helper
     * connection is the first question for every path, so it heads the section.
     */
    private fun addInputSection(root: LinearLayout) {
        root.addView(label("INPUT: is the helper connected, and which paths work?", 14f, bold = true))
        helperView = label("", 12f, bold = true)
        root.addView(helperView)
        root.addView(
            label(
                "Test one path at a time: tap its Test button (this opens Glashutte), use only that " +
                    "device for ~20 s with the cursor over an app window, come back here, then Share that path's log.",
                11f,
            ),
        )
        Path.values().forEach { path ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Test: ${shortName(path)}") { startInputTest(path) }, weighted())
            row.addView(button("Share ${shortName(path)} log") { sharePathLog(path) }, weighted())
            root.addView(row)
            val view = label("", 11f)
            pathViews[path] = view
            root.addView(view)
        }
        hereView = label("", 11f)
        root.addView(hereView)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button("Helper setup") { openGlashutte() }, weighted())
        tools.addView(button("Injection self-test") { injectionSelfTest() }, weighted())
        tools.addView(button("Dump windows") { dumpDisplays() }, weighted())
        root.addView(tools)

        root.addView(label("Input switches (defaults are what the app ships with)", 12f, bold = true))
        root.addView(strategySpinner())
        root.addView(
            check(
                "Wait for the input dispatcher's verdict on each injected event " +
                    "(a dropped click is reported instead of silently ignored)",
                InputExperiments.waitForResult(this),
            ) {
                InputExperiments.setWaitForResult(this, it)
                PrivilegedService.pushInputConfig()
            },
        )
        root.addView(
            check(
                "New app displays get their own window focus (OWN_FOCUS; applies to windows opened afterwards)",
                InputExperiments.ownFocusFlag(this),
            ) {
                InputExperiments.setOwnFocusFlag(this, it)
                PrivilegedService.pushInputConfig()
            },
        )
        root.addView(
            check(
                "Send Bluetooth keyboard keys to the app window last clicked",
                InputExperiments.forwardPhysicalKeys(this),
            ) { InputExperiments.setForwardPhysicalKeys(this, it) },
        )
        root.addView(
            check(
                "Mouse fallback: drive the cursor from the phone window when the helper's raw mouse path is silent",
                InputExperiments.mousePhoneWindowFallback(this),
            ) { InputExperiments.setMousePhoneWindowFallback(this, it) },
        )
    }

    private fun shortName(path: Path) = when (path) {
        Path.TRACKPAD -> "trackpad"
        Path.MOUSE -> "mouse"
        Path.KEYBOARD -> "keyboard"
    }

    private fun renderInputSummary() {
        val ready = InputDiagnostics.isHelperReady()
        helperView.text = InputDiagnostics.helperLine()
        helperView.setTextColor(if (ready) Color.rgb(120, 220, 120) else Color.rgb(255, 120, 120))
        pathViews.forEach { (path, view) ->
            val status = InputDiagnostics.worstStatus(path)
            view.text = "${if (InputDiagnostics.activeTest() == path) "> " else ""}${path.title}: ${InputDiagnostics.verdict(path)}"
            view.setTextColor(
                when (status) {
                    InputDiagnostics.Status.OK -> Color.rgb(120, 220, 120)
                    InputDiagnostics.Status.PENDING -> Color.rgb(200, 200, 200)
                    InputDiagnostics.Status.WARN -> Color.rgb(255, 200, 100)
                    InputDiagnostics.Status.FAIL -> Color.rgb(255, 120, 120)
                },
            )
        }
        hereView.text = "Events this Diagnostics screen itself receives (proves Android delivers the device): " +
            "keyboard presses=$keysSeenHere (last: $lastKeyHere), mouse events=$mouseEventsSeenHere"
        hereView.setTextColor(Color.rgb(170, 170, 200))
    }

    private fun startInputTest(path: Path) {
        InputDiagnostics.beginTest(path)
        PrivilegedService.refreshHelperReport()
        toast("Test started: ${path.title}")
        openGlashutte()
    }

    private fun openGlashutte() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Inject a harmless tap into the top-left corner of every Glashutte app display, bypassing
     * the cursor and hit-test entirely. If this is accepted but real clicks do nothing, the
     * fault is upstream of injection (cursor, projection, routing); if it fails, it is the
     * helper or the dispatcher.
     */
    private fun injectionSelfTest() {
        val displays = WorkspaceController.glashutteDisplayIdList()
        if (displays.isEmpty()) {
            toast("No app windows open. Open an app in the workspace first.")
            return
        }
        displays.forEach { id ->
            PrivilegedService.injectTouch(id, 2, 2, 0)
            handler.postDelayed({ PrivilegedService.injectTouch(id, 2, 2, 2) }, 60)
        }
        GlassesDiagnostics.i(TAG, "diagnostics screen: injection self-test sent to displays $displays")
        toast("Self-test tap sent to display(s) $displays; see the result under 'Dispatcher accepted'")
    }

    private fun dumpDisplays() {
        val displays = WorkspaceController.glashutteDisplayIdList()
        if (displays.isEmpty()) {
            toast("No app windows open.")
            return
        }
        Thread {
            val text = displays.joinToString("\n\n") { "### display $it\n" + PrivilegedService.describeDisplay(it) }
            InputDiagnostics.displayDump(text)
            handler.post { toast("Window dump added to the report") }
        }.start()
    }

    private fun strategySpinner(): View {
        val values = InputExperiments.Strategy.values()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values.map { "Injection route: ${it.label}" },
        )
        spinner.setSelection(values.indexOf(InputExperiments.strategy(this)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (values[pos] != InputExperiments.strategy(this@DiagnosticsActivity)) {
                    InputExperiments.setStrategy(this@DiagnosticsActivity, values[pos])
                    PrivilegedService.pushInputConfig()
                    GlassesDiagnostics.i(TAG, "diagnostics screen: injection route set to ${values[pos]}")
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        return spinner
    }

    private fun refreshHelperReportBlocking() {
        val latch = CountDownLatch(1)
        PrivilegedService.refreshHelperReport { latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun sharePathLog(path: Path) {
        Thread {
            try {
                refreshHelperReportBlocking()
                val dir = File(cacheDir, "diag").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "glashutte-input-${shortName(path)}-$stamp.txt")
                val sb = StringBuilder()
                sb.append("=== Glashutte input test: ${path.title} ===\n")
                sb.append("time=").append(java.time.OffsetDateTime.now()).append('\n')
                sb.append(GlassesDiagnostics.report(includeEvents = false, includeSdkLog = false).lineSequence().take(6).joinToString("\n")).append("\n\n")
                sb.append(InputDiagnostics.pathReport(path))
                sb.append("\n-- logcat (app process, input related) --\n")
                sb.append(readLogcat(inputOnly = true))
                file.writeText(sb.toString())
                val uri = FileProvider.getUriForFile(this, "$packageName.diagfiles", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Glashutte input test: ${path.title}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                handler.post { startActivity(Intent.createChooser(send, "Share ${path.title} log")) }
            } catch (t: Throwable) {
                handler.post { toast("Export failed: $t") }
            }
        }.start()
    }

    override fun onPause() {
        drm.onPause()
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun fullLog(): String {
        refreshHelperReportBlocking()
        val sb = StringBuilder(GlassesDiagnostics.report(includeEvents = true, includeSdkLog = true))
        sb.append(drm.reportText())
        sb.append("\n\n-- logcat (this app, tracking-related tags) --\n")
        sb.append(readLogcat())
        return sb.toString()
    }

    private fun readLogcat(inputOnly: Boolean = false): String = try {
        val proc = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-t", "6000",
            "GlashutteDiag:V", "Glashutte/Native:V", "Glashutte/SdkLog:V", "Glashutte/Tracking:V",
            "Glashutte/VitureSdk:V", "Glashutte/Usb:V", "Glashutte/Main:V", "Glashutte/Display:V",
            "Glashutte/Privileged:V", "Glashutte/Mouse:V", "Glashutte/Launch:V", "Glashutte/Renderer:I",
            "TrackpadView:I", "libglasses:V", "*:S",
        ).redirectErrorStream(true).start()
        var text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        if (inputOnly) {
            val keep = Regex("input|click:|inject|Privileged|Mouse|Trackpad|handleClick|hotkey|helper", RegexOption.IGNORE_CASE)
            text = text.lineSequence().filter { keep.containsMatchIn(it) }.joinToString("\n")
        }
        text.ifBlank { "(logcat returned nothing)" }
    } catch (t: Throwable) {
        "(logcat unavailable: $t)"
    }

    private fun shareLog() {
        Thread {
            try {
                val dir = File(cacheDir, "diag").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "glashutte-diag-$stamp.txt")
                file.writeText(fullLog())
                val uri = FileProvider.getUriForFile(this, "$packageName.diagfiles", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Glashutte head-tracking diagnostics")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                handler.post {
                    startActivity(Intent.createChooser(send, "Share Glashutte diagnostics"))
                }
            } catch (t: Throwable) {
                handler.post { toast("Export failed: $t") }
            }
        }.start()
    }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("glashutte-diag", GlassesDiagnostics.report()))
        toast("Report copied")
    }

    private fun restartTracking() {
        val retry = WorkspaceController.retryHeadTracking
        if (retry == null) {
            toast("Workspace not running (no glasses display). Connect the glasses first.")
            return
        }
        GlassesDiagnostics.i(TAG, "diagnostics screen: restart tracking requested")
        retry.invoke()
        toast("Restarting head tracking")
    }

    private fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            toast("CAMERA already granted")
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            GlassesDiagnostics.i(TAG, "CAMERA permission result granted=$ok")
            toast(if (ok) "CAMERA granted" else "CAMERA denied")
            if (ok) WorkspaceController.retryHeadTracking?.invoke()
        }
    }

    private fun probeUsb() {
        val lines = GlassesUsb.inventory(applicationContext)
        GlassesDiagnostics.i(TAG, "USB probe: ${lines.size} device(s)")
        toast("${lines.size} USB device(s) listed in report")
    }

    private fun pathSpinner(): View {
        val values = TrackingExperiments.PathOverride.values()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values.map { "Tracking path: ${it.label}" },
        )
        spinner.setSelection(values.indexOf(TrackingExperiments.pathOverride(this)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                TrackingExperiments.setPathOverride(this@DiagnosticsActivity, values[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        return spinner
    }

    private fun predictionModeSpinner(): View {
        val values = PredictionMode.values()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values.map { "Prediction: ${it.label}" },
        )
        spinner.setSelection(values.indexOf(LatencyExperiments.current().predictionMode))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (values[pos] != LatencyExperiments.current().predictionMode) {
                    LatencyExperiments.update(this@DiagnosticsActivity) { it.copy(predictionMode = values[pos]) }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        return spinner
    }

    private fun predictionAmountSpinner(): View {
        val values = LatencyExperiments.PREDICTION_CHOICES_MS
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            values.map { "Prediction amount: $it ms" },
        )
        val current = LatencyExperiments.current().predictionMs
        spinner.setSelection(values.indexOf(current).let { if (it >= 0) it else values.indexOf(LatencyExperiments.DEFAULT_PREDICTION_MS) })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (values[pos] != LatencyExperiments.current().predictionMs) {
                    LatencyExperiments.update(this@DiagnosticsActivity) { it.copy(predictionMs = values[pos]) }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        return spinner
    }

    private fun label(text: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun check(text: String, initial: Boolean, onChanged: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        isChecked = initial
        setOnCheckedChangeListener { _, c -> onChanged(c) }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        gravity = Gravity.CENTER
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "Glashutte/Diag"
        const val REFRESH_MS = 1000L
        const val REQ_CAMERA = 71
    }
}
