package io.github.dmulder.glashutte.handoff

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.widget.Toast
import io.github.dmulder.glashutte.glasses.FlatAppStore
import io.github.dmulder.glashutte.glasses.FlatFit
import io.github.dmulder.glashutte.glasses.FlatMode
import io.github.dmulder.glashutte.glasses.FlatSnapshot
import io.github.dmulder.glashutte.glasses.DrmProbe
import io.github.dmulder.glashutte.glasses.FlatPointer
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import io.github.dmulder.glashutte.glasses.HandoffSettings
import io.github.dmulder.glashutte.privileged.PrivilegedService
import io.github.dmulder.glashutte.spatial.WorkspaceController
import java.util.concurrent.Executors

/**
 * Native handoff: run one app flat on the glasses' *real* display instead of inside the
 * head-tracked workspace, so the platform's own display path (HDCP, secure layers) applies.
 *
 * Why this exists: the workspace re-renders apps from a virtual display into a GL scene, and
 * SurfaceFlinger blanks FLAG_SECURE / Widevine layers on a non-secure virtual display. A real
 * external display is composed by SurfaceFlinger itself, with no copy.
 *
 * Two ways to run an app flat:
 *  - [FlatMode.FLAT_TRACKPAD] (preferred): `am start --display <glasses>` at the panel's native
 *    16:10 and a readable density; the workspace window is made transparent underneath; the
 *    phone becomes a trackpad whose events are injected into that display by the shell helper,
 *    with a cursor on a separate non-secure overlay.
 *  - [FlatMode.MIRROR] (fallback): the workspace is taken down and the app runs on the phone's
 *    own display, which the glasses mirror. Touch is native; the phone screen is busy; the
 *    picture is the phone's portrait or letterboxed shape.
 *
 * It never captures or copies protected pixels; it only chooses which display the app runs on.
 */
object NativeHandoff {

    private const val TAG = GlassesDiagnostics.TAG
    private const val PREPARE_SETTLE_MS = 1200L

    /** Waits before each check that the app is on the glasses display (about 7 s in all). */
    private val VERIFY_WAITS_MS = longArrayOf(1200, 1500, 1800, 2200)
    private val LATE_SNAPSHOTS_MS = longArrayOf(4000, 12000)
    private const val POLL_MS = 3000L
    private const val TAP_GAP_MS = 40L
    private const val LONG_PRESS_MS = 650L
    private const val TICKS_PER_WIDTH = 800f

    enum class Phase { IDLE, STARTING, ACTIVE, RETURNING }

    class Session(
        val packageName: String,
        val activityName: String,
        val label: String,
        val mode: FlatMode,
        val displayId: Int,
        val startedAtMs: Long,
    )

    /** What the host activity must provide; implemented by `MainActivity`. */
    interface Host {
        val context: Context
        fun glassesDisplay(): Display?
        fun setWorkspacePassive(passive: Boolean)
        fun suspendWorkspace()
        fun resumeWorkspace()
        fun bringHostToFront()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "glashutte-handoff") }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var host: Host? = null
    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile var session: Session? = null
        private set
    @Volatile var lastReport: String = "(no flat app has been started yet)"
        private set

    @Volatile private var flatStarting = false
    private var pointer: FlatPointer? = null
    private var cursor: FlatCursorPresentation? = null
    private var dragging = false
    private var emptyPolls = 0
    private val pollRunnable = Runnable { poll() }

    /** True while the phone's trackpad / mouse / keyboard drive the flat app. */
    val capturesInput: Boolean
        get() = phase == Phase.ACTIVE && session?.mode == FlatMode.FLAT_TRACKPAD

    /** The display id that physical-keyboard and phone-IME input must target, or -1. */
    val inputDisplayId: Int
        get() = if (capturesInput) session?.displayId ?: -1 else -1

    /** True while the workspace window is deliberately gone (phone-mirror mode). */
    val workspaceSuspended: Boolean
        get() = phase != Phase.IDLE && session?.mode == FlatMode.MIRROR

    fun attachHost(h: Host) { host = h }
    fun detachHost(h: Host) { if (host === h) host = null }
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() = main.post { listeners.forEach { runCatching { it() } } }

    private fun note(message: String) = FlatLog.i("NATIVE-HANDOFF", message)
    private fun route(message: String) = FlatLog.i("FLAT-ROUTE", message)

    /** Shown on the phone after a flat launch was abandoned; cleared by the next launch or by tapping it. */
    @Volatile var notice: String = ""
        private set

    fun clearNotice() {
        notice = ""
        changed()
    }

    val hasHost: Boolean get() = host != null

    /** True while the workspace window must stay see-through so it cannot cover a flat app. */
    val wantsWorkspacePassive: Boolean
        get() = flatStarting || (session?.mode == FlatMode.FLAT_TRACKPAD && phase == Phase.ACTIVE)

    /**
     * Entry point from the launcher: route [packageName] flat when the always-flat list says so.
     * Returns `true` when handled (the caller must not also open a workspace window).
     */
    fun routeIfListed(packageName: String, activityName: String, label: String): Boolean {
        val h = host ?: return false
        if (!FlatAppStore.shouldRouteFlat(packageName)) return false
        if (h.glassesDisplay() == null) return false
        val mode = FlatAppStore.modeFor(packageName)
        FlatAppStore.logRouted(packageName, mode, "native handoff")
        launch(packageName, activityName, label, "always-flat list", mode)
        return true
    }

    /**
     * Launch [packageName] flat now (list, "Open flat" or a Diagnostics test). Safe from any thread.
     * [skipDisplayChanges] leaves the glasses display's size and density alone (a test that tells
     * apart "the display change blanked it" from other causes). [allowMirrorFallback] false makes
     * a failed flat launch restore the workspace instead of trying the phone mirror.
     */
    fun launch(
        packageName: String,
        activityName: String,
        label: String,
        reason: String,
        requested: FlatMode? = null,
        skipDisplayChanges: Boolean = false,
        allowMirrorFallback: Boolean = true,
    ) {
        main.post { startOnMain(packageName, activityName, label, reason, requested, skipDisplayChanges, allowMirrorFallback) }
    }

    private fun startOnMain(
        packageName: String,
        activityName: String,
        label: String,
        reason: String,
        requested: FlatMode?,
        skipDisplayChanges: Boolean,
        allowMirrorFallback: Boolean,
    ) {
        val h = host
        if (h == null) {
            notice = "Open Glashutte (with the glasses connected) first, then try again."
            note("launch of $packageName ignored: the workspace host is not running")
            changed()
            return
        }
        if (phase != Phase.IDLE) {
            Toast.makeText(h.context, "Return to the workspace before opening another flat app", Toast.LENGTH_SHORT).show()
            note("ignored launch of $packageName: phase=$phase")
            return
        }
        notice = ""
        val target = HandoffSettings.target(h.context)
        val wanted = requested ?: FlatAppStore.modeFor(packageName)
        val plan = when (target) {
            HandoffSettings.Target.MIRROR_ONLY -> FlatMode.MIRROR
            HandoffSettings.Target.EXTERNAL_ONLY -> FlatMode.FLAT_TRACKPAD
            HandoffSettings.Target.AUTO -> wanted
        }
        route(
            "==== launch $packageName ($reason): app wants ${wanted.wire}, target setting ${target.name} -> plan ${plan.wire}" +
                (if (skipDisplayChanges) ", NO display size/density changes (test)" else "") +
                (if (!allowMirrorFallback) ", no mirror fallback (test)" else ""),
        )
        phase = Phase.STARTING
        changed()
        WorkspaceController.closeAppByPackage(packageName)
        val display = h.glassesDisplay()
        if (display == null) {
            fail(h, packageName, "no glasses display is connected")
            return
        }
        when (plan) {
            FlatMode.FLAT_TRACKPAD -> startFlat(
                h, display, packageName, activityName, label,
                mayFallBackToMirror = allowMirrorFallback && target == HandoffSettings.Target.AUTO,
                skipDisplayChanges = skipDisplayChanges,
            )
            FlatMode.MIRROR -> startMirror(h, display, packageName, activityName, label)
        }
    }

    private fun startFlat(
        h: Host,
        display: Display,
        pkg: String,
        activity: String,
        label: String,
        mayFallBackToMirror: Boolean,
        skipDisplayChanges: Boolean,
    ) {
        if (!PrivilegedService.isHelperReady) {
            note("flat needs the shell helper and it is not connected")
            if (mayFallBackToMirror) startMirror(h, display, pkg, activity, label) else fail(h, pkg, "the shell helper is not connected")
            return
        }
        val ctx = h.context
        val density = HandoffSettings.densityDpi(ctx)
        val forceSize = HandoffSettings.forcePanelSize(ctx)
        flatStarting = true
        h.setWorkspacePassive(true)
        worker.execute {
            val report = StringBuilder()
            val m0 = DisplayMetrics().also { display.getRealMetrics(it) }
            FlatLog.i(
                "DRM-PROBE",
                "flat-state BEFORE launch: display ${display.displayId} '${display.name}' ${m0.widthPixels}x${m0.heightPixels}@${m0.densityDpi}dpi " +
                    "${"%.0f".format(display.refreshRate)}Hz; " + DrmProbe.quickState(ctx, display),
            )
            var displayChanged = false
            if (skipDisplayChanges) {
                note("display size/density left exactly as it is (test option)")
                report.append("display changes: skipped (test)\n")
            } else {
                HandoffSettings.setDisplayChangePending(ctx, display.displayId)
                val prepared = PrivilegedService.handoffPrepare(display.displayId, density, forceSize)
                displayChanged = true
                report.append("prepare display ${display.displayId}:\n").append(prepared.prependIndent("  ")).append('\n')
                FlatLog.block("NATIVE-HANDOFF", "display prepare result", prepared)
                SystemClock.sleep(PREPARE_SETTLE_MS)
                val m1 = DisplayMetrics().also { display.getRealMetrics(it) }
                note("display after prepare and ${PREPARE_SETTLE_MS}ms settle: ${m1.widthPixels}x${m1.heightPixels}@${m1.densityDpi}dpi state=${display.state}")
            }
            val outcome = PrivilegedService.handoffLaunch(display.displayId, pkg, activity)
            report.append("launch: ").append(outcome).append('\n')
            route("am start --display ${display.displayId} $pkg/$activity -> $outcome")

            var snapshot = ""
            var verdict: FlatSnapshot.Verdict? = null
            if (outcome.ok) {
                var waited = 0L
                for ((i, wait) in VERIFY_WAITS_MS.withIndex()) {
                    SystemClock.sleep(wait)
                    waited += wait
                    snapshot = PrivilegedService.flatSnapshot(display.displayId, pkg)
                    verdict = FlatSnapshot.decodeVerdict(snapshot)
                    route("verify #${i + 1} after ${waited}ms: ${snapshot.lineSequence().firstOrNull().orEmpty()}")
                    if (FlatSnapshot.passes(verdict)) break
                }
                FlatLog.block("FLAT-ROUTE", "final snapshot for $pkg on display ${display.displayId}", snapshot, 120)
            }
            val landed = outcome.ok && FlatSnapshot.passes(verdict)
            report.append("verified on display ${display.displayId}: $landed (${verdict ?: "no snapshot"})\n")
            if (!landed) {
                val why = when {
                    !outcome.ok -> "am start was refused: $outcome"
                    verdict == FlatSnapshot.Verdict.ELSEWHERE -> "the app started but not on the glasses display (the task bounced to another display)"
                    verdict == FlatSnapshot.Verdict.NOT_RUNNING -> "the app is not running (it crashed, finished or never started)"
                    verdict == FlatSnapshot.Verdict.ON_DISPLAY_NOT_VISIBLE -> "the app is on the glasses display but never showed a visible window"
                    else -> "the app could not be verified on the glasses display ($verdict)"
                }
                route("FAILED: $why")
                FlatLog.i("DRM-PROBE", "flat-state AFTER failed launch: " + DrmProbe.quickState(ctx, display))
                PrivilegedService.forceStop(pkg)
                if (displayChanged) {
                    val restored = PrivilegedService.handoffRestore(display.displayId)
                    HandoffSettings.setDisplayChangePending(ctx, -1)
                    note("display restored: ${restored.replace('\n', ' ')}")
                }
                SystemClock.sleep(400)
                main.post {
                    flatStarting = false
                    h.setWorkspacePassive(false)
                    lastReport = report.append("RESULT: flat launch failed: $why\n").toString()
                    if (mayFallBackToMirror) {
                        notice = "$label did not open on the glasses ($why). Trying the phone mirror instead."
                        note("falling back to the phone mirror")
                        startMirror(h, display, pkg, activity, label)
                    } else {
                        notice = "$label did not open on the glasses: $why. The workspace is back."
                        fail(h, pkg, why)
                    }
                }
                return@execute
            }
            FlatLog.i("DRM-PROBE", "flat-state AFTER launch (app verified on display): " + DrmProbe.quickState(ctx, display))
            val facts = PrivilegedService.displayFacts(display.displayId)
            val fit = fitReport(display, facts)
            report.append("fit:\n").append(fit.prependIndent("  ")).append('\n')
            main.post {
                flatStarting = false
                val s = Session(pkg, activity, label, FlatMode.FLAT_TRACKPAD, display.displayId, SystemClock.elapsedRealtime())
                session = s
                val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
                pointer = FlatPointer(metrics.widthPixels, metrics.heightPixels)
                dragging = false
                showCursor(h, display)
                HandoffNotifier.show(ctx, label, "Flat on the glasses; phone is the trackpad")
                phase = Phase.ACTIVE
                emptyPolls = 0
                main.postDelayed(pollRunnable, POLL_MS)
                lastReport = report.append("RESULT: flat + trackpad active\n").toString()
                route("ACTIVE flat+trackpad pkg=$pkg display=${display.displayId} ${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}dpi (window visible: if the glasses are still blank, the platform is hiding it: see SECURE in the snapshot)")
                for (delay in LATE_SNAPSHOTS_MS) main.postDelayed({ logLateSnapshot(s, display) }, delay)
                changed()
            }
        }
    }

    private fun logLateSnapshot(s: Session, display: Display) {
        if (session !== s || phase != Phase.ACTIVE) return
        val ctx = host?.context ?: return
        worker.execute {
            val snap = PrivilegedService.flatSnapshot(display.displayId, s.packageName)
            route("still-there check: ${snap.lineSequence().firstOrNull().orEmpty()}")
            FlatLog.i("DRM-PROBE", "flat-state while running: " + DrmProbe.quickState(ctx, display))
        }
    }

    private fun startMirror(h: Host, display: Display, pkg: String, activity: String, label: String) {
        val ctx = h.context
        h.suspendWorkspace()
        val report = StringBuilder("mirror: workspace taken down, app started on the phone's own display\n")
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(pkg, activity)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val started = runCatching { ctx.startActivity(intent) }
        if (started.isFailure) {
            h.resumeWorkspace()
            lastReport = report.append("RESULT: could not start $pkg: ${started.exceptionOrNull()}\n").toString()
            fail(h, pkg, "could not start the app on the phone: ${started.exceptionOrNull()?.message}")
            return
        }
        session = Session(pkg, activity, label, FlatMode.MIRROR, Display.DEFAULT_DISPLAY, SystemClock.elapsedRealtime())
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        report.append("glasses display id=${display.displayId} ${metrics.widthPixels}x${metrics.heightPixels} flags=0x${Integer.toHexString(display.flags)}\n")
        report.append("NOTE: in mirror mode the picture has the phone's shape, not the panel's 16:10.\n")
        lastReport = report.append("RESULT: mirror active\n").toString()
        HandoffNotifier.show(ctx, label, "Mirroring the phone screen; tap to return to the workspace")
        phase = Phase.ACTIVE
        note("ACTIVE mirror pkg=$pkg (app on the phone display, glasses mirror it)")
        FlatLog.i("DRM-PROBE", "mirror-state: glasses display ${display.displayId} " + DrmProbe.quickState(ctx, display))
        main.postDelayed({
            worker.execute {
                val snap = PrivilegedService.flatSnapshot(Display.DEFAULT_DISPLAY, pkg)
                FlatLog.block("FLAT-ROUTE", "mirror: where $pkg is (display 0 = phone)", snap, 60)
            }
        }, 2500)
        changed()
    }

    private fun fail(h: Host, pkg: String, why: String) {
        note("FAILED $pkg: $why")
        if (notice.isEmpty()) notice = "Could not open $pkg flat: $why"
        Toast.makeText(h.context, "Could not open flat: $why", Toast.LENGTH_LONG).show()
        flatStarting = false
        phase = Phase.IDLE
        session = null
        changed()
    }

    private fun showCursor(h: Host, display: Display) {
        val p = pointer ?: return
        if (!HandoffSettings.cursorOverlay(h.context)) return
        cursor?.let { runCatching { it.dismiss() } }
        cursor = try {
            FlatCursorPresentation(h.context, display, p).also { it.show() }
        } catch (t: Throwable) {
            note("cursor overlay refused on display ${display.displayId}: ${t.javaClass.simpleName}: ${t.message}; input works without a drawn cursor")
            null
        }
    }

    /** Leave the flat app and put the workspace back. */
    fun returnToWorkspace(reason: String) {
        main.post { returnOnMain(reason) }
    }

    private fun returnOnMain(reason: String) {
        val h = host
        val s = session
        if (s == null || phase == Phase.IDLE || phase == Phase.RETURNING) return
        phase = Phase.RETURNING
        main.removeCallbacks(pollRunnable)
        note("returning to the workspace ($reason); was ${s.mode.wire} ${s.packageName}")
        changed()
        cursor?.let { runCatching { it.dismiss() } }
        cursor = null
        pointer = null
        dragging = false
        val ctx = h?.context
        worker.execute {
            if (ctx != null && HandoffSettings.stopOnReturn(ctx)) PrivilegedService.forceStop(s.packageName)
            if (s.mode == FlatMode.FLAT_TRACKPAD) {
                val restored = PrivilegedService.handoffRestore(s.displayId)
                if (ctx != null) HandoffSettings.setDisplayChangePending(ctx, -1)
                note("display restored: ${restored.replace('\n', ' ')}")
            }
            main.post {
                session = null
                phase = Phase.IDLE
                if (h != null) {
                    if (s.mode == FlatMode.MIRROR) h.resumeWorkspace() else h.setWorkspacePassive(false)
                    h.bringHostToFront()
                    HandoffNotifier.cancel(h.context)
                }
                changed()
            }
        }
    }

    /** Called when the host activity comes back to the front. */
    fun onHostResumed() {
        val s = session ?: return
        if (phase == Phase.ACTIVE && s.mode == FlatMode.MIRROR &&
            SystemClock.elapsedRealtime() - s.startedAtMs > 3000
        ) {
            returnToWorkspace("phone returned to Glashutte")
        }
    }

    /** Undo a `wm size/density` change left behind if the app died while an app ran flat. */
    fun recoverStaleDisplay(context: Context) {
        val pending = HandoffSettings.displayChangePending(context)
        if (pending < 0 || phase != Phase.IDLE || !PrivilegedService.isHelperReady) return
        worker.execute {
            val restored = PrivilegedService.handoffRestore(pending)
            note("recovered a display left modified by a previous session (display $pending): ${restored.replace('\n', ' ')}")
            HandoffSettings.setDisplayChangePending(context, -1)
        }
    }

    private fun poll() {
        val s = session
        if (phase != Phase.ACTIVE || s == null || s.mode != FlatMode.FLAT_TRACKPAD) return
        worker.execute {
            val has = PrivilegedService.displayHasActivity(s.displayId)
            main.post {
                if (phase != Phase.ACTIVE || session !== s) return@post
                emptyPolls = if (has) 0 else emptyPolls + 1
                if (emptyPolls >= 2) {
                    returnToWorkspace("the app closed on the glasses")
                } else {
                    main.postDelayed(pollRunnable, POLL_MS)
                }
            }
        }
    }

    // region Pointer input (phone trackpad / mouse -> injected touch on the glasses' display)

    private fun target(): Pair<FlatPointer, Int>? {
        if (!capturesInput) return null
        val p = pointer ?: return null
        val id = session?.displayId ?: return null
        return p to id
    }

    fun moveFraction(dx: Float, dy: Float) {
        val (p, id) = target() ?: return
        p.moveByFraction(dx, dy, WorkspaceController.cursorSensitivity)
        if (dragging) PrivilegedService.injectTouch(id, p.ix, p.iy, 1)
        cursor?.refresh()
    }

    fun moveTicks(dx: Int, dy: Int) {
        val (p, id) = target() ?: return
        p.moveByTicks(dx, dy, TICKS_PER_WIDTH, WorkspaceController.cursorSensitivity)
        if (dragging) PrivilegedService.injectTouch(id, p.ix, p.iy, 1)
        cursor?.refresh()
    }

    fun tap() {
        val (p, id) = target() ?: return
        val x = p.ix
        val y = p.iy
        PrivilegedService.injectTouch(id, x, y, 0)
        main.postDelayed({ PrivilegedService.injectTouch(id, x, y, 2) }, TAP_GAP_MS)
    }

    fun longPress() {
        val (p, id) = target() ?: return
        val x = p.ix
        val y = p.iy
        PrivilegedService.injectTouch(id, x, y, 0)
        main.postDelayed({ PrivilegedService.injectTouch(id, x, y, 2) }, LONG_PRESS_MS)
    }

    fun beginDrag() {
        val (p, id) = target() ?: return
        dragging = true
        PrivilegedService.injectTouch(id, p.ix, p.iy, 0)
    }

    fun endDrag() {
        val (p, id) = target() ?: return
        if (!dragging) return
        dragging = false
        PrivilegedService.injectTouch(id, p.ix, p.iy, 2)
    }

    fun cancelDrag() {
        val (p, id) = target() ?: return
        if (!dragging) return
        dragging = false
        PrivilegedService.injectTouch(id, p.ix, p.iy, 3)
    }

    /** Scroll by a trackpad/wheel fraction, scaled the same way the workspace scales it. */
    fun scrollFraction(dyFraction: Float) = scroll(dyFraction * WorkspaceController.scrollSensitivity)

    /** Wheel-style scroll at the cursor ([vScroll] has the mouse-wheel sign convention). */
    fun scroll(vScroll: Float) {
        val (p, id) = target() ?: return
        PrivilegedService.scrollOnDisplay(id, p.ix, p.iy, vScroll)
    }

    // endregion

    // region Quick controls

    fun back() = key(KeyEvent.KEYCODE_BACK)
    fun home() = key(KeyEvent.KEYCODE_HOME)

    private fun key(code: Int) {
        val id = session?.displayId ?: return
        if (session?.mode != FlatMode.FLAT_TRACKPAD) return
        PrivilegedService.key(id, code)
    }

    /** Media play/pause goes to the active media session (any display), the way a headset button does. */
    fun playPause() {
        val ctx = host?.context ?: return
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    fun volume(raise: Boolean) {
        val ctx = host?.context ?: return
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    // endregion

    /**
     * Aspect-ratio and UI-size verdicts for the display the app is on, from the display's real
     * metrics and the activity bounds in the helper's dump. Logged under `GlashutteDiag`.
     */
    private fun fitReport(display: Display, facts: String): String {
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val bounds = Regex("""bounds=Rect\((\d+), (\d+) - (\d+), (\d+)\)""").findAll(facts)
            .map { m -> m.groupValues.drop(1).map { it.toInt() } }
            .map { (l, t, r, b) -> (r - l) to (b - t) }
            .firstOrNull { it.first > 0 && it.second > 0 }
        val checks = FlatFit.analyze(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, bounds?.first ?: 0, bounds?.second ?: 0)
        checks.forEach { note("FLAT-FIT ${it.line()}") }
        return buildString {
            checks.forEach { append(it.line()).append('\n') }
            append("--- helper display facts ---\n").append(facts)
        }
    }

    /** Fresh measurement of the running flat app's display, for the Diagnostics screen. */
    fun measureNow(): String {
        val h = host ?: return "(workspace host not running)"
        val display = h.glassesDisplay() ?: return "(no glasses display)"
        val facts = PrivilegedService.displayFacts(display.displayId)
        return fitReport(display, facts)
    }
}
