package io.github.dmulder.glashutte.glasses

import android.content.Context
import android.os.Build
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.acos

/**
 * One place that records *how far* the head-tracking pipeline got, so a single run on the
 * device identifies the stage that failed instead of "no tracking, no idea why".
 *
 * Everything funnels through here: the glasses module reports stage results and counters, the
 * workspace reports what the renderer does with the pose, and the phone-side diagnostics
 * screen renders [report] / [stageSummary] and exports the event log.
 *
 * Logcat: stage transitions and a 5-second heartbeat use the single tag [TAG]
 * (`adb logcat -s GlashutteDiag`). Detail lines keep their component tags (`Glashutte/...`)
 * and are also copied into an in-memory ring buffer that the log export includes.
 *
 * Pure bookkeeping — nothing in here changes tracking behaviour.
 */
object GlassesDiagnostics {

    const val TAG = "GlashutteDiag"

    /** The pipeline, in the order a pose has to travel. The first non-OK stage is the culprit. */
    enum class Stage(val title: String) {
        BACKEND("1. Backend (SDK in this build?)"),
        USB_DEVICE("2. Glasses visible on USB"),
        USB_PERMISSION("3. USB permission + open"),
        SDK_CREATE("4. SDK handle (create)"),
        SDK_INIT("5. SDK initialize + start"),
        TRACKING_PATH("6. Tracking path"),
        POSE_STREAM("7. Pose stream"),
        RENDERER("8. Pose reaches renderer / world-locked"),
    }

    enum class Status { PENDING, OK, WARN, FAIL, SKIPPED }

    class StageInfo(val status: Status, val detail: String, val atMs: Long)

    private val lock = Any()
    private val stages = LinkedHashMap<Stage, StageInfo>()
    private val sessionInfo = LinkedHashMap<String, String>()
    private val events = ArrayDeque<String>()
    private const val MAX_EVENTS = 800

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile private var appInfo = ""
    @Volatile private var sdkLibsInApk: List<String> = emptyList()
    @Volatile private var initialised = false

    @Volatile var backendName: String = "?"
        private set
    @Volatile var backendSdkBacked: Boolean = false
        private set
    @Volatile var trackingMode: String = "none"
        private set

    /** The display chosen as "the glasses" by [GlassesDisplay.find], or "none". */
    @Volatile var displayInfo: String = "not looked up yet"

    /** USB inventory from the latest probe (one line per device). */
    @Volatile var usbInventory: List<String> = emptyList()

    /** Latest counters from the native layer (`key=value`), refreshed about once a second. */
    @Volatile var nativeStats: Map<String, String> = emptyMap()

    /** Last lines libglasses logged through the SDK log hook (empty when no hook). */
    @Volatile var sdkLogProvider: (() -> String)? = null

    // --- pose statistics (written from the pose polling thread) -------------------------
    private val poseSamples = AtomicLong()
    private var firstPoseAtMs = 0L
    private var sessionStartedAtMs = 0L
    private var lastRateAtMs = 0L
    private var lastRateCount = 0L
    @Volatile private var rateHz = 0f
    private var havePrev = false
    private var pw = 1f; private var px = 0f; private var py = 0f; private var pz = 0f
    private var poseChanges = 0L
    private var totalRotationDeg = 0.0
    @Volatile private var lastChangeAtMs = 0L
    @Volatile private var lastRaw = FloatArray(7)
    @Volatile private var lastRecentred = FloatArray(4)
    @Volatile private var lastHasPosition = false
    private var lastHeartbeatAtMs = 0L

    // --- renderer side -----------------------------------------------------------------
    @Volatile private var rendererViewMode = "?"
    @Volatile private var rendererTrackingActive = false
    @Volatile private var rendererDelivered = 0L

    // --- permissions ---------------------------------------------------------------------
    @Volatile var cameraPermission: String = "unknown"
    @Volatile var usbPermission: String = "unknown"

    /** Capture static facts (app, phone, SDK libraries packaged in the APK). Safe to call often. */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val app = context.applicationContext
        val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val versionCode = pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        appInfo = "app=${app.packageName} versionName=${pkg?.versionName} versionCode=$versionCode " +
            "debuggable=${(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}\n" +
            "device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) " +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "patch=${Build.VERSION.SECURITY_PATCH}\nfingerprint=${Build.FINGERPRINT}"
        sdkLibsInApk = listSdkLibs(app)
        val backendNow = GlassesBackends.default()
        backendName = backendNow.name
        backendSdkBacked = backendNow.isSdkBacked
        stage(
            Stage.BACKEND,
            if (backendNow.isSdkBacked) Status.OK else Status.FAIL,
            if (backendNow.isSdkBacked) {
                "backend '${backendNow.name}' compiled in; SDK libs in APK: ${sdkLibsInApk.ifEmpty { listOf("none") }}"
            } else {
                "STUB backend: this APK was built WITHOUT the Viture SDK, so it can never track. " +
                    "Rebuild with the SDK (see README)."
            },
        )
    }

    private fun listSdkLibs(context: Context): List<String> {
        val wanted = setOf("libglasses.so", "libcarina_vio.so", "libcloud_protocol.so", "libglasses_bridge.so", "libc++_shared.so")
        val found = sortedSetOf<String>()
        val info = context.applicationInfo
        val apks = listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        for (apk in apks) {
            runCatching {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence().forEach { e ->
                        val name = e.name.substringAfterLast('/')
                        if (e.name.startsWith("lib/") && name in wanted) found.add(e.name.removePrefix("lib/"))
                    }
                }
            }
        }
        return found.toList()
    }

    // --- recording -----------------------------------------------------------------------

    fun stage(stage: Stage, status: Status, detail: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) { stages[stage] = StageInfo(status, detail, now) }
        val line = "STAGE ${stage.title}: $status — $detail"
        append('I', TAG, line)
        when (status) {
            Status.FAIL -> Log.e(TAG, line)
            Status.WARN -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
    }

    fun put(key: String, value: Any?) {
        synchronized(lock) { sessionInfo[key] = value.toString() }
    }

    fun setBackend(name: String, sdkBacked: Boolean) {
        backendName = name
        backendSdkBacked = sdkBacked
    }

    fun setTrackingMode(mode: String) {
        trackingMode = mode
        put("trackingMode", mode)
    }

    /** Detail line: goes to logcat under [tag] and into the exportable ring buffer. */
    fun d(tag: String, msg: String) { Log.d(tag, msg); append('D', tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); append('I', tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); append('W', tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        append('E', tag, if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    private fun append(level: Char, tag: String, msg: String) {
        val line = "${LocalTime.now().format(timeFmt)} $level/$tag: $msg"
        synchronized(lock) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** Forget everything about the previous tracking session (stages 2-8, counters, info). */
    fun beginSession() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            Stage.values().filter { it != Stage.BACKEND }.forEach { stages.remove(it) }
            sessionInfo.clear()
        }
        poseSamples.set(0)
        firstPoseAtMs = 0L
        sessionStartedAtMs = now
        lastRateAtMs = now
        lastRateCount = 0L
        rateHz = 0f
        havePrev = false
        poseChanges = 0L
        totalRotationDeg = 0.0
        lastChangeAtMs = 0L
        lastRaw = FloatArray(7)
        lastRecentred = FloatArray(4)
        lastHasPosition = false
        nativeStats = emptyMap()
        trackingMode = "none"
        append('I', TAG, "---- new tracking session ----")
    }

    // --- pose stream ---------------------------------------------------------------------

    /** A raw backend pose arrived (pose-polling thread). */
    fun onPoseSample(w: Float, x: Float, y: Float, z: Float, hasPosition: Boolean, ppx: Float, ppy: Float, ppz: Float) {
        val now = System.currentTimeMillis()
        val n = poseSamples.incrementAndGet()
        if (n == 1L) {
            firstPoseAtMs = now
            stage(
                Stage.POSE_STREAM, Status.OK,
                "first pose after ${now - sessionStartedAtMs} ms: q=(%.3f %.3f %.3f %.3f)".format(w, x, y, z),
            )
        }
        if (havePrev) {
            val dot = abs(w * pw + x * px + y * py + z * pz).coerceAtMost(1f)
            val deg = Math.toDegrees(2.0 * acos(dot.toDouble()))
            if (deg > 0.01) {
                poseChanges++
                totalRotationDeg += deg
                lastChangeAtMs = now
            }
        }
        pw = w; px = x; py = y; pz = z
        havePrev = true
        lastRaw = floatArrayOf(w, x, y, z, ppx, ppy, ppz)
        lastHasPosition = hasPosition
    }

    /** The recentred pose that was handed to the renderer callback. */
    fun onPoseDelivered(w: Float, x: Float, y: Float, z: Float) {
        lastRecentred = floatArrayOf(w, x, y, z)
    }

    /** Called from the poll loop; recomputes the rate and emits the heartbeat. */
    fun tick(nativeSnapshot: () -> Map<String, String>) {
        val now = System.currentTimeMillis()
        if (now - lastRateAtMs >= 1000) {
            val count = poseSamples.get()
            rateHz = (count - lastRateCount) * 1000f / (now - lastRateAtMs)
            lastRateCount = count
            lastRateAtMs = now
            nativeStats = runCatching(nativeSnapshot).getOrDefault(emptyMap())
        }
        if (now - lastHeartbeatAtMs >= 5000) {
            lastHeartbeatAtMs = now
            Log.i(TAG, "heartbeat: ${oneLine()}")
        }
    }

    fun poseRateHz(): Float = rateHz
    fun poseCount(): Long = poseSamples.get()

    // --- renderer side -------------------------------------------------------------------

    fun setRenderer(viewMode: String, trackingActive: Boolean, delivered: Long) {
        rendererViewMode = viewMode
        rendererTrackingActive = trackingActive
        rendererDelivered = delivered
        val poseOk = stageStatus(Stage.POSE_STREAM) == Status.OK
        val (status, detail) = when {
            !poseOk -> Status.PENDING to "waiting for pose stream"
            viewMode == "FREE" -> Status.OK to "view mode FREE (world-locked), $delivered poses delivered"
            else -> Status.WARN to "view mode $viewMode: the camera ignores head pose in PINNED mode " +
                "(tap the lock icon, or enable auto world-lock in Settings)"
        }
        val prev = synchronized(lock) { stages[Stage.RENDERER] }
        if (prev == null || prev.status != status) stage(Stage.RENDERER, status, detail)
    }

    private fun stageStatus(stage: Stage): Status = synchronized(lock) { stages[stage]?.status ?: Status.PENDING }

    // --- views -----------------------------------------------------------------------------

    /** First stage that is not OK, as "N. title: detail", or null when everything is OK. */
    fun firstProblem(): Pair<Stage, StageInfo>? = synchronized(lock) {
        Stage.values().forEach { s ->
            val info = stages[s] ?: StageInfo(Status.PENDING, "not reached yet", 0)
            if (info.status == Status.FAIL || info.status == Status.PENDING || info.status == Status.WARN) {
                return@synchronized s to info
            }
        }
        null
    }

    /** Short, human verdict for banners and the top of the report. */
    fun verdict(): String {
        if (initialised && !backendSdkBacked) {
            return "STUB BUILD: no Viture SDK in this APK, head tracking is impossible. Rebuild with the SDK."
        }
        val problem = firstProblem()
            ?: return if (poseChangesLooksDead()) {
                "Poses arrive (%.0f Hz) but never change: tracker is not tracking head motion".format(rateHz)
            } else {
                "OK: tracking live at %.0f Hz (%s)".format(rateHz, trackingMode)
            }
        val (stage, info) = problem
        return "${stage.title}: ${info.status} — ${info.detail}"
    }

    private fun poseChangesLooksDead(): Boolean {
        val n = poseSamples.get()
        return n > 150 && poseChanges == 0L
    }

    fun oneLine(): String =
        "backend=$backendName mode=$trackingMode rate=%.0fHz poses=${poseSamples.get()} ".format(rateHz) +
            "view=$rendererViewMode dof=$rendererTrackingActive | ${verdict()}"

    /** Short multi-line text for the in-glasses overlay. */
    fun overlayText(): String {
        val sb = StringBuilder()
        sb.append("GLASHUTTE DIAG  backend=").append(backendName).append("  mode=").append(trackingMode).append('\n')
        sb.append("pose %.0f Hz  n=%d  view=%s  tracking=%s\n".format(rateHz, poseSamples.get(), rendererViewMode, rendererTrackingActive))
        val q = lastRaw
        sb.append("raw q=(%.2f %.2f %.2f %.2f)".format(q[0], q[1], q[2], q[3]))
        if (lastHasPosition) sb.append("  p=(%.2f %.2f %.2f)".format(q[4], q[5], q[6]))
        sb.append('\n').append(verdict())
        return sb.toString()
    }

    /** Whether the in-glasses banner should show a warning (and what it says), else null. */
    fun warningBanner(): String? {
        if (!initialised) return null
        if (!backendSdkBacked) {
            return "NO HEAD TRACKING: this is the STUB build (no Viture SDK)"
        }
        val now = System.currentTimeMillis()
        val poseStatus = stageStatus(Stage.POSE_STREAM)
        val problem = firstProblem() ?: return null
        val (stage, info) = problem
        // Give a fresh session a few seconds before nagging; USB/Carina take a moment.
        if (stage.ordinal >= Stage.POSE_STREAM.ordinal && sessionStartedAtMs != 0L &&
            now - sessionStartedAtMs < 6000 && poseStatus != Status.FAIL
        ) {
            return null
        }
        if (stage == Stage.RENDERER) return null
        return "HEAD TRACKING OFF - ${stage.title.substringAfter(". ")}: ${info.detail.take(80)}"
    }

    /** Full multi-section report: what to paste into an issue. */
    fun report(includeEvents: Boolean = true, includeSdkLog: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("=== Glashutte head-tracking diagnostics ===\n")
        sb.append("time=").append(java.time.OffsetDateTime.now()).append('\n')
        sb.append(appInfo).append('\n')
        sb.append("backend=").append(backendName).append(" sdkBacked=").append(backendSdkBacked).append('\n')
        sb.append("sdk libs packaged in APK: ").append(sdkLibsInApk.ifEmpty { listOf("(none)") }).append("\n\n")
        sb.append(InputDiagnostics.helperLine()).append('\n')
        sb.append("INPUT VERDICT: ").append(InputDiagnostics.verdict()).append('\n')
        sb.append("VERDICT: ").append(verdict()).append("\n\n")

        sb.append(InputDiagnostics.report()).append('\n')

        sb.append("-- Pipeline stages (first non-OK stage is where it breaks) --\n")
        synchronized(lock) {
            Stage.values().forEach { s ->
                val info = stages[s]
                val status = info?.status ?: Status.PENDING
                val mark = when (status) {
                    Status.OK -> "[ OK ]"
                    Status.FAIL -> "[FAIL]"
                    Status.WARN -> "[WARN]"
                    Status.SKIPPED -> "[skip]"
                    Status.PENDING -> "[ .. ]"
                }
                sb.append(mark).append(' ').append(s.title).append('\n')
                sb.append("        ").append(info?.detail ?: "not reached yet").append('\n')
            }
        }

        sb.append("\n-- Session --\n")
        sb.append("glasses display: ").append(displayInfo).append('\n')
        sb.append("tracking mode: ").append(trackingMode).append('\n')
        sb.append("camera permission: ").append(cameraPermission).append('\n')
        sb.append("usb permission: ").append(usbPermission).append('\n')
        synchronized(lock) { sessionInfo.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') } }

        sb.append("\n-- Pose --\n")
        sb.append("samples=${poseSamples.get()} rate=%.1f Hz changes=$poseChanges totalRotation=%.1f deg\n".format(rateHz, totalRotationDeg))
        val now = System.currentTimeMillis()
        if (firstPoseAtMs != 0L) {
            sb.append("first pose %d ms after session start; last change %s\n".format(
                firstPoseAtMs - sessionStartedAtMs,
                if (lastChangeAtMs == 0L) "never" else "${now - lastChangeAtMs} ms ago",
            ))
        }
        val r = lastRaw
        sb.append("last raw q(w,x,y,z)=(%.4f %.4f %.4f %.4f)".format(r[0], r[1], r[2], r[3]))
        if (lastHasPosition) sb.append(" p=(%.3f %.3f %.3f)".format(r[4], r[5], r[6]))
        sb.append('\n')
        val o = lastRecentred
        sb.append("last recentred q=(%.4f %.4f %.4f %.4f)\n".format(o[0], o[1], o[2], o[3]))
        sb.append("renderer: viewMode=$rendererViewMode trackingActive=$rendererTrackingActive poseCallbacks=$rendererDelivered\n")

        sb.append("\n-- Native (JNI) counters --\n")
        if (nativeStats.isEmpty()) sb.append("(none: no SDK session)\n") else nativeStats.forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }

        sb.append("\n-- USB devices seen by Android --\n")
        if (usbInventory.isEmpty()) sb.append("(not probed yet)\n") else usbInventory.forEach { sb.append(it).append('\n') }

        if (includeSdkLog) {
            val sdkLog = runCatching { sdkLogProvider?.invoke() }.getOrNull()
            sb.append("\n-- libglasses log (SDK log hook) --\n")
            sb.append(if (sdkLog.isNullOrBlank()) "(empty or SDK has no log hook)\n" else sdkLog)
        }
        if (includeEvents) {
            sb.append("\n-- Event log (most recent last) --\n")
            synchronized(lock) { events.forEach { sb.append(it).append('\n') } }
        }
        return sb.toString()
    }
}
