package io.github.dmulder.glashutte

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.dmulder.glashutte.glasses.DrmProbe
import io.github.dmulder.glashutte.glasses.FlatAppStore
import io.github.dmulder.glashutte.glasses.FlatFit
import io.github.dmulder.glashutte.glasses.FlatMode
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import io.github.dmulder.glashutte.glasses.GlassesDisplay
import io.github.dmulder.glashutte.glasses.HandoffSettings
import io.github.dmulder.glashutte.handoff.FlatLog
import io.github.dmulder.glashutte.handoff.NativeHandoff
import io.github.dmulder.glashutte.handoff.SecureLayerTest
import io.github.dmulder.glashutte.privileged.PrivilegedService

/**
 * The "DRM / native handoff" block of the Diagnostics screen: the DRM probe, the secure-layer
 * and Widevine playback tests, the always-flat app list editor, and the handoff settings.
 * Everything it does is also logged under `GlashutteDiag` and included in Share log.
 */
class DrmSection(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var summary: TextView
    private lateinit var probeText: TextView
    private lateinit var testResult: TextView
    private lateinit var flatStatus: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var targetButton: Button
    private lateinit var densityLabel: TextView
    private lateinit var defaultModeButton: Button
    private val flatListener: () -> Unit = { handler.post { renderList(); renderSummary() } }
    private val handoffListener: () -> Unit = { renderSummary(); handler.post { flatStatus.text = flatStatusText() } }

    private fun flatStatusText(): String =
        "Flat state: ${NativeHandoff.phase.name.lowercase()}" +
            (NativeHandoff.session?.let { " (${it.label}, ${it.mode.wire}, display ${it.displayId})" } ?: "") +
            (if (NativeHandoff.notice.isNotEmpty()) "\n" + NativeHandoff.notice else "") +
            "\nlast run: " + NativeHandoff.lastReport.lineSequence().lastOrNull { it.startsWith("RESULT") }.orEmpty()

    fun addTo(root: LinearLayout) {
        FlatAppStore.init(activity.applicationContext)
        root.addView(label("DRM / NATIVE HANDOFF: why does a show play with no picture, and what can be done?", 14f, bold = true))
        root.addView(
            label(
                "Protected video (Netflix etc.) is blanked by Android on the virtual display the workspace uses. " +
                    "Apps on the always-flat list open on the glasses' own display instead (flat), at the panel's " +
                    "native 16:10, with the phone as the trackpad. This block probes what the hardware allows and " +
                    "edits that list. Run the probe with the glasses connected, then Share log.",
                11f,
            ),
        )
        summary = label("", 12f, bold = true)
        root.addView(summary)

        root.addView(row(button("Run DRM probe") { runProbe() }, button("Measure glasses fit") { measureFit() }))
        root.addView(row(button("Secure test: virtual display") { secureVirtual() }, button("Secure test: glasses") { secureGlasses() }))
        root.addView(row(button("Widevine test: phone") { widevine(onGlasses = false) }, button("Widevine test: glasses (flat)") { widevine(onGlasses = true) }))
        testResult = label("", 11f)
        root.addView(testResult)

        root.addView(label("FLAT BLANK TEST: does a normal (non-DRM) app show on the glasses? Run these in order, then Share log.", 12f, bold = true))
        root.addView(
            row(
                button("1. Flat test: Settings") { flatTest(skipDisplayChanges = false) },
                button("2. Same, no display changes") { flatTest(skipDisplayChanges = true) },
            ),
        )
        root.addView(button("Return to workspace now") { NativeHandoff.returnToWorkspace("Diagnostics button") })
        flatStatus = label("", 11f)
        root.addView(flatStatus)

        root.addView(label("Always-flat apps (they open flat on the glasses; tap a mode to switch flat+trackpad / phone mirror)", 12f, bold = true))
        root.addView(
            check("Route listed apps flat", FlatAppStore.enabled) { FlatAppStore.enabled = it },
        )
        defaultModeButton = button("") { FlatAppStore.defaultMode = other(FlatAppStore.defaultMode) }
        root.addView(defaultModeButton)
        listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        root.addView(addRow())
        root.addView(row(button("Pick an installed app") { pickInstalledApp() }, button("Reset to defaults") { FlatAppStore.resetToDefaults() }))

        root.addView(label("Flat mode settings", 12f, bold = true))
        targetButton = button("") {
            val values = HandoffSettings.Target.values()
            val next = values[(HandoffSettings.target(activity).ordinal + 1) % values.size]
            HandoffSettings.setTarget(activity, next)
            renderSettings()
        }
        root.addView(targetButton)
        densityLabel = label("", 12f)
        root.addView(
            row(
                button("Density -") { stepDensity(-20) },
                densityLabel,
                button("Density +") { stepDensity(20) },
            ),
        )
        root.addView(check("Set the glasses display to 1920x1200 (16:10) while a flat app runs", HandoffSettings.forcePanelSize(activity)) {
            HandoffSettings.setForcePanelSize(activity, it)
        })
        root.addView(check("Draw a cursor on the glasses while a flat app runs", HandoffSettings.cursorOverlay(activity)) {
            HandoffSettings.setCursorOverlay(activity, it)
        })
        root.addView(check("Stop the app when returning to the workspace", HandoffSettings.stopOnReturn(activity)) {
            HandoffSettings.setStopOnReturn(activity, it)
        })

        probeText = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.rgb(200, 220, 200))
            setTextIsSelectable(true)
        }
        root.addView(probeText)
        renderSettings()
        renderList()
        renderSummary()
        probeText.text = DrmProbe.lastReport
    }

    fun onResume() {
        FlatAppStore.addChangeListener(flatListener)
        NativeHandoff.addListener(handoffListener)
        renderSummary()
        flatStatus.text = flatStatusText()
    }

    fun onPause() {
        FlatAppStore.removeChangeListener(flatListener)
        NativeHandoff.removeListener(handoffListener)
        SecureLayerTest.stopOnGlasses()
    }

    /** Everything this block knows, for the exported log. */
    fun reportText(): String = buildString {
        append("\n=== DRM / native handoff ===\n")
        append(DrmProbe.lastReport).append('\n')
        append("\n-- native handoff --\n")
        append("phase=").append(NativeHandoff.phase)
        NativeHandoff.session?.let { append(" app=").append(it.packageName).append(" mode=").append(it.mode.wire).append(" display=").append(it.displayId) }
        append("\nsettings: target=").append(HandoffSettings.target(activity).name)
            .append(" density=").append(HandoffSettings.densityDpi(activity))
            .append(" forcePanelSize=").append(HandoffSettings.forcePanelSize(activity))
            .append(" cursorOverlay=").append(HandoffSettings.cursorOverlay(activity))
            .append(" stopOnReturn=").append(HandoffSettings.stopOnReturn(activity))
            .append(" pendingDisplayRestore=").append(HandoffSettings.displayChangePending(activity))
            .append('\n')
        append("last handoff:\n").append(NativeHandoff.lastReport.prependIndent("  ")).append('\n')
        append("\n-- flat-launch log (FLAT-ROUTE / DRM-PROBE / NATIVE-HANDOFF, also in GlashutteDiag) --\n")
        append(FlatLog.read()).append('\n')
        append("\n-- always-flat list (enabled=").append(FlatAppStore.enabled)
            .append(" defaultMode=").append(FlatAppStore.defaultMode.wire).append(") --\n")
        FlatAppStore.entries().forEach {
            append(it.packageName).append(" | ").append(it.label).append(" | ")
                .append(FlatAppStore.modeFor(it.packageName).wire)
                .append(if (FlatAppStore.isShippedDefault(it.packageName)) " | default" else " | user-added").append('\n')
        }
        append("\n-- test results --\n").append(testResult.text).append('\n')
    }

    private fun renderSummary() {
        handler.post {
            val s = NativeHandoff.session
            summary.text = "Probe: ${DrmProbe.lastSummary}\nFlat app: " +
                if (s == null) "none running (${NativeHandoff.phase.name.lowercase()})" else "${s.label} (${s.mode.wire}) ${NativeHandoff.phase.name.lowercase()}"
        }
    }

    private fun renderSettings() {
        targetButton.text = "Where flat apps go: " + HandoffSettings.target(activity).label
        densityLabel.text = "  ${HandoffSettings.densityDpi(activity)} dpi  "
        defaultModeButton.text = "Default mode for listed apps: " + modeText(FlatAppStore.defaultMode)
    }

    private fun stepDensity(delta: Int) {
        HandoffSettings.setDensityDpi(activity, HandoffSettings.densityDpi(activity) + delta)
        renderSettings()
    }

    private fun modeText(mode: FlatMode) = if (mode == FlatMode.MIRROR) "phone mirror" else "flat + trackpad"
    private fun other(mode: FlatMode) = if (mode == FlatMode.MIRROR) FlatMode.FLAT_TRACKPAD else FlatMode.MIRROR

    private fun renderList() {
        defaultModeButton.text = "Default mode for listed apps: " + modeText(FlatAppStore.defaultMode)
        listBox.removeAllViews()
        FlatAppStore.entries().forEach { entry ->
            val installed = runCatching { activity.packageManager.getPackageInfo(entry.packageName, 0) }.isSuccess
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val name = label(
                "${entry.label}\n${entry.packageName}  ${if (installed) "installed" else "not installed"}" +
                    if (FlatAppStore.isShippedDefault(entry.packageName)) "" else "  (added by you)",
                11f,
            )
            line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(button(if (FlatAppStore.modeFor(entry.packageName) == FlatMode.MIRROR) "mirror" else "flat") {
                FlatAppStore.setMode(entry.packageName, other(FlatAppStore.modeFor(entry.packageName)))
            })
            line.addView(button("Open") { openFlatNow(entry.packageName, entry.label) })
            line.addView(button("Remove") { FlatAppStore.remove(entry.packageName) })
            listBox.addView(line)
        }
    }

    private fun addRow(): LinearLayout {
        val input = EditText(activity).apply {
            hint = "package name, e.g. com.example.player"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 12f
        }
        return row(
            input,
            button("Add") {
                val pkg = input.text.toString().trim()
                if (!io.github.dmulder.glashutte.glasses.FlatAppsFormat.isPackageName(pkg)) {
                    toast("That is not a valid package name")
                } else {
                    val label = runCatching {
                        activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    FlatAppStore.add(pkg, label)
                    input.setText("")
                }
            },
            weights = floatArrayOf(1f, 0f),
        )
    }

    private fun pickInstalledApp() {
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != activity.packageName && !FlatAppStore.contains(it.first) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(activity)
            .setTitle("Always open flat")
            .setItems(apps.map { "${it.second}  (${it.first})" }.toTypedArray()) { _, i ->
                FlatAppStore.add(apps[i].first, apps[i].second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFlatNow(packageName: String, label: String) = startFlat(packageName, label, "Diagnostics: Open", skipDisplayChanges = false, allowMirrorFallback = true)

    /** Settings is a normal, non-protected app: if it is blank too, the problem is routing, not DRM. */
    private fun flatTest(skipDisplayChanges: Boolean) {
        val pkg = listOf("com.android.settings", "com.android.chrome")
            .firstOrNull { activity.packageManager.getLaunchIntentForPackage(it) != null }
        if (pkg == null) {
            toast("Neither Settings nor Chrome has a launcher entry")
            return
        }
        startFlat(
            pkg, if (pkg == "com.android.settings") "Settings" else "Chrome",
            "Diagnostics flat test (non-DRM)" + if (skipDisplayChanges) ", no display changes" else "",
            skipDisplayChanges = skipDisplayChanges, allowMirrorFallback = false,
        )
    }

    private fun startFlat(packageName: String, label: String, reason: String, skipDisplayChanges: Boolean, allowMirrorFallback: Boolean) {
        val component = activity.packageManager.getLaunchIntentForPackage(packageName)?.component
        if (component == null) {
            toast("$label is not installed")
            return
        }
        if (GlassesDisplay.find(activity) == null) {
            toast("Connect the glasses first")
            return
        }
        activity.startActivity(
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        var tries = 0
        val launcher = object : Runnable {
            override fun run() {
                if (NativeHandoff.hasHost || tries++ >= 10) {
                    NativeHandoff.launch(
                        packageName, component.className, label, reason,
                        requested = FlatMode.FLAT_TRACKPAD,
                        skipDisplayChanges = skipDisplayChanges,
                        allowMirrorFallback = allowMirrorFallback,
                    )
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.postDelayed(launcher, 900)
    }

    private fun runProbe() {
        toast("Probing...")
        Thread {
            val helper = if (PrivilegedService.isHelperReady) PrivilegedService.probeSecureDisplay() else null
            val result = DrmProbe.run(activity, helper)
            handler.post {
                probeText.text = result.text
                renderSummary()
                toast("Probe done: ${result.summary}")
            }
        }.start()
    }

    private fun measureFit() {
        Thread {
            val text = if (NativeHandoff.session != null) {
                NativeHandoff.measureNow()
            } else {
                val display = GlassesDisplay.find(activity)
                if (display == null) {
                    "no glasses display connected"
                } else {
                    val m = DisplayMetrics().also { display.getRealMetrics(it) }
                    val checks = FlatFit.analyze(m.widthPixels, m.heightPixels, m.densityDpi)
                    checks.forEach { GlassesDiagnostics.i(GlassesDiagnostics.TAG, "FLAT-FIT (idle display) ${it.line()}") }
                    "Idle glasses display, before any flat app (a flat launch sets 1920x1200 at ${HandoffSettings.densityDpi(activity)} dpi):\n" +
                        checks.joinToString("\n") { it.line() }
                }
            }
            handler.post { testResult.text = text }
        }.start()
    }

    private fun secureVirtual() {
        testResult.text = "Running the secure-layer test on a virtual display..."
        SecureLayerTest.runOnVirtualDisplay(activity) { testResult.text = "Virtual display: $it" }
    }

    private fun secureGlasses() {
        val display = GlassesDisplay.find(activity)
        if (display == null) {
            toast("Connect the glasses first")
            return
        }
        SecureLayerTest.showOnGlasses(activity, display) { testResult.text = "Glasses display: $it" }
    }

    private fun widevine(onGlasses: Boolean) {
        if (!onGlasses) {
            DrmTestActivity.start(activity, null)
            return
        }
        val display = GlassesDisplay.find(activity)
        if (display == null) {
            toast("Connect the glasses first")
            return
        }
        try {
            DrmTestActivity.start(activity, display.displayId)
            testResult.text = "Widevine test started on the glasses display (display ${display.displayId}). Report what you see: picture, or sound only."
        } catch (t: Throwable) {
            GlassesDiagnostics.i(GlassesDiagnostics.TAG, "DRM-PLAY: direct launch on display ${display.displayId} refused (${t.javaClass.simpleName}: ${t.message}); trying the helper")
            Thread {
                val outcome = PrivilegedService.handoffLaunch(
                    display.displayId, activity.packageName, DrmTestActivity::class.java.name,
                )
                handler.post { testResult.text = "Widevine test via the helper on display ${display.displayId}: $outcome" }
            }.start()
        }
    }

    private fun row(vararg views: android.view.View, weights: FloatArray? = null) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            val w = weights?.getOrNull(i) ?: 1f
            addView(v, LinearLayout.LayoutParams(if (w > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, w))
        }
    }

    private fun label(text: String, sp: Float, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.rgb(230, 230, 230))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setOnClickListener { onClick() }
    }

    private fun check(text: String, initial: Boolean, onChanged: (Boolean) -> Unit) = CheckBox(activity).apply {
        this.text = text
        isChecked = initial
        setTextColor(Color.rgb(230, 230, 230))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
}
