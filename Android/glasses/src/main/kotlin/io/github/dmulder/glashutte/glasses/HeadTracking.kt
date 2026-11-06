package io.github.dmulder.glashutte.glasses

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.util.concurrent.Executors

/**
 * Drives head tracking: finds the glasses on USB, opens a [GlassesSession] on the active
 * [GlassesBackend], polls head pose, and delivers a recentred orientation quaternion via
 * [onPose] so the workspace camera can stay world-fixed.
 *
 * This class owns everything that is *not* vendor specific — USB discovery and permission,
 * the pose-stream and first-pose watchdogs with auto-retry, and app-side recentring
 * ([PoseRecenterer]). The vendor SDK lifecycle and capability detection (on-glasses native
 * DOF, Carina VIO, or Gen1/2 host IMU) live behind [GlassesBackend]; with the stub backend
 * no session ever opens and tracking simply stays off.
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
    /** The vendor backend to open sessions on. Defaults to whatever this build supports. */
    private val backend: GlassesBackend = GlassesBackends.default(),
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

    /**
     * Fired when Android will not hand over the glasses' USB device until the CAMERA
     * permission is granted (the glasses expose a USB video interface). The host activity
     * should request CAMERA and then call [start] again.
     */
    var onCameraPermissionNeeded: (() -> Unit)? = null

    // App-side heading recentre (and 6DOF position recentre); the first pose after a (re)start
    // becomes the reference. Only touched from the pose-polling thread.
    private val recenterer = PoseRecenterer()
    private val orientationOut = FloatArray(4)
    private val positionOut = FloatArray(3)

    // Latency pipeline (see [LatencyExperiments]). With pull-on-render the GL thread calls
    // [pullPose] once per frame: it reads the backend's newest timestamped sample, recentres
    // it and publishes it as one immutable [TimedPose]. [poseLock] guards the recenterer and
    // buffer because that path runs on the GL thread while [startPolling] resets them.
    private val poseLock = Any()
    private val poseBuffer = PoseBuffer()
    private val pullOrientation = FloatArray(4)
    private val pullPosition = FloatArray(3)
    private val extrapolated = FloatArray(4)
    @Volatile private var lastDeliveredSampleNanos = 0L
    private var lastSeenSampleNanos = 0L
    private var appliedLatency: LatencyExperiments.Settings? = null

    init {
        LatencyExperiments.init(appContext)
    }

    /** The open backend session, or null while tracking is off. Created on [worker]. */
    @Volatile private var session: GlassesSession? = null

    /**
     * Begin tracking: locate the glasses on USB and request permission. Idempotent — if
     * tracking is already up this is a no-op; if a previous call missed the device because
     * USB enumeration lagged behind the display, calling again retries. The expected retry
     * trigger is `USB_DEVICE_ATTACHED` in [io.github.dmulder.glashutte.MainActivity].
     */
    fun start() = synchronized(lifecycleLock) {
        if (started) {
            GlassesDiagnostics.d(TAG, "start() ignored — head tracking already started")
            return
        }
        val glassesUsb = usb ?: GlassesUsb(appContext, ::onUsbOpened, ::onUsbDenied).also {
            usb = it
            it.onCameraPermissionNeeded = { onCameraPermissionNeeded?.invoke() }
        }
        GlassesDiagnostics.init(appContext)
        GlassesDiagnostics.beginSession()
        GlassesDiagnostics.put("backend", backend.name)
        val device = glassesUsb.find()
        if (device == null) {
            GlassesDiagnostics.stage(
                GlassesDiagnostics.Stage.USB_DEVICE, GlassesDiagnostics.Status.FAIL,
                "no VITURE glasses USB device visible to Android (see the USB device list below); " +
                    "check the cable/port, or the glasses' PID may be unknown",
            )
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
            GlassesDiagnostics.i(TAG, "no VITURE glasses found on USB — head tracking off (will retry on USB attach)")
            return
        }
        started = true
        // A fresh user-initiated start (or USB attach) — give the auto-retry budget back,
        // unless this start() is itself an auto-restart (which preserves the running count).
        if (!autoRestartScheduled) autoRetryCount = 0
        GlassesDiagnostics.i(TAG, "found glasses, pid=0x${device.productId.toString(16)}")
        GlassesDiagnostics.put("usb.pid", "0x${device.productId.toString(16)}")
        PanelSpecs.activeUsbPid = device.productId
        GlassesDiagnostics.put("usb.productName", runCatching { device.productName }.getOrNull())
        GlassesDiagnostics.stage(
            GlassesDiagnostics.Stage.USB_DEVICE, GlassesDiagnostics.Status.OK,
            "glasses found: vid=0x%04x pid=0x%04x".format(device.vendorId, device.productId),
        )
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
                closeSession()
                runCatching { connection?.close() }
                connection = null
            }
        }
        usb?.release()
        usb = null
    }

    private fun onUsbDenied() {
        GlassesDiagnostics.w(TAG, "USB permission denied — head tracking off")
        // Latch cleared so a later attach / manual retry can ask again.
        started = false
    }

    private fun onUsbOpened(device: UsbDevice, connection: UsbDeviceConnection) {
        this.connection = connection
        val pid = device.productId
        val fd = connection.fileDescriptor
        worker.execute { startSdk(pid, fd) }
    }

    /** Runs on [worker]: open a backend session, then start polling its poses. */
    private fun startSdk(pid: Int, fd: Int) {
        GlassesDiagnostics.i(TAG, "opening session on backend '${backend.name}' (pid=0x${pid.toString(16)})")
        val opened = try {
            val options = SessionOptions(
                sixDof = use6Dof(),
                pathOverride = TrackingExperiments.pathOverride(appContext),
                sdkCacheDir = if (TrackingExperiments.initCacheDir(appContext)) appContext.cacheDir.absolutePath else null,
            )
            backend.open(pid, fd, options)
        } catch (t: Throwable) {
            // e.g. UnsatisfiedLinkError when libglasses_bridge/libglasses cannot be loaded.
            GlassesDiagnostics.e(TAG, "backend '${backend.name}'.open threw", t)
            GlassesDiagnostics.stage(
                GlassesDiagnostics.Stage.SDK_CREATE, GlassesDiagnostics.Status.FAIL,
                "backend.open threw ${t.javaClass.simpleName}: ${t.message}",
            )
            return
        }
        if (opened == null) {
            GlassesDiagnostics.w(TAG, "backend '${backend.name}' did not open a session — head tracking off")
            return
        }
        session = opened
        GlassesDiagnostics.i(TAG, "session open: ${opened.capabilities}")
        startPolling(opened)
    }

    /** Close the current session, if any. Callers hold [lifecycleLock]. */
    private fun closeSession() {
        val current = session ?: return
        session = null
        runCatching { current.close() }
    }

    /**
     * Ask the device/SDK to re-anchor its frame on where the user is looking (Ctrl+Alt+R).
     * Returns false when no session is open or the backend cannot do it.
     */
    fun recenterDevice(): Boolean = session?.recenter() ?: false

    /** Capabilities of the open session, or null while tracking is off. */
    fun capabilities(): GlassesCapabilities? = session?.capabilities

    private fun startPolling(active: GlassesSession) {
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
        synchronized(poseLock) {
            recenterer.reset()
            poseBuffer.reset()
        }
        lastSeenSampleNanos = 0L
        lastDeliveredSampleNanos = 0L
        appliedLatency = null
        pollThread = Thread {
            while (polling) {
                val latency = LatencyExperiments.current()
                applyLatency(active, latency)
                // Pull-on-render: this loop only supervises (diagnostics + watchdogs) and the
                // GL thread reads the pose itself in [pullPose]. Otherwise it is the classic
                // poll-and-push delivery.
                val pose = if (latency.pullOnRender) {
                    active.latestPose()?.takeIf { it.timestampNanos != lastSeenSampleNanos }
                        ?.also { lastSeenSampleNanos = it.timestampNanos }
                } else {
                    active.pollPose()
                }
                if (pose != null) {
                    firstPoseArrived = true
                    GlassesDiagnostics.onPoseSample(
                        pose.w, pose.x, pose.y, pose.z, pose.hasPosition, pose.px, pose.py, pose.pz,
                    )
                    if (latency.pullOnRender) {
                        noteStreamAlive()
                    } else {
                        lastDeliveredSampleNanos = pose.timestampNanos
                        feedPose(pose.w, pose.x, pose.y, pose.z)
                        if (pose.hasPosition) feedPosition(pose.px, pose.py, pose.pz)
                    }
                }
                GlassesDiagnostics.tick(active::diagnostics)
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
                GlassesDiagnostics.stage(
                    GlassesDiagnostics.Stage.POSE_STREAM, GlassesDiagnostics.Status.FAIL,
                    "no pose ever arrived (waited $sinceMs ms, $autoRetryCount auto-restarts); giving up",
                )
                GlassesDiagnostics.w(
                    TAG,
                    "no first pose after $sinceMs ms and $autoRetryCount auto-retries — " +
                        "giving up (manual reconnect still available)",
                )
            }
            return
        }
        autoRestartScheduled = true
        autoRetryCount++
        GlassesDiagnostics.stage(
            GlassesDiagnostics.Stage.POSE_STREAM, GlassesDiagnostics.Status.WARN,
            "no pose within ${sinceMs} ms of starting the session; auto-restart $autoRetryCount/$MAX_AUTO_RETRIES",
        )
        GlassesDiagnostics.w(
            TAG,
            "no first pose after $sinceMs ms — auto-restarting tracking " +
                "(attempt $autoRetryCount/$MAX_AUTO_RETRIES)",
        )
        worker.execute {
            runCatching { restartInternal() }
                .onFailure { GlassesDiagnostics.e(TAG, "auto-restart failed", it) }
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
            GlassesDiagnostics.i(TAG, "restartInternal: superseded by stop() — skipping")
            return
        }
        GlassesDiagnostics.i(TAG, "restartInternal: tearing down SDK/USB for auto-retry")
        polling = false
        pollThread?.interrupt()
        pollThread = null
        closeSession()
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
            GlassesDiagnostics.stage(
                GlassesDiagnostics.Stage.POSE_STREAM, GlassesDiagnostics.Status.WARN,
                "pose stream stalled: nothing for ${sinceMs} ms",
            )
            GlassesDiagnostics.w(TAG, "pose stream stalled — no pose for ${sinceMs}ms (DOF lost)")
            runCatching { onStreamingChanged?.invoke(false) }
        }
    }

    /** Record that a pose just arrived and flip the stream-live latch (and callback) if needed. */
    private fun noteStreamAlive() {
        lastPoseAtMs = System.currentTimeMillis()
        if (!streaming) {
            streaming = true
            GlassesDiagnostics.stage(
                GlassesDiagnostics.Stage.POSE_STREAM, GlassesDiagnostics.Status.OK,
                "pose stream live",
            )
            GlassesDiagnostics.i(TAG, "pose stream live (DOF up)")
            runCatching { onStreamingChanged?.invoke(true) }
        }
    }

    /** Recentre heading against the reference orientation, then deliver via [onPose]. */
    private fun feedPose(w: Float, x: Float, y: Float, z: Float) {
        noteStreamAlive()
        synchronized(poseLock) { recenterer.recenterOrientation(w, x, y, z, orientationOut) }
        GlassesDiagnostics.onPoseDelivered(orientationOut[0], orientationOut[1], orientationOut[2], orientationOut[3])
        onPose(orientationOut[0], orientationOut[1], orientationOut[2], orientationOut[3])
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
        synchronized(poseLock) { recenterer.recenterPosition(px, py, pz, positionOut) }
        onPosition?.invoke(positionOut[0], positionOut[1], positionOut[2])
    }

    /**
     * Push the active latency settings to the backend (SDK predict_time, native poll period)
     * the first time a session sees them and whenever they change.
     */
    private fun applyLatency(active: GlassesSession, latency: LatencyExperiments.Settings) {
        if (latency == appliedLatency) return
        appliedLatency = latency
        runCatching { active.applyLatency(latency.sdkPredictSeconds, latency.nativePollIntervalMicros) }
            .onFailure { GlassesDiagnostics.w(TAG, "applyLatency failed: $it") }
        GlassesDiagnostics.i(
            GlassesDiagnostics.TAG,
            "latency settings active: ${latency.describe()} " +
                "(sdk predict_time=${"%.1f".format(latency.sdkPredictSeconds * 1000)} ms, " +
                "native poll ${latency.nativePollIntervalMicros} us)",
        )
    }

    /**
     * Called by the renderer on the GL thread at the start of every frame ([HeadPoseSource]).
     *
     * With pull-on-render ON it reads the backend's newest timestamped sample, recentres it,
     * publishes it as one immutable [TimedPose] and fills [out] — applying app-side
     * extrapolation when that prediction mode is selected. Returns false (leaving [out]
     * untouched) when pull-on-render is OFF or no pose exists yet, so the renderer keeps the
     * value pushed through [onPose].
     */
    fun pullPose(nowNanos: Long, out: PulledPose): Boolean {
        LatencyTelemetry.onFrame(nowNanos)
        val settings = LatencyExperiments.current()
        if (!settings.pullOnRender) {
            val delivered = lastDeliveredSampleNanos
            if (delivered != 0L) LatencyTelemetry.onPoseUsed(nowNanos - delivered, 0L)
            return false
        }
        val sample = session?.latestPose() ?: return false
        val pose = synchronized(poseLock) {
            recenterer.recenterOrientation(sample.w, sample.x, sample.y, sample.z, pullOrientation)
            var hasPos = false
            if (sample.hasPosition) {
                recenterer.recenterPosition(sample.px, sample.py, sample.pz, pullPosition)
                hasPos = true
            }
            poseBuffer.publish(
                pullOrientation[0], pullOrientation[1], pullOrientation[2], pullOrientation[3],
                hasPos, pullPosition[0], pullPosition[1], pullPosition[2],
                sample.timestampNanos,
            )
        }
        val age = nowNanos - pose.timestampNanos
        val lookahead = if (settings.appExtrapolation) {
            PoseExtrapolator.extrapolate(
                pose, PoseExtrapolator.lookaheadNanos(age, settings.predictionMs.toFloat()), extrapolated,
            )
        } else {
            extrapolated[0] = pose.w; extrapolated[1] = pose.x; extrapolated[2] = pose.y; extrapolated[3] = pose.z
            0L
        }
        out.w = extrapolated[0]; out.x = extrapolated[1]; out.y = extrapolated[2]; out.z = extrapolated[3]
        out.hasPosition = pose.hasPosition
        out.px = pose.px; out.py = pose.py; out.pz = pose.pz
        out.sequence = pose.sequence
        out.ageNanos = age
        out.lookaheadNanos = lookahead
        LatencyTelemetry.onPoseUsed(age, lookahead)
        GlassesDiagnostics.onPoseDelivered(out.w, out.x, out.y, out.z)
        return true
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
