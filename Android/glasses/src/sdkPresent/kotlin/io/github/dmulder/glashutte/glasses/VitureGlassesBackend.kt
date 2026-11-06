package io.github.dmulder.glashutte.glasses

import android.util.Log

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
        Log.i(TAG, "libglasses ${runCatching { NativeGlasses.getVersion() }.getOrDefault("?")}")
        if (!NativeGlasses.create(productId, usbFd)) {
            Log.e(TAG, "NativeGlasses.create failed")
            return null
        }
        val nativeDof = NativeGlasses.isProductSupportNativeDof(productId)
        val type = NativeGlasses.getDeviceType()
        val carina = type == NativeGlasses.DEVICE_TYPE_CARINA

        // Carina needs its DoF type set after create and before initialize. 6DoF (the SDK
        // default) additionally streams px,py,pz for positional parallax but runs the
        // cameras continuously; 3DoF is the lighter, orientation-only path.
        val want6Dof = carina && !nativeDof && options.sixDof
        if (carina && !nativeDof) {
            NativeGlasses.setDofTypeCarina(want6Dof)
        }
        NativeGlasses.registerStateCallback()
        NativeGlasses.initialize()
        NativeGlasses.start()

        val path = when {
            nativeDof -> {
                Log.i(TAG, "tracking path: native on-glasses DOF")
                NativeGlasses.setupNativeDofDevice()
                TrackingPath.NATIVE_DOF
            }
            carina -> {
                Log.i(
                    TAG,
                    "tracking path: Carina VIO (deviceType=$type) " +
                        if (want6Dof) "— 6DOF, position/parallax on" else "— 3DOF, orientation only",
                )
                NativeGlasses.startCarinaPollThread()
                TrackingPath.CARINA_VIO
            }
            else -> {
                Log.i(TAG, "tracking path: Gen1/2 host IMU (deviceType=$type)")
                NativeGlasses.openImu()
                TrackingPath.HOST_IMU
            }
        }

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
            Log.i(TAG, "recenter (${capabilities.trackingPath}) rc=$rc")
            return rc == 0
        }

        override fun setDisplayMode(mode: GlassesDisplayMode): Boolean {
            // The JNI bridge only sets the flat 1920x1200@60 native mode, and does so while
            // opening a native-DoF session. SBS / other refresh rates need new bridge calls.
            return mode in capabilities.displayModes
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
    }
}
