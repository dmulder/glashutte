package io.github.dmulder.glashutte.glasses

/**
 * [GlassesBackend] on top of the proprietary VITURE Android SDK, via [NativeGlasses] (the JNI
 * bridge in `glasses_bridge.cpp`).
 *
 * This source set is only compiled when the SDK is present at build time; see
 * `glasses/build.gradle.kts`. Everything in here is the SDK lifecycle that used to live in
 * `HeadTracking.startSdk`, unchanged in behaviour:
 *
 *   create -> (Carina: set DoF type) -> register state cb -> initialize -> start
 *          -> one of { native-DoF setup | Carina poll thread | host IMU open }
 *
 * One SDK handle exists per process, so only one session may be open at a time; [open]
 * replaces any previous native handle (the bridge destroys it first).
 */
class VitureGlassesBackend : GlassesBackend {

    override val name: String = "viture-sdk"
    override val isSdkBacked: Boolean = true

    override fun open(productId: Int, usbFd: Int, options: SessionOptions): GlassesSession? {
        val d = GlassesDiagnostics
        val pidHex = "0x${productId.toString(16)}"
        val version = runCatching { NativeGlasses.getVersion() }.getOrDefault("?")
        GlassesDiagnostics.i(TAG, "libglasses $version")
        d.put("libglasses.version", version)
        d.put("sdk.log", runCatching { NativeGlasses.enableSdkLogging(SDK_LOG_LEVEL) }.getOrDefault("failed"))
        d.sdkLogProvider = { NativeGlasses.getSdkLog() }
        NativeGlasses.resetNativeStats()
        d.put("product", runCatching { NativeGlasses.probeProduct(productId) }.getOrDefault("probe failed"))

        if (!NativeGlasses.create(productId, usbFd)) {
            GlassesDiagnostics.e(TAG, "NativeGlasses.create failed")
            d.stage(
                GlassesDiagnostics.Stage.SDK_CREATE, GlassesDiagnostics.Status.FAIL,
                "xr_device_provider_create(pid=$pidHex, fd=$usbFd) returned NULL: the SDK rejected this " +
                    "device or file descriptor (unknown PID, or the USB device is not really open). " +
                    "See the libglasses log section.",
            )
            return null
        }
        d.stage(GlassesDiagnostics.Stage.SDK_CREATE, GlassesDiagnostics.Status.OK, "handle created for pid=$pidHex fd=$usbFd, libglasses $version")

        val nativeDofSdk = NativeGlasses.isProductSupportNativeDof(productId)
        val type = NativeGlasses.getDeviceType()
        val carinaSdk = type == NativeGlasses.DEVICE_TYPE_CARINA
        d.put("sdk.isProductSupportNativeDof", nativeDofSdk)
        d.put("sdk.deviceType", "$type (${deviceTypeName(type)})")

        // Experiments (diagnostics screen) may override what the SDK reports; AUTO reproduces
        // the upstream decision exactly.
        val override = options.pathOverride
        val nativeDof = when (override) {
            TrackingExperiments.PathOverride.AUTO -> nativeDofSdk
            TrackingExperiments.PathOverride.NATIVE_DOF -> true
            else -> false
        }
        val carina = when (override) {
            TrackingExperiments.PathOverride.AUTO -> carinaSdk
            TrackingExperiments.PathOverride.CARINA_3DOF,
            TrackingExperiments.PathOverride.CARINA_6DOF -> true
            else -> false
        }
        // Carina needs its DoF type set after create and before initialize. 6DoF (the SDK
        // default) additionally streams px,py,pz for positional parallax but runs the
        // cameras continuously; 3DoF is the lighter, orientation-only path.
        val requested6Dof = when (override) {
            TrackingExperiments.PathOverride.CARINA_6DOF -> true
            TrackingExperiments.PathOverride.CARINA_3DOF -> false
            else -> options.sixDof
        }
        val want6Dof = carina && !nativeDof && requested6Dof
        d.put("requested6Dof(setting)", options.sixDof)
        d.put("pathOverride", override)
        if (override != TrackingExperiments.PathOverride.AUTO) {
            d.w(TAG, "EXPERIMENT path override active: $override (sdk says deviceType=$type nativeDof=$nativeDofSdk)")
        }
        if (carina && !nativeDof) {
            val rc = NativeGlasses.setDofTypeCarina(want6Dof)
            d.put("rc.setDofTypeCarina(${if (want6Dof) "6dof" else "3dof"})", rcText(rc))
            // Counting callbacks only; they tell us whether IMU / VIO / cameras produce data.
            val cbRc = runCatching { NativeGlasses.registerCarinaDiagCallbacks(want6Dof) }.getOrDefault(-1)
            d.put("rc.registerCallbacksCarina", rcText(cbRc))
        }
        d.put("rc.registerStateCallback", rcText(NativeGlasses.registerStateCallback()))
        val initRc = if (options.sdkCacheDir != null) {
            NativeGlasses.initializeWithCacheDir(options.sdkCacheDir)
        } else {
            NativeGlasses.initialize()
        }
        val startRc = NativeGlasses.start()
        d.put("rc.initialize", rcText(initRc) + if (options.sdkCacheDir != null) " (cacheDir=${options.sdkCacheDir})" else " (cacheDir=NULL)")
        d.put("rc.start", rcText(startRc))
        if (initRc == 0 && startRc == 0) {
            d.stage(GlassesDiagnostics.Stage.SDK_INIT, GlassesDiagnostics.Status.OK, "initialize rc=0, start rc=0")
        } else {
            // Upstream carries on regardless; so do we, but the failure is now on record.
            d.stage(
                GlassesDiagnostics.Stage.SDK_INIT, GlassesDiagnostics.Status.FAIL,
                "initialize rc=${rcText(initRc)}, start rc=${rcText(startRc)} " +
                    "(-7 device rejected, -8 calibration init, -9 serial fetch, -2/-3 USB). " +
                    "Continuing, but poses are unlikely.",
            )
        }

        val path = when {
            nativeDof -> {
                GlassesDiagnostics.i(TAG, "tracking path: native on-glasses DOF")
                NativeGlasses.setupNativeDofDevice()
                d.stage(
                    GlassesDiagnostics.Stage.TRACKING_PATH, GlassesDiagnostics.Status.WARN,
                    "native on-glasses DoF: tracking runs on the glasses and this app receives no " +
                        "pose callback in this path. Luma Ultra is expected to use Carina VIO instead.",
                )
                TrackingPath.NATIVE_DOF
            }
            carina -> {
                GlassesDiagnostics.i(
                    TAG,
                    "tracking path: Carina VIO (deviceType=$type) " +
                        if (want6Dof) "— 6DOF, position/parallax on" else "— 3DOF, orientation only",
                )
                NativeGlasses.startCarinaPollThread()
                d.stage(
                    GlassesDiagnostics.Stage.TRACKING_PATH, GlassesDiagnostics.Status.OK,
                    "Carina VIO ${if (want6Dof) "6DoF" else "3DoF"} (deviceType=$type); polling get_gl_pose_carina at ~120 Hz",
                )
                TrackingPath.CARINA_VIO
            }
            else -> {
                GlassesDiagnostics.i(TAG, "tracking path: Gen1/2 host IMU (deviceType=$type)")
                val imuRc = NativeGlasses.openImu()
                d.put("rc.openImu", rcText(imuRc))
                d.stage(
                    GlassesDiagnostics.Stage.TRACKING_PATH,
                    if (imuRc == 0 && !carinaSdk) GlassesDiagnostics.Status.OK else GlassesDiagnostics.Status.WARN,
                    "Gen1/2 host IMU (deviceType=$type, openImu rc=${rcText(imuRc)})" +
                        if (carinaSdk) ": the SDK reports a Carina device, so this path should not produce poses" else "",
                )
                TrackingPath.HOST_IMU
            }
        }
        d.setTrackingMode(
            when (path) {
                TrackingPath.NATIVE_DOF -> "native on-glasses DoF"
                TrackingPath.CARINA_VIO -> if (want6Dof) "Carina VIO 6DoF" else "Carina VIO 3DoF"
                TrackingPath.HOST_IMU -> "host IMU 3DoF"
            },
        )

        // setupNativeDofDevice() switches native-DoF panels to flat 1920x1200@60.
        val activeMode = if (path == TrackingPath.NATIVE_DOF) {
            GlassesDisplayMode.FLAT_1920x1200_60
        } else {
            null
        }
        return VitureSession(
            capabilities = GlassesCapabilities(
                trackingPath = path,
                // pose[0..2] is a usable world position only on Carina in 6DoF; elsewhere
                // those floats are roll/pitch/yaw and must not become a camera translation.
                providesPosition = path == TrackingPath.CARINA_VIO && want6Dof,
                displayModes = setOfNotNull(activeMode),
                activeDisplayMode = activeMode,
            ),
        )
    }

    private class VitureSession(
        override val capabilities: GlassesCapabilities,
    ) : GlassesSession {

        @Volatile private var closed = false

        override fun pollPose(): PoseSample? {
            if (closed || !NativeGlasses.isPoseFresh()) return null
            val pose = NativeGlasses.getPose()
            // pose[3..6] is the orientation quaternion (w, x, y, z) for every device;
            // pose[0..2] is the world position (px, py, pz) on Carina only.
            if (pose.size < 7) return null
            return PoseSample(
                w = pose[3], x = pose[4], y = pose[5], z = pose[6],
                hasPosition = capabilities.providesPosition,
                px = pose[0], py = pose[1], pz = pose[2],
            )
        }

        override fun recenter(): Boolean {
            if (closed) return false
            val rc = when (capabilities.trackingPath) {
                TrackingPath.NATIVE_DOF -> NativeGlasses.nativeRecenterDof()
                TrackingPath.CARINA_VIO -> NativeGlasses.resetOriginCarina()
                TrackingPath.HOST_IMU -> return false
            }
            GlassesDiagnostics.i(TAG, "recenter (${capabilities.trackingPath}) rc=$rc")
            return rc == 0
        }

        override fun setDisplayMode(mode: GlassesDisplayMode): Boolean {
            // The JNI bridge only sets the flat 1920x1200@60 native mode, and does so while
            // opening a native-DoF session. SBS / other refresh rates need new bridge calls.
            return mode in capabilities.displayModes
        }

        override fun diagnostics(): Map<String, String> {
            if (closed) return emptyMap()
            val out = LinkedHashMap<String, String>()
            NativeGlasses.getNativeStats().split(';').forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) out[kv.substring(0, i)] = kv.substring(i + 1)
            }
            return out
        }

        override fun readDeviceState(): GlassesDeviceState {
            if (closed) return GlassesDeviceState()
            return GlassesDeviceState(
                brightness = NativeGlasses.getBrightness(),
                volume = NativeGlasses.getVolume(),
                film = NativeGlasses.getFilm(),
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { NativeGlasses.stopCarinaPollThread() }
            runCatching { NativeGlasses.stop() }
            runCatching { NativeGlasses.shutdown() }
            runCatching { NativeGlasses.destroy() }
        }
    }

    private companion object {
        const val TAG = "Glashutte/VitureSdk"

        /** 3 = debug: the SDK's own log is the best evidence of why a handshake failed. */
        const val SDK_LOG_LEVEL = 3

        fun rcText(rc: Int): String = "$rc (${
            when (rc) {
                0 -> "SUCCESS"
                -1 -> "INVALID_PARAM"
                -2 -> "USB_UNAVAILABLE"
                -3 -> "USB_EXEC"
                -4 -> "NOT_SUPPORTED"
                -5 -> "NO_DATA"
                -6 -> "DATA_PARSE"
                -7 -> "DEVICE_REJECTED"
                -8 -> "CALIB_INIT"
                -9 -> "SERIAL_FETCH"
                -10 -> "INVALID_STATE"
                -99 -> "UNKNOWN"
                -998 -> "api missing in this SDK"
                else -> "?"
            }
        })"

        fun deviceTypeName(type: Int): String = when (type) {
            0 -> "GEN1"
            1 -> "GEN2"
            2 -> "CARINA"
            else -> "unknown"
        }
    }
}
