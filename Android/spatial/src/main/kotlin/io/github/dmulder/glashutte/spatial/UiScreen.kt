package io.github.dmulder.glashutte.spatial

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.Matrix
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface

/**
 * A workspace screen whose content is an Android UI — Glashutte's own [Presentation] — rather
 * than a launched third-party app.
 *
 * A [VirtualDisplay] renders into a
 * [SurfaceTexture] that [WorkspaceRenderer] samples onto a quad. What differs is the
 * content (our own view hierarchy) and that the cursor's clicks are dispatched straight
 * into that view tree — no Shizuku needed, since it is our own window.
 *
 * Threading: the constructor and [updateTexture]/[release] run on the GL thread; [start]
 * and [dispatchTap] run on the main thread.
 */
class UiScreen(
    /** GL external-OES texture name the UI's frames are decoded into. */
    val textureId: Int,
    /** Surface pixel size — exposed so callers can map cursor hits to its pixels. */
    val width: Int,
    val height: Int,
    private val mainHandler: Handler,
    /** Virtual-display name — kept distinct per screen (desktop, each window's chrome). */
    private val displayName: String,
) {
    val surfaceTexture: SurfaceTexture =
        SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }

    private val surface = Surface(surfaceTexture)

    /** SurfaceTexture → texture-coordinate transform, refreshed every GL frame. */
    val textureMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var virtualDisplay: VirtualDisplay? = null

    /**
     * The ID of this UI screen's backing VirtualDisplay, or null until [start] /
     * [startTrusted] has run. For the trusted path the display is owned by the
     * privileged helper, not by [virtualDisplay].
     */
    val displayId: Int? get() = virtualDisplay?.display?.displayId ?: trustedDisplayId
    private var presentation: Presentation? = null

    @Volatile
    private var released = false

    /**
     * Create the backing [VirtualDisplay] and show the [Presentation] built by
     * [presentationFor] on it. Call on the main thread.
     */
    fun start(context: Context, presentationFor: (Context, Display) -> Presentation) {
        if (released) return
        val displayManager = context.getSystemService(DisplayManager::class.java)
        if (displayManager == null) {
            Log.e(TAG, "no DisplayManager")
            return
        }
        val virtual = displayManager.createVirtualDisplay(
            displayName, width, height, DENSITY_DPI, surface, FLAGS,
        )
        if (virtual == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            return
        }
        virtualDisplay = virtual
        presentation = try {
            presentationFor(context, virtual.display).also { it.show() }
        } catch (e: Exception) {
            Log.e(TAG, "could not show the desktop presentation", e)
            null
        }
        Log.i(TAG, "ui screen ready ${width}x$height display=${virtual.display.displayId}")
    }

    /**
     * Create the backing display via the *privileged* path (TRUSTED flag) so apps can
     * also be launched onto it — slot desktops use this so the same display hosts both
     * the per-slot Glashutte home Presentation and any apps the user launches there.
     *
     * If a privileged creator is wired but the helper isn't READY yet (typical on a
     * cold start — the helper bootstrap over wireless-debugging ADB takes a second or
     * two), the call retries with backoff up to [MAX_TRUSTED_ATTEMPTS] times. We do
     * **not** silently fall back to an untrusted display: a launched activity on a
     * non-TRUSTED secondary display owned by the calling process renders, but its
     * composited frames are redacted by SurfaceFlinger before reaching the owner's
     * SurfaceTexture (a security feature to prevent screen-snooping). That redaction
     * is exactly the "icon in taskbar but no window" symptom — Android logs `Displayed
     * pkg/Activity` and `visible=true`, yet our GL sample only ever sees wallpaper.
     * Falls back to the untrusted [start] path only when no privileged creator is
     * registered at all (e.g. a dev build without the helper plumbing).
     *
     * Call on the main thread.
     */
    fun startTrusted(context: Context, presentationFor: (Context, Display) -> Presentation) {
        if (released) return
        val createTrusted = WorkspaceController.createVirtualDisplay
        if (createTrusted == null) {
            Log.w(TAG, "no privileged display creator — falling back to untrusted display")
            start(context, presentationFor)
            return
        }
        attemptTrustedStart(context, presentationFor, attempt = 1)
    }

    /**
     * Like [startTrusted] but does **not** attach a Presentation to the trusted display.
     * Used for the per-app activity displays — Samsung One UI parks a transient
     * KEYGUARD_DIALOG window on every secondary display where a Presentation has been
     * `show()`-n, and that window sits at z=2009 with no NOT_TOUCHABLE bit, silently
     * eating every injected touch before it reaches the activity. Skipping `show()`
     * leaves the display Presentation-less, so the launched activity is the only
     * touchable window on it and injected events land where we want them.
     *
     * Same retry policy as [startTrusted] for the helper-not-READY race.
     *
     * Call on the main thread.
     */
    fun startTrustedBare() {
        if (released) return
        val createTrusted = WorkspaceController.createVirtualDisplay ?: return
        attemptTrustedBareStart(createTrusted, attempt = 1)
    }

    private fun attemptTrustedBareStart(
        createTrusted: (String, Int, Int, Int, Surface) -> Int?,
        attempt: Int,
    ) {
        if (released) return
        // Per-app DPI is user-configurable in the Windows tab; the UI's own
        // displays (start / startTrusted) keep the fixed [DENSITY_DPI] so the
        // workspace chrome / taskbar size doesn't shift around per-app.
        val appDpi = WorkspaceController.appDisplayDpi.coerceIn(60, 640)
        val displayId = createTrusted(displayName, width, height, appDpi, surface)
        if (displayId == null) {
            if (attempt >= MAX_TRUSTED_ATTEMPTS) {
                Log.e(
                    TAG,
                    "bare trusted display creation failed after $MAX_TRUSTED_ATTEMPTS attempts " +
                        "— helper never reached READY; window $displayName stays blank",
                )
                return
            }
            Log.i(
                TAG,
                "bare trusted display creation deferred (helper not READY); " +
                    "retry $attempt/${MAX_TRUSTED_ATTEMPTS} in ${TRUSTED_RETRY_MS}ms",
            )
            mainHandler.postDelayed(
                {
                    val again = WorkspaceController.createVirtualDisplay ?: return@postDelayed
                    attemptTrustedBareStart(again, attempt + 1)
                },
                TRUSTED_RETRY_MS,
            )
            return
        }
        trustedDisplayId = displayId
        Log.i(
            TAG,
            "ui screen ready (trusted, bare) ${width}x$height display=$displayId (attempt=$attempt)",
        )
    }

    private fun attemptTrustedStart(
        context: Context,
        presentationFor: (Context, Display) -> Presentation,
        attempt: Int,
    ) {
        if (released) return
        val createTrusted = WorkspaceController.createVirtualDisplay ?: return
        val displayId = createTrusted(displayName, width, height, DENSITY_DPI, surface)
        if (displayId == null) {
            if (attempt >= MAX_TRUSTED_ATTEMPTS) {
                Log.e(
                    TAG,
                    "trusted display creation failed after $MAX_TRUSTED_ATTEMPTS attempts " +
                        "— helper never reached READY; slot $displayName stays blank",
                )
                return
            }
            Log.i(
                TAG,
                "trusted display creation deferred (helper not READY); " +
                    "retry $attempt/${MAX_TRUSTED_ATTEMPTS} in ${TRUSTED_RETRY_MS}ms",
            )
            mainHandler.postDelayed(
                { attemptTrustedStart(context, presentationFor, attempt + 1) },
                TRUSTED_RETRY_MS,
            )
            return
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(displayId)
        if (display == null) {
            Log.e(TAG, "trusted display $displayId not visible to DisplayManager")
            // Privileged display already exists — release it so it doesn't leak.
            WorkspaceController.releaseVirtualDisplay?.invoke(displayId)
            return
        }
        trustedDisplayId = displayId
        presentation = try {
            presentationFor(context, display).also { it.show() }
        } catch (e: Exception) {
            Log.e(TAG, "could not show the desktop presentation on trusted display", e)
            WorkspaceController.releaseVirtualDisplay?.invoke(displayId)
            trustedDisplayId = null
            null
        }
        Log.i(TAG, "ui screen ready (trusted) ${width}x$height display=$displayId (attempt=$attempt)")
    }

    /** Non-null when this UiScreen owns a trusted display created via the privileged path. */
    @Volatile
    private var trustedDisplayId: Int? = null

    /** Pull the latest UI frame into the GL texture. Call on the GL thread. */
    fun updateTexture() {
        if (released) return
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureMatrix)
        } catch (_: Exception) {
            // No frame produced yet — keep the previous texture contents.
        }
    }

    /** Dispatch a tap at pixel [px], [py] into the hosted UI. Call on the main thread. */
    fun dispatchTap(px: Float, py: Float) {
        val root = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, px, py, 0)
        root.dispatchTouchEvent(down)
        root.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /**
     * Dispatch a vertical scroll at pixel [px], [py] into the hosted UI — the same
     * `ACTION_SCROLL` event a mouse wheel produces. Call on the main thread.
     */
    fun dispatchScroll(px: Float, py: Float, vScroll: Float) {
        val root = presentation?.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = px
                y = py
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            },
        )
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        root.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    /**
     * Release the Presentation, VirtualDisplay, and Surface resources. Safe to call from
     * the GL thread (the screen must already be out of the render set).
     *
     * Teardown order matters and must be single-threaded. A [Presentation] is a Dialog
     * whose window registers a system-gesture-exclusion on its display; if the
     * VirtualDisplay is released *before* the dialog is dismissed, the window-decor
     * teardown then unregisters that exclusion against an already-invalid display and the
     * system logs `IllegalArgumentException: ... invalid display: N`. The old code posted
     * the dismiss to the main thread but released the display synchronously here, losing
     * the ordering. So run the whole teardown on the main thread, in dependency order:
     * dismiss the dialog (display still valid) → release the display → release its output
     * Surface → release the SurfaceTexture. Surface/SurfaceTexture.release() are
     * thread-safe; the GL texture [textureId] is freed separately on the GL thread.
     */
    fun release() {
        if (released) return
        released = true
        val p = presentation
        val vd = virtualDisplay
        val tid = trustedDisplayId
        presentation = null
        virtualDisplay = null
        trustedDisplayId = null
        mainHandler.post {
            p?.dismiss()
            vd?.release()
            // Trusted display owned by the privileged helper — released over that channel.
            tid?.let { WorkspaceController.releaseVirtualDisplay?.invoke(it) }
            surface.release()
            surfaceTexture.release()
        }
    }

    private companion object {
        const val TAG = "Glashutte/UiScreen"

        /** Logical density of the desktop display — tunes how large its widgets render. */
        const val DENSITY_DPI = 200

        /** `OWN_CONTENT_ONLY` so it never mirrors; `PRESENTATION` marks it secondary content. */
        const val FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        /**
         * Retry budget for trusted-display creation. The privileged helper takes a few
         * hundred ms to bind over wireless-debugging ADB on cold start; 250 ms × 40 ≈
         * 10 s is far more than typical.
         */
        const val TRUSTED_RETRY_MS = 250L
        const val MAX_TRUSTED_ATTEMPTS = 40
    }
}
