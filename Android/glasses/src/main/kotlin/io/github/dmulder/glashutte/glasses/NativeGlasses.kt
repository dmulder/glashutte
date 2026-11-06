package io.github.dmulder.glashutte.glasses

/**
 * Kotlin side of the JNI bridge to the native VITURE SDK.
 *
 * Every method is implemented in `glasses_bridge.cpp` and forwards to `libglasses.so`. The
 * library is loaded the first time this object is touched; do not reference it unless the
 * native build is wired up and the glasses are connected.
 *
 * Threading: drive the lifecycle from a single background thread. Pose can be read from any
 * thread — [getPose] is internally locked.
 */
object NativeGlasses {

    init {
        System.loadLibrary("glasses_bridge")
    }

    /** Version string of the bundled `libglasses.so`. */
    external fun getVersion(): String

    // Device lifecycle -------------------------------------------------------

    /** Open the glasses. [fd] is a USB file descriptor; [pid] the USB product id. */
    external fun create(pid: Int, fd: Int): Boolean

    /** [DEVICE_TYPE_CARINA] for VITURE Carina, or -1 for older Gen1/2 hardware. */
    external fun getDeviceType(): Int

    external fun registerStateCallback(): Int
    external fun initialize(): Int
    external fun start(): Int
    external fun stop(): Int
    external fun shutdown(): Int
    external fun destroy()

    // Gen1/Gen2 head tracking (host-side IMU) --------------------------------

    external fun openImu(): Int
    external fun closeImu(): Int

    // Native-DOF devices (on-glasses tracking) -------------------------------

    /** True if [pid]'s product tracks head motion on the glasses themselves. */
    external fun isProductSupportNativeDof(pid: Int): Boolean
    external fun setupNativeDofDevice()
    external fun nativeRecenterDof(): Int

    // Carina (VIO tracking) --------------------------------------------------

    /** Call after [create] and before [initialize]. */
    external fun setDofTypeCarina(is6dof: Boolean): Int
    external fun startCarinaPollThread()
    external fun stopCarinaPollThread()
    external fun carinaStopForModeSwitch(): Boolean
    external fun resetOriginCarina(): Int
    external fun resetPoseCarina(): Int

    // Pose -------------------------------------------------------------------

    /**
     * The latest pose as 7 floats.
     * Gen1/2: `[roll, pitch, yaw, qw, qx, qy, qz]`; Carina: `[px, py, pz, qw, qx, qy, qz]`.
     */
    external fun getPose(): FloatArray

    /** True once, when a pose has arrived since the previous call. */
    external fun isPoseFresh(): Boolean

    /** Carina 6DOF only: `0` = stable, `1` = unstable. */
    external fun getPoseStatus(): Int

    // Cached device state ----------------------------------------------------

    external fun getBrightness(): Int
    external fun getVolume(): Int
    external fun getFilm(): Int

    const val DEVICE_TYPE_CARINA: Int = 2
}
