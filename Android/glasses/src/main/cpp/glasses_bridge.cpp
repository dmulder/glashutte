// JNI bridge between Glashutte and the native VITURE SDK (libglasses.so).
//
// Mirrors the lifecycle of the official VITURE Android demo, trimmed to what Glashutte needs
// for head tracking: device lifecycle, IMU/Carina pose, and native-DOF capability. Camera
// support is intentionally omitted.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <ctime>

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
    int64_t ts_ns = 0;     // CLOCK_MONOTONIC (== System.nanoTime) when `data` was stored; 0 = none
};
static PoseState g_pose;

// CLOCK_MONOTONIC in nanoseconds: the same clock as Java's System.nanoTime(), so the Kotlin
// side can compute pose age without any conversion.
static int64_t monotonic_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// Latency experiments (set from the diagnostics screen; defaults reproduce the previous
// behaviour: no SDK-side prediction, ~120 Hz polling).
static std::atomic<double> g_predict_time_s{0.0};
static std::atomic<int>    g_poll_interval_us{8333};

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
// Diagnostics: counters the Kotlin side reads to show which stage of the tracking pipeline
// is alive. They never influence behaviour.
// ---------------------------------------------------------------------------

struct NativeStats {
    std::atomic<long> poll_attempts{0};   // get_gl_pose_carina calls
    std::atomic<long> poll_ok{0};         // ... that returned SUCCESS
    std::atomic<long> poll_fail{0};
    std::atomic<int>  last_poll_rc{0};
    std::atomic<long> poll_bad_quat{0};   // SUCCESS but quaternion not unit length
    std::atomic<long> imu_pose_cb{0};     // Gen1/2 pose callback invocations
    std::atomic<long> carina_pose_cb{0};  // Carina pose callback (camera rate, ~25 Hz)
    std::atomic<long> carina_imu_cb{0};   // Carina raw IMU callback
    std::atomic<long> carina_camera_cb{0};// Carina stereo camera frames
    std::atomic<int>  carina_cb_rc{-999}; // rc of register_callbacks_carina (-999 = not called)
    std::atomic<int>  camera_cb_wanted{0};
    std::atomic<int>  cam_w{0};
    std::atomic<int>  cam_h{0};
    std::atomic<int>  pose_status{1};
};
static NativeStats g_stats;

// Last lines libglasses logged through the SDK log hook (when the SDK has one).
static std::mutex g_sdklog_mtx;
static std::deque<std::string> g_sdklog;
static constexpr size_t SDK_LOG_MAX_LINES = 400;
static std::atomic<int> g_sdk_log_hooked{0};

static void sdk_log_hook(int level, const char* tag, const char* message) {
    int prio = level <= 1 ? ANDROID_LOG_ERROR : (level == 2 ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG);
    __android_log_print(prio, "Glashutte/SdkLog", "[%s] %s", tag ? tag : "", message ? message : "");
    char line[512];
    snprintf(line, sizeof(line), "L%d [%s] %s", level, tag ? tag : "", message ? message : "");
    std::lock_guard<std::mutex> lk(g_sdklog_mtx);
    g_sdklog.emplace_back(line);
    while (g_sdklog.size() > SDK_LOG_MAX_LINES) g_sdklog.pop_front();
}

// Entry points that may be missing from a given SDK release. Resolved at runtime so that
// this bridge still loads (and tracking still works) against an SDK that lacks them.
typedef void (*SdkLogHookFn)(int, const char*, const char*);
typedef void (*SetLogHookFn)(SdkLogHookFn);
typedef void (*SetLogLevelFn)(int);
typedef int  (*IsProductIdValidFn)(int);
typedef int  (*GetMarketNameFn)(int, char*, int*);
typedef int  (*RegisterCarinaCbFn)(XRDeviceProviderHandle, XRPoseCallback, XRVSyncCallback,
                                   XRImuCallback, XRCameraCallback);
typedef int  (*CameraPidFn)(int);

static void* sdk_symbol(const char* name) {
    static void* lib = dlopen("libglasses.so", RTLD_NOW);
    void* sym = lib ? dlsym(lib, name) : nullptr;
    if (!sym) sym = dlsym(RTLD_DEFAULT, name);
    return sym;
}

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
    g_stats.imu_pose_cb++;
    std::lock_guard<std::mutex> lk(g_pose.mtx);
    memcpy(g_pose.data, data, 7 * sizeof(float));
    g_pose.ts_ns = monotonic_ns();
    g_pose.fresh = true;
}

static void carina_pose_cb(float*, double) { g_stats.carina_pose_cb++; }
static void carina_imu_cb(float*, double) { g_stats.carina_imu_cb++; }
static void carina_camera_cb(char*, char*, char*, char*, double, int w, int h) {
    g_stats.carina_camera_cb++;
    g_stats.cam_w = w;
    g_stats.cam_h = h;
}

static void carina_poll_fn() {
    float pose[7] = {};
    while (g_poll_running) {
        int status = 1;
        if (g_handle) {
            int rc = xr_device_provider_get_gl_pose_carina(
                g_handle, pose, g_predict_time_s.load(std::memory_order_relaxed), &status);
            g_stats.poll_attempts++;
            g_stats.last_poll_rc = rc;
            if (rc == VITURE_GLASSES_SUCCESS) {
                long n = ++g_stats.poll_ok;
                g_stats.pose_status = status;
                float norm2 = pose[3] * pose[3] + pose[4] * pose[4] +
                              pose[5] * pose[5] + pose[6] * pose[6];
                if (norm2 < 0.81f || norm2 > 1.21f) {
                    long bad = ++g_stats.poll_bad_quat;
                    if (bad <= 3) {
                        LOGE("carina pose has non-unit quaternion q=(%.3f %.3f %.3f %.3f) status=%d",
                             pose[3], pose[4], pose[5], pose[6], status);
                    }
                }
                if (n == 1) {
                    LOGI("first carina pose p=(%.3f %.3f %.3f) q=(%.3f %.3f %.3f %.3f) status=%d",
                         pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6], status);
                }
                std::lock_guard<std::mutex> lk(g_pose.mtx);
                memcpy(g_pose.data, pose, 7 * sizeof(float));
                g_pose.pose_status = status;
                g_pose.ts_ns       = monotonic_ns();
                g_pose.fresh       = true;
            } else {
                long f = ++g_stats.poll_fail;
                if (f <= 5 || f % 500 == 0) {
                    LOGE("get_gl_pose_carina rc=%d (failure #%ld, %ld ok so far)",
                         rc, f, g_stats.poll_ok.load());
                }
            }
        }
        std::this_thread::sleep_for(
            std::chrono::microseconds(g_poll_interval_us.load(std::memory_order_relaxed)));
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
    g_pose.ts_ns       = 0;
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

// Same as initialize(), but hands the SDK a writable directory for its calibration cache.
JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_initializeWithCacheDir(
    JNIEnv* env, jobject, jstring cacheDir) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    const char* dir = cacheDir ? env->GetStringUTFChars(cacheDir, nullptr) : nullptr;
    int r = xr_device_provider_initialize(g_handle, nullptr, dir);
    if (dir) env->ReleaseStringUTFChars(cacheDir, dir);
    if (r != VITURE_GLASSES_SUCCESS) LOGE("initialize(cacheDir) failed: %d", r);
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

// Newest cached pose WITHOUT consuming the fresh flag and without allocating: copies the 7
// floats into `out` and returns the sample's CLOCK_MONOTONIC timestamp in ns (0 = no pose yet).
// Cheap enough to call once per rendered frame.
JNIEXPORT jlong JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getPoseTimed(JNIEnv* env, jobject, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 7) return 0;
    float local[7];
    int64_t ts;
    {
        std::lock_guard<std::mutex> lk(g_pose.mtx);
        memcpy(local, g_pose.data, sizeof(local));
        ts = g_pose.ts_ns;
    }
    if (ts == 0) return 0;
    env->SetFloatArrayRegion(out, 0, 7, local);
    return static_cast<jlong>(ts);
}

// Seconds of look-ahead handed to get_gl_pose_carina (its predict_time). 0 = no prediction.
JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_setPredictTimeSeconds(JNIEnv*, jobject, jdouble seconds) {
    double s = seconds;
    if (!(s >= 0.0)) s = 0.0;
    if (s > 0.1) s = 0.1;
    g_predict_time_s.store(s, std::memory_order_relaxed);
}

// Native poll period in microseconds (clamped to 1..20 ms).
JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_setPollIntervalMicros(JNIEnv*, jobject, jint micros) {
    int us = micros;
    if (us < 1000) us = 1000;
    if (us > 20000) us = 20000;
    g_poll_interval_us.store(us, std::memory_order_relaxed);
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

// ---------------------------------------------------------------------------
// Diagnostics JNI (read-only probes; none of these change tracking behaviour except
// registerCarinaDiagCallbacks, which only installs counting callbacks)
// ---------------------------------------------------------------------------

// Raise the SDK's own log level and mirror its log lines into logcat (tag Glashutte/SdkLog)
// and an in-process ring buffer. Returns a short description of what the SDK supports.
JNIEXPORT jstring JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_enableSdkLogging(JNIEnv* env, jobject, jint level) {
    std::string out;
    auto set_level = reinterpret_cast<SetLogLevelFn>(sdk_symbol("xr_device_provider_set_log_level"));
    auto set_hook  = reinterpret_cast<SetLogHookFn>(sdk_symbol("xr_device_provider_set_log_hook"));
    if (set_level) { set_level(level); out += "set_log_level=ok "; } else { out += "set_log_level=missing "; }
    if (set_hook) {
        set_hook(sdk_log_hook);
        g_sdk_log_hooked = 1;
        out += "set_log_hook=ok";
    } else {
        out += "set_log_hook=missing";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getSdkLog(JNIEnv* env, jobject) {
    std::string all;
    {
        std::lock_guard<std::mutex> lk(g_sdklog_mtx);
        for (const auto& l : g_sdklog) { all += l; all += '\n'; }
    }
    return env->NewStringUTF(all.c_str());
}

// What the SDK says about a USB product id: valid?, market name, native-DOF, camera ids.
JNIEXPORT jstring JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_probeProduct(JNIEnv* env, jobject, jint pid) {
    char buf[256];
    std::string out;
    auto valid = reinterpret_cast<IsProductIdValidFn>(sdk_symbol("xr_device_provider_is_product_id_valid"));
    snprintf(buf, sizeof(buf), "sdk_knows_pid=%s ", valid ? (valid(pid) ? "yes" : "NO") : "unknown(api missing)");
    out += buf;
    auto market = reinterpret_cast<GetMarketNameFn>(sdk_symbol("xr_device_provider_get_market_name"));
    if (market) {
        char name[128] = {};
        int len = sizeof(name) - 1;
        int rc = market(pid, name, &len);
        name[sizeof(name) - 1] = 0;
        snprintf(buf, sizeof(buf), "market_name='%s'(rc=%d) ", name, rc);
        out += buf;
    } else {
        out += "market_name=unknown(api missing) ";
    }
    snprintf(buf, sizeof(buf), "native_dof=%d ", xr_device_provider_is_product_support_native_dof(pid));
    out += buf;
    auto cam_vid = reinterpret_cast<CameraPidFn>(sdk_symbol("xr_camera_provider_get_camera_vid"));
    auto cam_pid = reinterpret_cast<CameraPidFn>(sdk_symbol("xr_camera_provider_get_camera_pid"));
    if (cam_vid && cam_pid) {
        snprintf(buf, sizeof(buf), "camera_usb=%04x:%04x", cam_vid(pid), cam_pid(pid));
        out += buf;
    } else {
        out += "camera_usb=unknown(api missing)";
    }
    return env->NewStringUTF(out.c_str());
}

// Carina only: install counting callbacks so we can tell whether the IMU, the pose estimator
// and (6DoF only) the stereo cameras are producing data. Call after create, before initialize.
JNIEXPORT jint JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_registerCarinaDiagCallbacks(
    JNIEnv*, jobject, jboolean withCamera) {
    std::lock_guard<std::mutex> lk(g_lifecycle_mtx);
    if (!g_handle) return VITURE_GLASSES_ERROR_INVALID_PARAM;
    auto reg = reinterpret_cast<RegisterCarinaCbFn>(sdk_symbol("xr_device_provider_register_callbacks_carina"));
    if (!reg) { g_stats.carina_cb_rc = -998; return -998; }
    g_stats.camera_cb_wanted = withCamera ? 1 : 0;
    int rc = reg(g_handle, carina_pose_cb, nullptr, carina_imu_cb,
                 withCamera ? carina_camera_cb : nullptr);
    g_stats.carina_cb_rc = rc;
    return rc;
}

// "key=value;key=value" snapshot of the counters above plus the cached pose status.
JNIEXPORT jstring JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_getNativeStats(JNIEnv* env, jobject) {
    char buf[640];
    snprintf(buf, sizeof(buf),
             "poll_attempts=%ld;poll_ok=%ld;poll_fail=%ld;last_poll_rc=%d;poll_bad_quat=%ld;"
             "pose_status=%d;imu_pose_cb=%ld;carina_pose_cb=%ld;carina_imu_cb=%ld;"
             "carina_camera_frames=%ld;camera_cb_wanted=%d;camera_wh=%dx%d;"
             "carina_cb_rc=%d;sdk_log_hooked=%d;poll_running=%d;handle=%d;"
             "predict_ms=%.1f;poll_interval_us=%d",
             g_stats.poll_attempts.load(), g_stats.poll_ok.load(), g_stats.poll_fail.load(),
             g_stats.last_poll_rc.load(), g_stats.poll_bad_quat.load(),
             g_stats.pose_status.load(), g_stats.imu_pose_cb.load(), g_stats.carina_pose_cb.load(),
             g_stats.carina_imu_cb.load(), g_stats.carina_camera_cb.load(),
             g_stats.camera_cb_wanted.load(), g_stats.cam_w.load(), g_stats.cam_h.load(),
             g_stats.carina_cb_rc.load(), g_sdk_log_hooked.load(), g_poll_running.load() ? 1 : 0,
             g_handle ? 1 : 0,
             g_predict_time_s.load() * 1000.0, g_poll_interval_us.load());
    return env->NewStringUTF(buf);
}

// Zero the per-session counters (called at the start of every session).
JNIEXPORT void JNICALL
Java_io_github_dmulder_glashutte_glasses_NativeGlasses_resetNativeStats(JNIEnv*, jobject) {
    g_stats.poll_attempts = 0; g_stats.poll_ok = 0; g_stats.poll_fail = 0;
    g_stats.last_poll_rc = 0; g_stats.poll_bad_quat = 0;
    g_stats.imu_pose_cb = 0; g_stats.carina_pose_cb = 0; g_stats.carina_imu_cb = 0;
    g_stats.carina_camera_cb = 0; g_stats.carina_cb_rc = -999; g_stats.camera_cb_wanted = 0;
    g_stats.cam_w = 0; g_stats.cam_h = 0; g_stats.pose_status = 1;
}

}  // extern "C"
