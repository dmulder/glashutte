package io.github.dmulder.glashutte.glasses

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import java.util.concurrent.Executors

/**
 * Drives head tracking: finds the glasses on USB, runs the native SDK lifecycle, polls head
 * pose, and delivers a recentred orientation quaternion via [onPose] so the workspace camera
 * can stay world-fixed.
 *
 * Capability is detected at runtime — on-glasses native DOF, Carina VIO, or Gen1/2 host IMU —
 * and the matching tracking path is used. Recentre is done in Glashutte: the first pose becomes
 * the reference, so wherever the user is looking when tracking starts becomes "straight ahead".
 *
 * It knows nothing of the renderer or the rest of Glashutte — `:app` wires [onPose] to the
 * camera — so the module is a self-contained, swappable head-tracking provider.
 */
class HeadTracking(
    context: Context,
    /** Receives each recentred head-orientation quaternion as (w, x, y, z). */
    private val onPose: (Float, Float, Float, Float) -> Unit,
    /**
     * Receives each recentred head *position* as (x, y, z) metres in the gravity-aligned,
     * heading-recentred world frame — only on tracking paths that actually report 6DOF
     * position (Carina VIO). Null / never called on orientation-only paths (native
     * on-glasses DOF, Gen1/2 host IMU), so the camera stays at the origin there.
     */
    private val onPosition: ((Float, Float, Float) -> Unit)? = null,
    /**
     * Whether to run the Carina device in 6DOF (positional parallax) or 3DOF
     * (orientation-only). Read once per [start] / reconnect — the DOF type must be set
     * before SDK init, so toggling it takes effect on the next reconnect, not live.
     * 6DOF streams px,py,pz for parallax but runs the cameras continuously; 3DOF is the
     * lighter, orientation-only path. Defaults to 3DOF when not supplied.
     */
    private val use6Dof: () -> Boolean = { false },
) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "glashutte-tracking") }

    private var usb: GlassesUsb? = null
    private var connection: UsbDeviceConnection? = null

    @Volatile private var polling = false
    private var pollThread: Thread? = null

    /**
     * Serialises the SDK/USB lifecycle ([start] / [stop] / [restartInternal]). The native
     * SDK handle is a process-wide singleton, and these can now be driven concurrently from
     * three places — the main thread (USB-attach → restartHeadTracking, the reconnect
     * button), the tracking [worker], and the auto-retry on the poll thread. Without this
     * lock two of them can interleave create/destroy on the same handle and wedge or crash
     * the native side (the double-start the MainActivity.onNewIntent comment warns about).
     */
    private val lifecycleLock = Any()

    // Guards against a double start(): one HeadTracking owns one SDK session, and the native
    // SDK handle is a process-wide singleton.
    @Volatile private var started = false

    // Pose-stream watchdog: tracks the wall-clock time of the last [feedPose] callback and
    // declares the stream dead when it goes silent for [STREAM_STALL_TIMEOUT_MS]. The
    // glasses USB endpoint can be killed externally (e.g. Bluetooth radio activation
    // racing the USB host on some OEMs) — Carina logs LIBUSB_ERROR_TIMEOUT but keeps
    // its poll thread alive, so without this watchdog the app keeps claiming DOF is live.
    @Volatile private var lastPoseAtMs = 0L
    @Volatile private var streaming = false

    // Initial-pose watchdog: a tracker can connect (USB opens, SDK starts) yet never deliver
    // a single pose — Carina VIO failing to converge, or the IMU endpoint wedged
    // (LIBUSB_ERROR_NO_DEVICE). [checkStreamWatchdog] can't catch that (it only acts after a
    // first pose has arrived), so without this nothing recovers and DOF just stays dead. We
    // time from [pollStartedAtMs]; if no pose lands within [INITIAL_POSE_TIMEOUT_MS] we tear
    // the session down and re-open, up to [MAX_AUTO_RETRIES] times per user-initiated start.
    @Volatile private var firstPoseArrived = false
    @Volatile private var pollStartedAtMs = 0L
    @Volatile private var autoRetryCount = 0
    @Volatile private var autoRestartScheduled = false

    /** Fires when the pose stream starts or stops. Posted from the poll thread. */
    var onStreamingChanged: ((streaming: Boolean) -> Unit)? = null

    // Reference orientation for recentre; the first pose fills it in.
    @Volatile private var haveRef = false
    private var refW = 1f
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    // Yaw-only twist of the reference orientation, normalised to (cos, sin) about world Y.
    // Recentre cancels HEADING only — pitch/roll stay gravity-anchored — so a head yaw is
    // always a rotation about true vertical and never bleeds into roll (no scene tilt).
    private var refYawCos = 1f
    private var refYawSin = 0f

    // Reference position for recentre (Carina 6DOF only); the first pose fills it in.
    @Volatile private var haveRefPos = false
    private var refPx = 0f
    private var refPy = 0f
    private var refPz = 0f

    /**
     * Whether the active tracking path reports 6DOF position. True only on the Carina VIO
     * poll path (`pose[0..2]` = px,py,pz); false on orientation-only paths, where those
     * three floats are roll/pitch/yaw and must not be fed as a camera translation.
     */
    @Volatile private var providesPosition = false

    /**
     * Begin tracking: locate the glasses on USB and request permission. Idempotent — if
     * tracking is already up this is a no-op; if a previous call missed the device because
     * USB enumeration lagged behind the display, calling again retries. The expected retry
     * trigger is `USB_DEVICE_ATTACHED` in [io.github.dmulder.glashutte.MainActivity].
     */
    fun start() = synchronized(lifecycleLock) {
        if (started) {
            Log.d(TAG, "start() ignored — head tracking already started")
            return
        }
        val glassesUsb = usb ?: GlassesUsb(appContext, ::onUsbOpened, ::onUsbDenied).also { usb = it }
        val device = glassesUsb.find()
        if (device == null) {
            // Don't latch — leave [started] false so a later USB_DEVICE_ATTACHED
            // re-entry can succeed. The display side of the glasses can come up before
            // the USB IMU endpoint enumerates; without retry that brief race kills DOF
            // for the whole session.
            //
            // Clear the auto-restart latch here: if this start() is the tail of an
            // auto-restart whose device vanished, leaving it set would make the NEXT
            // genuine start() preserve the (spent) retry count instead of resetting it,
            // permanently disabling auto-retry for the session.
            autoRestartScheduled = false
            Log.i(TAG, "no VITURE glasses found on USB — head tracking off (will retry on USB attach)")
            return
        }
        started = true
        // A fresh user-initiated start (or USB attach) — give the auto-retry budget back,
        // unless this start() is itself an auto-restart (which preserves the running count).
        if (!autoRestartScheduled) autoRetryCount = 0
        Log.i(TAG, "found glasses, pid=0x${device.productId.toString(16)}")
        glassesUsb.open(device)
    }

    /** Whether [start] has successfully claimed the glasses' USB device for this instance. */
    fun isStarted(): Boolean = started

    /** Stop tracking and release the SDK + USB. */
    fun stop() = synchronized(lifecycleLock) {
        started = false
        polling = false
        // Cancel any pending/in-flight auto-restart: the user asked to stop, so a queued
        // restartInternal must not bring tracking back. It checks this flag under the lock.
        autoRestartScheduled = false
        pollThread?.interrupt()
        pollThread = null
        worker.execute {
            synchronized(lifecycleLock) {
                runCatching { NativeGlasses.stopCarinaPollThread() }
                runCatching { NativeGlasses.stop() }
                runCatching { NativeGlasses.shutdown() }
                runCatching { NativeGlasses.destroy() }
                runCatching { connection?.close() }
                connection = null
            }
        }
        usb?.release()
        usb = null
    }

    private fun onUsbDenied() {
        Log.w(TAG, "USB permission denied — head tracking off")
    }

    private fun onUsbOpened(device: UsbDevice, connection: UsbDeviceConnection) {
        this.connection = connection
        val pid = device.productId
        val fd = connection.fileDescriptor
        worker.execute { startSdk(pid, fd) }
    }

    /** Runs on [worker]: the SDK lifecycle, then the capability-specific tracking path. */
    private fun startSdk(pid: Int, fd: Int) {
        Log.i(TAG, "libglasses ${runCatching { NativeGlasses.getVersion() }.getOrDefault("?")}")
        if (!NativeGlasses.create(pid, fd)) {
            Log.e(TAG, "NativeGlasses.create failed")
            return
        }
        val nativeDof = NativeGlasses.isProductSupportNativeDof(pid)
        val type = NativeGlasses.getDeviceType()
        val carina = type == NativeGlasses.DEVICE_TYPE_CARINA

        // Carina needs its DOF type set after create and before initialize. 6DOF (the SDK
        // default) additionally streams px,py,pz for positional parallax but runs the
        // cameras continuously; 3DOF is the lighter, orientation-only path. Controlled by
        // the [use6Dof] setting and applied here on each (re)connect.
        val want6Dof = carina && !nativeDof && use6Dof()
        if (carina && !nativeDof) {
            NativeGlasses.setDofTypeCarina(want6Dof)
        }
        NativeGlasses.registerStateCallback()
        NativeGlasses.initialize()
        NativeGlasses.start()

        // Position parallax is only available when Carina runs in 6DOF (then pose[0..2] is
        // px,py,pz). In 3DOF — and on the native-DOF / Gen1/2 paths — those floats aren't a
        // usable world position, so we don't feed them and the camera stays at the origin.
        providesPosition = false
        when {
            nativeDof -> {
                Log.i(TAG, "tracking path: native on-glasses DOF")
                NativeGlasses.setupNativeDofDevice()
            }
            carina -> {
                Log.i(
                    TAG,
                    "tracking path: Carina VIO (deviceType=$type) " +
                        if (want6Dof) "— 6DOF, position/parallax on" else "— 3DOF, orientation only",
                )
                providesPosition = want6Dof
                NativeGlasses.startCarinaPollThread()
            }
            else -> {
                Log.i(TAG, "tracking path: Gen1/2 host IMU (deviceType=$type)")
                NativeGlasses.openImu()
            }
        }
        startPolling()
    }

    private fun startPolling() {
        polling = true
        lastPoseAtMs = 0L
        streaming = false
        firstPoseArrived = false
        autoRestartScheduled = false
        pollStartedAtMs = System.currentTimeMillis()
        // Drop any stale recenter reference from a previous session. On reconnect the
        // Carina VIO re-initialises with a fresh internal origin, so the first pose of
        // THIS session must become the new reference — otherwise raw poses in the new
        // frame get recentred against the old origin and the horizon tilts/drifts.
        haveRef = false
        haveRefPos = false
        pollThread = Thread {
            while (polling) {
                if (NativeGlasses.isPoseFresh()) {
                    val pose = NativeGlasses.getPose()
                    // pose[3..6] is the orientation quaternion (w, x, y, z) for every device.
                    if (pose.size >= 7) {
                        firstPoseArrived = true
                        feedPose(pose[3], pose[4], pose[5], pose[6])
                        // pose[0..2] is the world position (px, py, pz) on Carina only.
                        if (providesPosition) feedPosition(pose[0], pose[1], pose[2])
                    }
                }
                checkInitialPoseWatchdog()
                checkStreamWatchdog()
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "glashutte-pose"
            start()
        }
    }

    /**
     * Detect a tracker that connected but never produced its FIRST pose, and auto-recover.
     * Carina VIO sometimes fails to converge, and the IMU endpoint can come up wedged
     * (LIBUSB_ERROR_NO_DEVICE / repeated "get GL pose failed") — in both cases poses never
     * start, so [checkStreamWatchdog] (which only acts after a first pose) can't help and DOF
     * stays dead until the user manually replugs. Here, if no pose has arrived within
     * [INITIAL_POSE_TIMEOUT_MS] of [startPolling], we tear the SDK + USB session down and
     * re-open it, up to [MAX_AUTO_RETRIES] times per user-initiated start. Runs on the poll
     * thread; the restart hops to [worker] so we don't join our own thread.
     */
    private fun checkInitialPoseWatchdog() {
        if (!polling || firstPoseArrived || autoRestartScheduled || pollStartedAtMs == 0L) return
        val sinceMs = System.currentTimeMillis() - pollStartedAtMs
        if (sinceMs < INITIAL_POSE_TIMEOUT_MS) return
        if (autoRetryCount >= MAX_AUTO_RETRIES) {
            // Give up auto-retrying; leave the session as-is so the user's manual reconnect
            // (or a USB re-attach) still works. Log once — guard so we don't spam.
            if (!autoRestartScheduled) {
                autoRestartScheduled = true  // reuse as a latch to silence repeats
                Log.w(
                    TAG,
                    "no first pose after $sinceMs ms and $autoRetryCount auto-retries — " +
                        "giving up (manual reconnect still available)",
                )
            }
            return
        }
        autoRestartScheduled = true
        autoRetryCount++
        Log.w(
            TAG,
            "no first pose after $sinceMs ms — auto-restarting tracking " +
                "(attempt $autoRetryCount/$MAX_AUTO_RETRIES)",
        )
        worker.execute {
            runCatching { restartInternal() }
                .onFailure { Log.e(TAG, "auto-restart failed", it) }
        }
    }

    /**
     * Tear down and re-open the SDK + USB session in place, preserving the auto-retry count
     * (so the budget spans the whole recovery, not each attempt). Runs on [worker].
     */
    private fun restartInternal() = synchronized(lifecycleLock) {
        // If a deliberate stop() landed between scheduling this and acquiring the lock,
        // don't resurrect tracking the user asked to end.
        if (!autoRestartScheduled) {
            Log.i(TAG, "restartInternal: superseded by stop() — skipping")
            return
        }
        Log.i(TAG, "restartInternal: tearing down SDK/USB for auto-retry")
        polling = false
        pollThread?.interrupt()
        pollThread = null
        runCatching { NativeGlasses.stopCarinaPollThread() }
        runCatching { NativeGlasses.stop() }
        runCatching { NativeGlasses.shutdown() }
        runCatching { NativeGlasses.destroy() }
        runCatching { connection?.close() }
        connection = null
        usb?.release()
        usb = null
        started = false
        // autoRestartScheduled stays true so start() preserves autoRetryCount across this
        // re-open; startPolling() clears it once the new session's poll loop is live.
        start()
    }

    /**
     * Detect a stalled pose stream. Once [streaming] has been latched true (first pose
     * arrived), look for a quiet gap of [STREAM_STALL_TIMEOUT_MS] and flip it back to
     * false — the USB endpoint or the SDK has stopped delivering data. The corresponding
     * recover transition fires from [feedPose] the next time a pose arrives.
     */
    private fun checkStreamWatchdog() {
        if (!streaming) return
        val now = System.currentTimeMillis()
        if (lastPoseAtMs == 0L) return
        val sinceMs = now - lastPoseAtMs
        if (sinceMs > STREAM_STALL_TIMEOUT_MS) {
            streaming = false
            Log.w(TAG, "pose stream stalled — no pose for ${sinceMs}ms (DOF lost)")
            runCatching { onStreamingChanged?.invoke(false) }
        }
    }

    /** Recentre heading against the reference orientation, then deliver via [onPose]. */
    private fun feedPose(w: Float, x: Float, y: Float, z: Float) {
        lastPoseAtMs = System.currentTimeMillis()
        if (!streaming) {
            streaming = true
            Log.i(TAG, "pose stream live (DOF up)")
            runCatching { onStreamingChanged?.invoke(true) }
        }
        if (!haveRef) {
            refW = w; refX = x; refY = y; refZ = z
            // Yaw-only twist of the reference about world Y: normalise (w, 0, y, 0). Storing
            // the unit (cos, sin) lets recentre and parallax share one heading rotation.
            val n = kotlin.math.sqrt(w * w + y * y)
            if (n > 1e-6f) { refYawCos = w / n; refYawSin = y / n }
            else { refYawCos = 1f; refYawSin = 0f }
            haveRef = true
        }
        // YAW-ONLY recentre: effective = conjugate(refYawTwist) * raw.
        //
        // We cancel only the reference HEADING (rotation about world/gravity Y), leaving
        // pitch and roll exactly as Carina reports them — gravity-anchored. Because the
        // recentre rotation is purely about world Y, a head yaw is always a rotation about
        // true vertical and can never bleed into roll, so the scene no longer tilts as you
        // pan ("screens angled vertically"). The earlier full-orientation recentre folded
        // the reference's pitch into the yaw axis, which is what caused the tilt.
        //
        // Keeping pitch truthful also defines a single rigid, gravity-aligned world frame,
        // which is what positional parallax ([feedPosition]) needs to stay consistent.
        // conjugate(refYawTwist) = (cos, 0, -sin, 0).
        val c = refYawCos; val s = refYawSin
        val ew = c * w + s * y
        val ex = c * x - s * z
        val ey = c * y - s * w
        val ez = c * z + s * x
        onPose(ew, ex, ey, ez)
    }

    /**
     * Recentre the 6DOF head position and deliver it via [onPosition]. The raw Carina
     * position is in the VIO world frame (gravity-aligned, OpenGL axes, origin at VIO
     * init); we subtract the reference position and rotate the displacement by the inverse
     * reference heading so it lands in the same heading-recentred frame the orientation
     * uses. Result: physically moving your head translates the camera through the fixed
     * workspace — real positional parallax. Only called on 6DOF (Carina) paths.
     */
    private fun feedPosition(px: Float, py: Float, pz: Float) {
        if (!haveRefPos) {
            refPx = px; refPy = py; refPz = pz
            haveRefPos = true
        }
        val dx = px - refPx
        val dy = py - refPy
        val dz = pz - refPz
        // Rotate the world displacement by -refYaw about Y (same heading recentre as the
        // orientation). cos/sin here are the full-angle terms derived from the half-angle
        // (cos, sin) twist: cosθ = c²−s², sinθ = 2cs.
        val c = refYawCos; val s = refYawSin
        val cosT = c * c - s * s
        val sinT = 2f * c * s
        val rx = cosT * dx - sinT * dz
        val rz = sinT * dx + cosT * dz
        onPosition?.invoke(rx, dy, rz)
    }

    private companion object {
        const val TAG = "Glashutte/Tracking"
        const val POLL_INTERVAL_MS = 8L  // ~120 Hz
        /** A live pose stream emits at >=60 Hz; a 1.5 s gap is far past any normal lull
         *  and is the threshold for declaring DOF dead. */
        const val STREAM_STALL_TIMEOUT_MS = 1500L

        /** How long after [startPolling] to wait for the FIRST pose before assuming the
         *  tracker came up dead and auto-restarting. Generous enough to cover Carina VIO
         *  warm-up (it can take ~1-2 s to converge) without nagging on a healthy start. */
        const val INITIAL_POSE_TIMEOUT_MS = 4000L

        /** Auto-restart attempts per user-initiated start before giving up and leaving
         *  recovery to a manual reconnect / USB re-attach. */
        const val MAX_AUTO_RETRIES = 3
    }
}
