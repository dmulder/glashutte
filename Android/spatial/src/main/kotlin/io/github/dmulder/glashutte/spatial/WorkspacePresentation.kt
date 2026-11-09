package io.github.dmulder.glashutte.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.github.dmulder.glashutte.glasses.DisplayRefresh
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import io.github.dmulder.glashutte.glasses.GlassesUsb
import io.github.dmulder.glashutte.glasses.HeadPoseSource
import io.github.dmulder.glashutte.glasses.HeadTracking
import io.github.dmulder.glashutte.glasses.LatencyExperiments
import io.github.dmulder.glashutte.glasses.LatencyTelemetry
import io.github.dmulder.glashutte.glasses.TrackingExperiments

/**
 * Hosts the workspace on the VITURE glasses.
 *
 * A [Presentation] is a window bound to a specific [Display]; showing it on the glasses'
 * display takes that display over from mirroring, so the glasses show the 3D workspace while
 * the phone keeps showing the control panel — two independent screens, no DeX required.
 */
class WorkspacePresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {

    private var surfaceView: WorkspaceSurfaceView? = null
    private var headTracking: HeadTracking? = null

    private val diagHandler = Handler(Looper.getMainLooper())
    private val diagTick = object : Runnable {
        override fun run() {
            val renderer = surfaceView?.workspaceRenderer
            if (renderer != null) {
                GlassesDiagnostics.setRenderer(
                    renderer.currentViewModeForDiagnostics.name,
                    WorkspaceController.headTrackingActive,
                    renderer.headPoseUpdates,
                )
            }
            WorkspaceController.statusBanner =
                if (TrackingExperiments.glassesOverlay(context)) {
                    GlassesDiagnostics.overlayText()
                } else {
                    GlassesDiagnostics.warningBanner()
                }
            applyLatencyDisplaySettings()
            diagHandler.postDelayed(this, DIAG_INTERVAL_MS)
        }
    }

    private var appliedHighestRefresh: Boolean? = null
    private var refreshRequest = "not requested"

    /**
     * Keep the window's preferred display mode in line with the "highest refresh rate"
     * experiment, publish the display's live mode to the latency log, and emit its heartbeat.
     */
    private fun applyLatencyDisplaySettings() {
        val settings = LatencyExperiments.current()
        if (settings.highestRefresh != appliedHighestRefresh) {
            appliedHighestRefresh = settings.highestRefresh
            val result = DisplayRefresh.apply(window, display, settings.highestRefresh)
            refreshRequest = result.description
            GlassesDiagnostics.i(
                GlassesDiagnostics.TAG,
                "refresh rate: highestRefresh=${settings.highestRefresh} ${result.description}",
            )
        }
        LatencyTelemetry.displayInfo = DisplayRefresh.describeActive(display) +
            " [highestRefresh ${refreshRequest.substringBefore(" supported=")}]"
        LatencyTelemetry.heartbeat(System.currentTimeMillis(), System.nanoTime(), settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = WorkspaceSurfaceView(context)
        surfaceView = view
        setContentView(view)
        WorkspaceController.register(view.workspaceRenderer)
        // On the VITURE glasses the field-of-view edges are hard to see, so render into a
        // centred band; a plain external display can use its whole height.
        WorkspaceController.setScreenBand(if (GlassesUsb.isConnected(context)) 0.83f else 1.0f)
        // Drive the camera from the glasses' head pose, so the screens stay world-fixed.
        // The pose-stream watchdog inside HeadTracking flips the controller's
        // headTrackingActive flag on start/stop transitions, which (a) ungrays the FREE
        // toggle in the toolbars and (b) auto-reverts to PINNED when DOF dies.
        val renderer = view.workspaceRenderer
        GlassesDiagnostics.init(context)
        diagHandler.post(diagTick)
        headTracking = HeadTracking(
            context,
            onPose = { w, x, y, z -> renderer.setHeadPose(w, x, y, z) },
            onPosition = { x, y, z -> renderer.setHeadPosition(x, y, z) },
            use6Dof = { WorkspaceController.carina6Dof },
        ).also { tracking ->
            tracking.onStreamingChanged = { streaming ->
                WorkspaceController.headTrackingActive = streaming
            }
            tracking.onCameraPermissionNeeded = { WorkspaceController.requestCameraPermission?.invoke() }
            tracking.start()
        }
        renderer.headPoseSource = HeadPoseSource { now, out -> headTracking?.pullPose(now, out) ?: false }
        // Ctrl+Alt+R: ask the glasses/SDK to re-anchor its frame (no-op on backends that can't).
        WorkspaceController.sdkRecenter = { headTracking?.recenterDevice() }
        // The DOF-retry button's hook (WorkspaceController.retryHeadTracking) is owned by
        // MainActivity, which routes it through restartHeadTracking() and reports the
        // success / failure outcome — so we don't wire it here.
    }

    override fun onStart() {
        super.onStart()
        surfaceView?.onResume()
    }

    /**
     * Re-attempt head-tracking startup. No-op if it's already running. Used to recover
     * from the startup race where the glasses' DisplayPort side enumerates before the USB
     * IMU endpoint — without this hook, the first `HeadTracking.start()` returns with no
     * device found and DOF stays dead for the session. Wired by [io.github.dmulder.glashutte.MainActivity]
     * from its `USB_DEVICE_ATTACHED` handler.
     */
    fun retryHeadTracking() {
        headTracking?.start()
    }

    /**
     * Force-restart head tracking with a full SDK teardown first. Used after a USB
     * rescan (from the DOF-stall watchdog): the prior SDK handle is bound to a USB
     * device file the kernel just removed, so we have to release it cleanly before
     * the post-bind attach intent triggers a fresh `start()`.
     */
    fun restartHeadTracking() {
        headTracking?.stop()
        headTracking?.start()
    }

    /**
     * Make the workspace window fully transparent and untouchable (or restore it). Used while an
     * app runs flat on the glasses' display: the workspace stays alive underneath (so returning
     * to it is instant and nothing is torn down), but it no longer covers the app and, with a
     * zero-alpha layer, SurfaceFlinger composites nothing for it.
     */
    fun setPassive(passive: Boolean) {
        val w = window ?: return
        val attrs = w.attributes
        if (passive) {
            attrs.alpha = 0f
            attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            attrs.alpha = 1f
            attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        w.attributes = attrs
        GlassesDiagnostics.i(GlassesDiagnostics.TAG, "workspace window passive=$passive (alpha=${attrs.alpha})")
    }

    override fun onStop() {
        surfaceView?.onPause()
        super.onStop()
    }

    override fun dismiss() {
        diagHandler.removeCallbacks(diagTick)
        surfaceView?.workspaceRenderer?.headPoseSource = null
        WorkspaceController.statusBanner = null
        headTracking?.stop()
        headTracking = null
        WorkspaceController.sdkRecenter = null
        WorkspaceController.headTrackingActive = false
        surfaceView?.let { view ->
            WorkspaceController.unregister(view.workspaceRenderer)
            view.queueEvent {
                // Force-stop the launched apps before releasing their displays, so they close
                // rather than being relocated onto the phone's screen.
                view.workspaceRenderer.closeAllWindows()
                view.workspaceRenderer.releaseAll()
            }
            view.onPause()
        }
        surfaceView = null
        super.dismiss()
    }

    private companion object {
        const val DIAG_INTERVAL_MS = 500L
    }
}
