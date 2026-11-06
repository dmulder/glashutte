package io.github.dmulder.glashutte.spatial

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import io.github.dmulder.glashutte.glasses.GlassesUsb
import io.github.dmulder.glashutte.glasses.HeadTracking

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
        headTracking = HeadTracking(
            context,
            onPose = { w, x, y, z -> renderer.setHeadPose(w, x, y, z) },
            onPosition = { x, y, z -> renderer.setHeadPosition(x, y, z) },
            use6Dof = { WorkspaceController.carina6Dof },
        ).also { tracking ->
            tracking.onStreamingChanged = { streaming ->
                WorkspaceController.headTrackingActive = streaming
            }
            tracking.start()
        }
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

    override fun onStop() {
        surfaceView?.onPause()
        super.onStop()
    }

    override fun dismiss() {
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
}
