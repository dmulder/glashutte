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
import io.github.dmulder.glashutte.glasses.ProjectionExperiments
import io.github.dmulder.glashutte.glasses.TrackingExperiments
import io.github.dmulder.glashutte.spatial.WorkspaceController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Separate launcher entry ("Glashutte Diagnostics") showing which stage of the head-tracking
 * pipeline fails, with a one-tap log export. Everything it shows lives in
 * [GlassesDiagnostics], so it is the same data the in-glasses overlay and `GlashutteDiag`
 * logcat lines carry.
 */
class DiagnosticsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reportView: TextView
    private val refresh = object : Runnable {
        override fun run() {
            reportView.text = GlassesDiagnostics.report(includeEvents = true, includeSdkLog = false)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlassesDiagnostics.init(applicationContext)
        GlassesUsb.inventory(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 16, 20))
            setPadding(dp(12), dp(32), dp(12), dp(8))
        }

        root.addView(label("Glashutte head-tracking diagnostics", 18f, bold = true))
        root.addView(label("Open this while the glasses are connected and the workspace is up, wait ~15 s, then Share log.", 12f))

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

        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.rgb(220, 220, 220))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(reportView) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun fullLog(): String {
        val sb = StringBuilder(GlassesDiagnostics.report(includeEvents = true, includeSdkLog = true))
        sb.append("\n\n-- logcat (this app, tracking-related tags) --\n")
        sb.append(readLogcat())
        return sb.toString()
    }

    private fun readLogcat(): String = try {
        val proc = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-t", "4000",
            "GlashutteDiag:V", "Glashutte/Native:V", "Glashutte/SdkLog:V", "Glashutte/Tracking:V",
            "Glashutte/VitureSdk:V", "Glashutte/Usb:V", "Glashutte/Main:V", "Glashutte/Display:V",
            "libglasses:V", "*:S",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
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
