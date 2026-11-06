// JNI bridge between Glashutte and the native VITURE SDK (libglasses.so).
//
// Mirrors the lifecycle of the official VITURE Android demo, trimmed to what Glashutte needs
// for head tracking: device lifecycle, IMU/Carina pose, and native-DOF capability. Camera
// support is intentionally omitted.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <cstring>

#include "viture_version.h"
#include "viture_glasses_provider.h"
#include "viture_device.h"
#include "viture_device_carina.h"
#include "viture_protocol_public.h"

#define LOG_TAG "Glashutte/Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

static XRDeviceProviderHandle g_handle = nullptr;
static int                    g_pid    = -1;
static int                    g_fd     = -1;

// Serialises the device-handle lifecycle. The JNI lifecycle methods can be reached from more
// than one tracking thread — e.g. an old HeadTracking tearing down on its worker while a new
// one (after a reconnect) runs create() on another. Without this lock two threads could call
// xr_device_provider_destroy() on the same g_handle and double-free it, which aborts the
// whole process via Scudo. Pose/state getters use their own locks and never take this one,
// so the 120 Hz poll path is unaffected.
static std::mutex g_lifecycle_mtx;

struct PoseState {
    std::mutex mtx;
    float data[7] = {};    // Gen1/Gen2: [roll,pitch,yaw,qw,qx,qy,qz]
                           // Carina:    [px,py,pz,qw,qx,qy,qz]
    bool fresh = false;
    int pose_status = 1;   // 0=stable, 1=unstable (Carina only)
};
static PoseState g_pose;

struct DeviceState {
    std::mutex mtx;
    int brightness = -1;
    int volume     = -1;
    int film       = -1;
};
static DeviceState g_dev;

static std::atomic<bool> g_poll_running{false};
static std::thread g_poll_thread;

// ---------------------------------------------------------------------------
// SDK callbacks
// ---------------------------------------------------------------------------

static void state_cb(int id, int value) {
    {
        std::lock_guard<std::mutex> lk(g_dev.mtx);
        switch (id) {
            case VITURE_CALLBACK_ID_BRIGHTNESS:          g_dev.brightness = value; break;
            case VITURE_CALLBACK_ID_VOLUME:              g_dev.volume     = value; break;
            case VITURE_CALLBACK_ID_ELECTROCHROMIC_FILM: g_dev.film       = value; break;
            default: break;
        }
    }
    LOGI("state_cb id=%d value=%d", id, value);
}

static void imu_pose_cb(float* data, uint64_t /*ts*/) {
    std::lock_guard<std::mutex> lk(g_pose.mtx);
    memcpy(g_pose.data, data, 7 * sizeof(float));
    g_pose.fresh = true;
}

static void carina_poll_fn() {
    float pose[7] = {};
    while (g_poll_running) {
        int status = 1;
        if (g_handle &&
            xr_device_provider_get_gl_pose_carina(g_handle, pose, 0.0, &status)
                == VITURE_GLASSES_SUCCESS) {
            std::lock_guard<std::mutex> lk(g_pose.mtx);
            memcpy(g_pose.data, pose, 7 * sizeof(float));
            g_pose.pose_status = status;
            g_pose.fresh       = true;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(8333));  // ~120 Hz
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static void reset_pose_state() {
    std::lock_guard<std::mutex> lk(g_pose.mtx);
    memset(g_pose.data, 0, sizeof(g_pose.data));
    g_pose.fresh       = false;
    g_pose.pose_status = 1;
}

static void reset_dev_state() {
    std::lock_guard<std::mutex> lk(g_dev.mtx);
    g_dev.brightness = g_dev.volume = g_dev.film = -1;
}

// Read brightness/volume/film from the device. Must be called after xr_device_provider_start.
static void read_initial_dev_state() {
    int bri = xr_device_provider_get_brightness_level(g_handle);
    int vol = xr_device_provider_get_volume_level(g_handle);
    float film_v = 0.0f;
    int film_err = xr_device_provider_get_film_mode(g_handle, &film_v);
    std::lock_guard<std::mutex> lk(g_dev.mtx);
    if (bri >= 0) g_dev.brightness = bri;
    if (vol >= 0) g_dev.volume     = vol;
    if (film_err == VITURE_GLASSES_SUCCESS)
        g_dev.film = static_cast<int>(film_v + 0.5f);
}

// Tear down the SDK handle. The caller must hold g_lifecycle_mtx. Nulling g_handle before
// returning makes a second teardown a safe no-op rather than a double-free.
static void destroy_handle_locked() {
    if (g_handle) {
        xr_device_provider_destroy(g_handle);
        g_handle = nullptr;
    }
}

// ---------------------------------------------------------------------------
// JNI — all methods of io.github.dmulder.glashutte.glasses.NativeGlasses
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getVersion(JNIEnv* env, jobject) {
    const char* v = GetVersionString();
    LOGI("libglasses version: %s", v ? v : "(null)");
    return env->NewStringUTF(v ? v : "");
}

JNIEXPORT jboolean JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_create(JNIEnv*, jobject, jint pid, jint fd) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    destroy_handle_locked();
    reset_pose_state();
    reset_dev_state();
    g_handle = xr_device_provider_create(pid, fd);
    if (!g_handle) { LOGE("create failed pid=0x%04x fd=%d", pid, fd); return JNI_FALSE; }
    g_pid = pid;
    g_fd  = fd;
    LOGI("create ok pid=0x%04x fd=%d", pid, fd);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getDeviceType(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return -1;
    return xr_device_provider_get_device_type(g_handle);
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_registerStateCallback(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_register_state_callback(g_handle, state_cb);
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_initialize(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    int r = xr_device_provider_initialize(g_handle, nullptr, nullptr);
    if (r != VITURE_GLASSES_SUCCESS) LOGE("initialize failed: %d", r);
    return r;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_start(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    int r = xr_device_provider_start(g_handle);
    if (r != VITURE_GLASSES_SUCCESS) { LOGE("start failed: %d", r); return r; }
    read_initial_dev_state();
    return r;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_stop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_stop(g_handle);
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_shutdown(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_shutdown(g_handle);
}

JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_destroy(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    destroy_handle_locked();
}

// Gen1/Gen2: register the pose callback and open the IMU at the high sample rate.
JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_openImu(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    xr_device_provider_register_imu_pose_callback(g_handle, imu_pose_cb);
    int r = xr_device_provider_open_imu(g_handle, VITURE_IMU_MODE_POSE, VITURE_IMU_FREQUENCY_HIGH);
    if (r != VITURE_GLASSES_SUCCESS) LOGE("openImu failed: %d", r);
    return r;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_closeImu(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_close_imu(g_handle, VITURE_IMU_MODE_POSE);
}

// Capability query: true if the product does head tracking on the glasses themselves.
// Operates on the product id, so it can be called before create().
JNIEXPORT jboolean JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_isProductSupportNativeDof(JNIEnv*, jobject, jint pid) {
    return xr_device_provider_is_product_support_native_dof(pid) ? JNI_TRUE : JNI_FALSE;
}

// Switch a native-DOF device into native mode for on-device 3DOF tracking.
JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_setupNativeDofDevice(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return;
    int rc = xr_device_provider_native_set_mode(g_handle, 1);
    if (rc != VITURE_GLASSES_SUCCESS) LOGE("native_set_mode(1) failed: %d", rc);

    rc = xr_device_provider_native_set_display_mode(
        g_handle, VITURE_NATIVE_DISPLAY_MODE_1920_1200_60HZ);
    if (rc != VITURE_GLASSES_SUCCESS) LOGE("native_set_display_mode failed: %d", rc);

    rc = xr_device_provider_native_set_dof(g_handle, VITURE_NATIVE_DOF_3);
    if (rc != VITURE_GLASSES_SUCCESS) LOGE("native_set_dof failed: %d", rc);

    rc = xr_device_provider_native_recenter_dof(g_handle);
    if (rc != VITURE_GLASSES_SUCCESS) LOGE("native_recenter_dof failed: %d", rc);
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_nativeRecenterDof(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_native_recenter_dof(g_handle);
}

// Carina: must be called after create and before initialize.
JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_setDofTypeCarina(JNIEnv*, jobject, jboolean is6dof) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_set_dof_type_carina(g_handle, is6dof ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_startCarinaPollThread(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (g_poll_running) return;
    g_poll_running = true;
    g_poll_thread  = std::thread(carina_poll_fn);
}

JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_stopCarinaPollThread(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_poll_running) return;
    g_poll_running = false;
    if (g_poll_thread.joinable()) g_poll_thread.join();
}

JNIEXPORT jboolean JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_carinaStopForModeSwitch(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (g_poll_running) {
        g_poll_running = false;
        if (g_poll_thread.joinable()) g_poll_thread.join();
    }
    destroy_handle_locked();
    reset_pose_state();
    reset_dev_state();
    return JNI_TRUE;
}

// Carina: SDK-level yaw + position reset — relocates the coordinate frame to the user.
JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_resetOriginCarina(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    float pose[7];
    {
        std::lock_guard<std::mutex> pl(g_pose.mtx);
        memcpy(pose, g_pose.data, sizeof(pose));
    }
    return xr_device_provider_reset_origin_carina(g_handle, pose);
}

// Carina: full VIO re-initialisation.
JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_resetPoseCarina(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    return xr_device_provider_reset_pose_carina(g_handle);
}

// Returns 7 floats: [roll,pitch,yaw,qw,qx,qy,qz] (Gen1/2) or [px,py,pz,qw,qx,qy,qz] (Carina).
JNIEXPORT jfloatArray JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getPose(JNIEnv* env, jobject) {
    jfloatArray arr = env->NewFloatArray(7);
    std::lock_guard<std::mutex> lk(g_pose.mtx);
    env->SetFloatArrayRegion(arr, 0, 7, g_pose.data);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_isPoseFresh(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_pose.mtx);
    bool f = g_pose.fresh;
    g_pose.fresh = false;
    return f ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getPoseStatus(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_pose.mtx);
    return g_pose.pose_status;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getBrightness(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_dev.mtx);
    return g_dev.brightness;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getVolume(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_dev.mtx);
    return g_dev.volume;
}

JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getFilm(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_dev.mtx);
    return g_dev.film;
}

}  // extern "C"
