package io.github.dmulder.glashutte.glasses

/**
 * Vendor-neutral surface between Glashutte and "something that tells us where the user's head
 * is and lets us control the glasses' display".
 *
 * Everything above this interface (the workspace camera, [HeadTracking]'s watchdogs and
 * recentring) is SDK-free and always compiled. The implementations are:
 *
 *  - `VitureGlassesBackend` — wraps the proprietary VITURE Android SDK through JNI. It is
 *    compiled **only** when the SDK has been supplied locally (see the README); the SDK is
 *    never part of this repository.
 *  - [StubGlassesBackend] — what you get without the SDK. It never opens a session, so the
 *    app runs as a PINNED-view workspace on any external display.
 *
 * Use [GlassesBackends.default] to get whichever one this build supports.
 */
interface GlassesBackend {
    /** Short stable name for logs and diagnostics, e.g. `viture-sdk` or `stub`. */
    val name: String

    /** True when this backend talks to real glasses hardware through a vendor SDK. */
    val isSdkBacked: Boolean

    /**
     * Open a tracking session on the glasses' USB control device.
     *
     * [usbFd] is the file descriptor of the already-permitted USB device identified by
     * [productId]. Returns `null` when the backend cannot drive this device (no SDK, SDK
     * rejected the handshake, ...). A returned session must be released with
     * [GlassesSession.close].
     *
     * May block for seconds; call from a worker thread.
     */
    fun open(productId: Int, usbFd: Int, options: SessionOptions): GlassesSession?
}

/** One live connection to a pair of glasses. Created by [GlassesBackend.open]. */
interface GlassesSession : AutoCloseable {
    val capabilities: GlassesCapabilities

    /**
     * The newest head pose, or `null` when nothing new has arrived since the previous call.
     * Cheap and thread-safe; polled at roughly 120 Hz. Returns `null` after [close].
     */
    fun pollPose(): PoseSample?

    /**
     * Ask the *device/SDK* to re-anchor its coordinate frame on where the user is looking now
     * (native on-glasses recentre, or Carina origin reset). Returns whether the request was
     * accepted. This is separate from the app-side heading recentre in [PoseRecenterer],
     * which works on every backend.
     */
    fun recenter(): Boolean

    /**
     * Switch the glasses' panel mode. Returns `false` when [mode] is not one of
     * [GlassesCapabilities.displayModes].
     */
    fun setDisplayMode(mode: GlassesDisplayMode): Boolean

    /** Latest cached brightness / volume / film state; fields are -1 when unknown. */
    fun readDeviceState(): GlassesDeviceState

    /** Backend-specific counters for the diagnostics screen (`key` to `value`). Never throws. */
    fun diagnostics(): Map<String, String> = emptyMap()

    /** Stop tracking and release every native/USB resource. Safe to call twice. */
    override fun close()
}

/** Where a backend's pose comes from; mirrors the three paths the VITURE SDK exposes. */
enum class TrackingPath {
    /** The glasses track on-device (Beast class) and report an orientation. */
    NATIVE_DOF,

    /** Carina VIO from the stereo cameras + IMU (Luma Ultra class). 3DoF or 6DoF. */
    CARINA_VIO,

    /** Orientation computed on the host from the glasses' raw IMU (older models). */
    HOST_IMU,
}

data class GlassesCapabilities(
    val trackingPath: TrackingPath,
    /** Whether [PoseSample.hasPosition] will ever be true (Carina in 6DoF only). */
    val providesPosition: Boolean,
    /** Panel modes this session can be switched into. May be empty. */
    val displayModes: Set<GlassesDisplayMode> = emptySet(),
    /** The mode the panel is currently in, when known. */
    val activeDisplayMode: GlassesDisplayMode? = null,
)

/** A panel mode; [sideBySide3d] means the left/right halves go to the left/right eye. */
data class GlassesDisplayMode(
    val width: Int,
    val height: Int,
    val refreshHz: Int,
    val sideBySide3d: Boolean = false,
) {
    companion object {
        /** Flat 1920x1200 at 60 Hz — the mode Glashutte renders its mono workspace into. */
        val FLAT_1920x1200_60 = GlassesDisplayMode(1920, 1200, 60)
    }
}

/** Options chosen by the app before a session opens. */
data class SessionOptions(
    /** Carina only: run VIO in 6DoF (positional parallax). Fixed for the life of a session. */
    val sixDof: Boolean = false,
    /** Diagnostics experiment: force a tracking path instead of trusting the SDK. */
    val pathOverride: TrackingExperiments.PathOverride = TrackingExperiments.PathOverride.AUTO,
    /** Diagnostics experiment: writable directory handed to the SDK's initialize (default: NULL). */
    val sdkCacheDir: String? = null,
)

/**
 * One head-pose reading in the SDK's world frame, before app-side recentring.
 * The orientation is a unit quaternion (w, x, y, z); position is metres and is only valid
 * when [hasPosition] is true.
 */
class PoseSample(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val hasPosition: Boolean = false,
    val px: Float = 0f,
    val py: Float = 0f,
    val pz: Float = 0f,
)

data class GlassesDeviceState(
    val brightness: Int = -1,
    val volume: Int = -1,
    val film: Int = -1,
)
