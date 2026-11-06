package io.github.dmulder.glashutte.glasses

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap

/**
 * Records how far input gets on its way into a launched app, separately for the three ways
 * the owner drives the workspace: the phone screen as a trackpad, a Bluetooth mouse, and a
 * Bluetooth keyboard. One run on the device names the stage that fails for each path instead
 * of "the app loads but does nothing".
 *
 * Stage 0 of every path is always the same question, answered first in every log: **is the
 * shell-uid helper connected?** Without it nothing can be injected into an app's virtual
 * display, the mouse is never read, and the phone shows the setup wizard instead of the
 * trackpad.
 *
 * Callers: `PrivilegedService` (helper lifecycle and injection results), the trackpad view,
 * the main activity (phone scene, key events, device inventory), the app's mouse handlers,
 * and the workspace renderer (cursor, clicks, hit-testing). The Diagnostics screen shows
 * [report] / [pathReport]; per-event lines go to logcat under [TAG] as `input[trackpad]`,
 * `input[mouse]` and `input[keyboard]` (`adb logcat -s GlashutteDiag`) and into
 * [GlassesDiagnostics]'s ring buffer. Pure bookkeeping: nothing here changes input behaviour.
 */
object InputDiagnostics {

    const val TAG = GlassesDiagnostics.TAG

    enum class Path(val title: String, val tag: String) {
        TRACKPAD("Phone screen as trackpad", "trackpad"),
        MOUSE("Bluetooth mouse", "mouse"),
        KEYBOARD("Bluetooth keyboard", "keyboard"),
    }

    enum class Status { PENDING, OK, WARN, FAIL }

    class StageLine(val title: String, val status: Status, val detail: String)

    enum class Route { APP, SLOT_UI, MISS }

    /** What the renderer decided for one click or scroll. */
    class HitRecord(
        val path: Path,
        val kind: String,
        val route: Route,
        val reason: String,
        val cursorNdc: String,
        val screenIdx: Int,
        val slotPx: String,
        val windowPkg: String?,
        val windowDisplayId: Int?,
        val localX: Int,
        val localY: Int,
        val displaySize: String,
        /** Max NDC distance between the cursor and the re-projected hit point; NaN = not checked. */
        val roundTripErr: Float,
        val projection: String,
    )

    private class Counter(var count: Long = 0, var lastAtMs: Long = 0, var lastDetail: String = "")

    private class PathState {
        val counters = LinkedHashMap<String, Counter>()
        var lastHit: HitRecord? = null
        var lastHitAtMs = 0L
        var hits = 0L
        var appHits = 0L
        var slotHits = 0L
        val missReasons = LinkedHashMap<String, Int>()
        var lastInject: String? = null
        var lastInjectOutcome: InjectOutcome? = null
        var injectOk = 0L
        val injectFail = LinkedHashMap<Int, Int>()
        var maxRoundTripErr = 0f
        var testStartedAtMs = 0L
    }

    private val lock = Any()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val paths = EnumMap<Path, PathState>(Path::class.java).also { m -> Path.values().forEach { m[it] = PathState() } }
    private fun state(path: Path): PathState = paths.getValue(path)

    // --- helper lifecycle ---------------------------------------------------------------------
    @Volatile private var helperState = "not initialised"
    @Volatile private var helperDetail = ""
    @Volatile private var helperReady = false
    @Volatile private var helperReadySinceMs = 0L
    @Volatile private var helperPingOk: Boolean? = null
    @Volatile private var helperDeaths = 0
    @Volatile private var helperInfo = ""
    @Volatile private var injectorOk: Boolean? = null
    @Volatile private var injectorSummary = "not probed yet"
    @Volatile private var helperReport: String? = null
    @Volatile private var helperReportAtMs = 0L
    @Volatile private var helperStats: Map<String, String> = emptyMap()
    private val timeline = ArrayDeque<String>()

    // --- phone side facts ---------------------------------------------------------------------
    @Volatile private var phoneScene = "unknown (the phone screen has not been shown yet)"
    @Volatile private var trackpadVisible: Boolean? = null
    @Volatile private var devices: List<String> = emptyList()
    @Volatile private var mouseNames: List<String> = emptyList()
    @Volatile private var keyboardNames: List<String> = emptyList()
    @Volatile private var devicesScanned = false
    @Volatile private var typingTarget = "none"
    @Volatile private var typingTargetDisplay = -1
    @Volatile private var lastHitPath = Path.TRACKPAD
    @Volatile private var cursorNdc = "(unknown)"
    @Volatile private var activePath: Path? = null
    private var scrollLoggedAtMs = 0L
    private var lastFailLogAtMs = 0L

    // ----------------------------------------------------------------------------------------
    // Helper lifecycle

    /** The helper's connection state changed (or its failure reason did). */
    fun helperState(state: String, ready: Boolean, detail: String = "") {
        val changed = state != helperState || detail != helperDetail || ready != helperReady
        helperState = state
        helperDetail = detail
        if (ready && !helperReady) helperReadySinceMs = System.currentTimeMillis()
        helperReady = ready
        if (!ready) {
            helperPingOk = null
            injectorOk = null
            injectorSummary = "not probed yet"
        }
        if (changed) {
            timelineEvent("input helper CONNECTED=${if (ready) "YES" else "NO"} state=$state${if (detail.isEmpty()) "" else " — $detail"}")
        }
    }

    fun isHelperReady(): Boolean = helperReady

    /** One line of helper bring-up history (discover, pair, connect, start, binder). */
    fun timelineEvent(message: String) {
        val line = "${LocalTime.now().format(timeFmt)} $message"
        synchronized(lock) {
            timeline.addLast(line)
            while (timeline.size > MAX_TIMELINE) timeline.removeFirst()
        }
        GlassesDiagnostics.i(TAG, "input: $message")
    }

    fun helperDied(reason: String) {
        helperDeaths++
        helperReady = false
        helperPingOk = false
        timelineEvent("input helper CONNECTED=NO — process DIED ($reason); deaths this session: $helperDeaths")
    }

    fun helperPing(ok: Boolean) {
        helperPingOk = ok
    }

    /** Facts the helper reported about itself: protocol version, uid, pid, sdk. */
    fun helperInfo(info: String) {
        helperInfo = info
    }

    /** The helper's verdict on reaching `InputManager`: [ok] with a one-line [summary]. */
    fun injector(ok: Boolean, summary: String) {
        val changed = injectorOk != ok || injectorSummary != summary
        injectorOk = ok
        injectorSummary = summary
        if (changed) timelineEvent("injector ${if (ok) "OK" else "UNAVAILABLE"}: $summary")
    }

    /**
     * Full text the helper produced for its own report. Lines of the form `STATS key=value ...`
     * are parsed for the per-path stages (mouse devices grabbed, raw key events seen, ...).
     */
    fun helperReport(text: String?) {
        helperReport = text
        helperReportAtMs = System.currentTimeMillis()
        helperStats = parseStats(text)
    }

    internal fun parseStats(text: String?): Map<String, String> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.lineSequence().filter { it.startsWith("STATS ") }.forEach { line ->
            line.removePrefix("STATS ").split(' ').forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) out[kv.substring(0, i)] = kv.substring(i + 1)
            }
        }
        return out
    }

    // ----------------------------------------------------------------------------------------
    // Phone-side facts

    /** What the phone screen is showing: the trackpad, the setup wizard, or the wait-for-glasses page. */
    fun phoneScene(scene: String, trackpadShown: Boolean) {
        if (scene != phoneScene) timelineEvent("phone screen: $scene (trackpad ${if (trackpadShown) "visible" else "HIDDEN"})")
        phoneScene = scene
        trackpadVisible = trackpadShown
    }

    /** Input devices Android currently lists, with the mouse / keyboard names picked out. */
    fun deviceInventory(all: List<String>, mice: List<String>, keyboards: List<String>) {
        val changed = all != devices
        devices = all
        mouseNames = mice
        keyboardNames = keyboards
        devicesScanned = true
        if (changed) {
            timelineEvent("input devices: mouse=${if (mice.isEmpty()) "NONE" else mice.joinToString()} keyboard=${if (keyboards.isEmpty()) "NONE" else keyboards.joinToString()} (${all.size} devices total)")
        }
    }

    /** The app window keystrokes go to: [displayId] < 0 clears it. */
    fun typingTarget(displayId: Int, description: String) {
        if (displayId == typingTargetDisplay && description == typingTarget) return
        typingTargetDisplay = displayId
        typingTarget = if (displayId < 0) "none" else description
        GlassesDiagnostics.i(TAG, "input[keyboard]: typing target -> $typingTarget (display $displayId)")
    }

    fun typingTargetDisplay(): Int = typingTargetDisplay

    /** The workspace cursor moved (NDC, -1..1). Kept as the "last cursor" in the report. */
    fun cursor(x: Float, y: Float) {
        cursorNdc = "(%.3f,%.3f)".format(x, y)
    }

    // ----------------------------------------------------------------------------------------
    // Event counters

    /**
     * Count one [name]d event on [path]; the first few of each name since the test began (and
     * every [always] event) are logged to logcat so a Share log shows the sequence without
     * flooding it with per-move lines.
     */
    fun note(path: Path, name: String, detail: String = "", always: Boolean = false) {
        val now = System.currentTimeMillis()
        val n: Long
        synchronized(lock) {
            val c = state(path).counters.getOrPut(name) { Counter() }
            c.count++
            c.lastAtMs = now
            if (detail.isNotEmpty()) c.lastDetail = detail
            n = c.count
        }
        if (always || n <= LOG_FIRST_N || n % LOG_EVERY_N == 0L) {
            GlassesDiagnostics.i(TAG, "input[${path.tag}]: $name #$n${if (detail.isEmpty()) "" else " $detail"}")
        }
    }

    fun count(path: Path, name: String): Long = synchronized(lock) { state(path).counters[name]?.count ?: 0L }

    fun lastDetail(path: Path, name: String): String = synchronized(lock) { state(path).counters[name]?.lastDetail.orEmpty() }

    /** Start a clean test of [path]: forget its counters and write a marker into the log. */
    fun beginTest(path: Path) {
        synchronized(lock) {
            val s = paths.getValue(path)
            s.counters.clear()
            s.lastHit = null; s.lastHitAtMs = 0L; s.hits = 0; s.appHits = 0; s.slotHits = 0
            s.missReasons.clear(); s.lastInject = null; s.lastInjectOutcome = null
            s.injectOk = 0; s.injectFail.clear(); s.maxRoundTripErr = 0f
            s.testStartedAtMs = System.currentTimeMillis()
        }
        activePath = path
        GlassesDiagnostics.i(TAG, "input: ---- TEST START ${path.tag} ---- helper=${if (helperReady) "CONNECTED" else "NOT CONNECTED ($helperState)"}")
    }

    fun activeTest(): Path? = activePath

    // ----------------------------------------------------------------------------------------
    // Hit-testing

    fun recordHit(hit: HitRecord) {
        lastHitPath = hit.path
        val roundTrip = when {
            hit.roundTripErr.isNaN() -> "n/a"
            hit.roundTripErr > ROUND_TRIP_TOLERANCE -> "%.4f MISMATCH".format(hit.roundTripErr)
            else -> "%.5f ok".format(hit.roundTripErr)
        }
        synchronized(lock) {
            val s = state(hit.path)
            s.lastHit = hit
            s.lastHitAtMs = System.currentTimeMillis()
            s.hits++
            when (hit.route) {
                Route.APP -> s.appHits++
                Route.SLOT_UI -> s.slotHits++
                Route.MISS -> s.missReasons.merge(hit.reason, 1, Int::plus)
            }
            if (!hit.roundTripErr.isNaN() && hit.roundTripErr > s.maxRoundTripErr) s.maxRoundTripErr = hit.roundTripErr
        }
        val line = "input[${hit.path.tag}]: ${hit.kind} -> ${hit.route} ${hit.reason} cursorNdc=${hit.cursorNdc} " +
            "screen=${hit.screenIdx} slotPx=${hit.slotPx}" +
            (if (hit.windowPkg != null) {
                " window=${hit.windowPkg} display=${hit.windowDisplayId} " +
                    "local=(${hit.localX},${hit.localY}) of ${hit.displaySize}"
            } else {
                ""
            }) + " projRoundTrip=$roundTrip ${hit.projection}"
        if (hit.route == Route.MISS || roundTrip.endsWith("MISMATCH")) {
            GlassesDiagnostics.w(TAG, line)
        } else if (hit.kind == "scroll") {
            val now = System.currentTimeMillis()
            if (now - scrollLoggedAtMs >= SCROLL_LOG_INTERVAL_MS) {
                scrollLoggedAtMs = now
                GlassesDiagnostics.i(TAG, line)
            }
        } else {
            GlassesDiagnostics.i(TAG, line)
        }
    }

    // ----------------------------------------------------------------------------------------
    // Injection results

    /**
     * One injection attempt finished. [kind] is "touch", "scroll", "key" or "text"; [what]
     * names the action and coordinates. Touch and scroll results count against the path that
     * produced the most recent hit-test; key and text against the keyboard.
     */
    fun recordInject(kind: String, what: String, outcome: InjectOutcome, path: Path? = null) {
        val target = path ?: if (kind == "key" || kind == "text") Path.KEYBOARD else lastHitPath
        val now = System.currentTimeMillis()
        val summary = "$what -> $outcome"
        synchronized(lock) {
            val s = state(target)
            s.lastInject = "${LocalTime.now().format(timeFmt)} $kind $summary"
            s.lastInjectOutcome = outcome
            if (outcome.ok) s.injectOk++ else s.injectFail.merge(outcome.code, 1, Int::plus)
        }
        if (outcome.ok) {
            if (kind == "scroll") {
                if (now - scrollLoggedAtMs >= SCROLL_LOG_INTERVAL_MS) {
                    scrollLoggedAtMs = now
                    GlassesDiagnostics.i(TAG, "input[${target.tag}]: inject $kind $summary")
                }
            } else {
                GlassesDiagnostics.i(TAG, "input[${target.tag}]: inject $kind $summary")
            }
        } else if (now - lastFailLogAtMs >= FAIL_LOG_INTERVAL_MS || kind == "touch" || kind == "key") {
            lastFailLogAtMs = now
            GlassesDiagnostics.w(TAG, "input[${target.tag}]: inject $kind $summary")
        }
    }

    // ----------------------------------------------------------------------------------------
    // Views

    private fun helperStage(): StageLine =
        if (helperReady) {
            StageLine(
                "0. Input helper connected (needed by every path)", Status.OK,
                "YES — state $helperState${if (helperInfo.isEmpty()) "" else "; $helperInfo"}" +
                    (if (helperDeaths > 0) "; it died $helperDeaths time(s) earlier this session" else "") +
                    (if (helperPingOk == false) "; LAST PING FAILED" else ""),
            )
        } else {
            StageLine(
                "0. Input helper connected (needed by every path)", Status.FAIL,
                "NO — state $helperState${if (helperDetail.isEmpty()) "" else " — $helperDetail"}",
            )
        }

    private fun injectorStages(path: Path, out: MutableList<StageLine>) {
        out += when (injectorOk) {
            true -> StageLine("Helper can reach InputManager", Status.OK, injectorSummary)
            false -> StageLine("Helper can reach InputManager", Status.FAIL, injectorSummary)
            null -> StageLine(
                "Helper can reach InputManager",
                if (helperReady) Status.PENDING else Status.FAIL,
                if (helperReady) injectorSummary else "no helper",
            )
        }
        val (outcome, last) = synchronized(lock) { state(path).lastInjectOutcome to state(path).lastInject }
        out += when {
            outcome == null -> StageLine("Dispatcher accepted the injected event", Status.PENDING, "nothing injected yet for this path")
            outcome.ok -> StageLine("Dispatcher accepted the injected event", Status.OK, last.orEmpty())
            else -> StageLine("Dispatcher accepted the injected event", Status.FAIL, last.orEmpty())
        }
    }

    private fun hitStage(path: Path, what: String): StageLine {
        val hit = synchronized(lock) { state(path).lastHit }
        return when {
            hit == null -> StageLine("$what lands on an app window", Status.PENDING, "no click yet: put the cursor over an app window and click")
            hit.route == Route.APP -> StageLine(
                "$what lands on an app window",
                if (!hit.roundTripErr.isNaN() && hit.roundTripErr > ROUND_TRIP_TOLERANCE) Status.WARN else Status.OK,
                "last ${hit.kind} -> ${hit.windowPkg} display=${hit.windowDisplayId} local=(${hit.localX},${hit.localY}) of ${hit.displaySize}",
            )
            hit.route == Route.SLOT_UI -> StageLine(
                "$what lands on an app window", Status.PENDING,
                "last ${hit.kind} went to the Glashutte desktop UI (${hit.reason}); click inside an app window to test injection",
            )
            else -> StageLine("$what lands on an app window", Status.FAIL, "last ${hit.kind} missed: ${hit.reason}")
        }
    }

    private fun clickChain(path: Path, clickName: String, out: MutableList<StageLine>) {
        val queued = count(path, "clickQueued")
        val processed = count(path, "clickProcessed")
        val noRenderer = count(path, "noRenderer")
        out += when {
            noRenderer > 0 && queued == 0L -> StageLine(
                "Click reaches the workspace renderer", Status.FAIL,
                "$noRenderer input(s) dropped: no workspace renderer is attached (glasses workspace not running)",
            )
            queued == 0L -> StageLine("Click reaches the workspace renderer", Status.PENDING, "no $clickName yet")
            processed == 0L -> StageLine(
                "Click reaches the workspace renderer", Status.FAIL,
                "$queued click(s) queued but the GL thread never processed one (renderer paused?)",
            )
            else -> StageLine("Click reaches the workspace renderer", Status.OK, "queued=$queued processed=$processed")
        }
        out += hitStage(path, "Click")
    }

    fun stages(path: Path): List<StageLine> {
        val out = ArrayList<StageLine>()
        out += helperStage()
        when (path) {
            Path.TRACKPAD -> {
                out += when {
                    trackpadVisible == null -> StageLine("Phone shows the trackpad", Status.PENDING, phoneScene)
                    trackpadVisible == true -> StageLine("Phone shows the trackpad", Status.OK, phoneScene)
                    else -> StageLine("Phone shows the trackpad", Status.FAIL, "the trackpad is HIDDEN: $phoneScene")
                }
                val touches = count(path, "touch")
                out += if (touches > 0) {
                    StageLine("Touches reach the trackpad view", Status.OK, "touches=$touches last: ${lastDetail(path, "touch")}")
                } else {
                    StageLine("Touches reach the trackpad view", Status.PENDING, "no touch yet: drag a finger on the phone screen")
                }
                val moves = count(path, "move")
                val taps = count(path, "tap")
                val twoFinger = count(path, "twoFingerDrag")
                out += when {
                    touches == 0L -> StageLine("Gestures are recognised", Status.PENDING, "no touches yet")
                    moves + taps + twoFinger == 0L -> StageLine(
                        "Gestures are recognised", Status.WARN,
                        "$touches touches but no move / tap / scroll gesture came out of them",
                    )
                    else -> StageLine("Gestures are recognised", Status.OK, "moves=$moves taps=$taps twoFingerDrags=$twoFinger")
                }
                val cursorMoves = count(path, "cursor")
                val noRenderer = count(path, "noRenderer")
                out += when {
                    cursorMoves > 0 -> StageLine("Workspace cursor moves", Status.OK, "cursor events=$cursorMoves last ndc=$cursorNdc")
                    noRenderer > 0 -> StageLine("Workspace cursor moves", Status.FAIL, "$noRenderer gesture(s) dropped: no workspace renderer attached")
                    else -> StageLine("Workspace cursor moves", Status.PENDING, "no cursor movement yet")
                }
                clickChain(path, "tap", out)
            }
            Path.MOUSE -> {
                out += when {
                    !devicesScanned -> StageLine("Android sees a mouse", Status.PENDING, "device list not scanned yet")
                    mouseNames.isNotEmpty() -> StageLine("Android sees a mouse", Status.OK, mouseNames.joinToString())
                    else -> StageLine(
                        "Android sees a mouse", Status.FAIL,
                        "no pointer device in Android's input list: connect the mouse in Bluetooth settings first",
                    )
                }
                val monitor = helperStats["monitor"]
                val grabbed = helperStats["grabbed"]?.toIntOrNull() ?: 0
                val opened = helperStats["opened"]?.toIntOrNull() ?: -1
                val relSeen = helperStats["rel"]?.toLongOrNull() ?: 0L
                out += when {
                    !helperReady -> StageLine("Helper is reading the mouse", Status.FAIL, "no helper: the mouse is not read at all")
                    monitor == null -> StageLine("Helper is reading the mouse", Status.PENDING, "helper has not reported yet (open Diagnostics to fetch)")
                    monitor == "off" -> StageLine("Helper is reading the mouse", Status.FAIL, "helper's input monitor is OFF (Glashutte did not detect a mouse or keyboard)")
                    opened == 0 -> StageLine("Helper is reading the mouse", Status.FAIL, "helper could not open any /dev/input node: ${helperStats["skipped"].orEmpty()}")
                    grabbed == 0 && relSeen == 0L -> StageLine(
                        "Helper is reading the mouse", Status.WARN,
                        "monitor on, $opened node(s) open, but no pointer motion seen yet: move the mouse",
                    )
                    else -> StageLine("Helper is reading the mouse", Status.OK, "opened=$opened grabbed=$grabbed relEvents=$relSeen")
                }
                val evdev = count(path, "evdevDelta") + count(path, "evdevButton")
                val fallback = count(path, "activityDelta") + count(path, "activityButton")
                out += when {
                    evdev > 0 -> StageLine("Mouse events reach Glashutte", Status.OK, "from helper: deltas=${count(path, "evdevDelta")} buttons=${count(path, "evdevButton")} wheel=${count(path, "wheel")}")
                    fallback > 0 -> StageLine(
                        "Mouse events reach Glashutte", Status.WARN,
                        "only via the phone window fallback (deltas=${count(path, "activityDelta")} buttons=${count(path, "activityButton")}); the helper's raw path delivered nothing",
                    )
                    else -> StageLine("Mouse events reach Glashutte", Status.PENDING, "no mouse event yet: move the mouse and click")
                }
                val cursorMoves = count(path, "cursor")
                val noRenderer = count(path, "noRenderer")
                out += when {
                    cursorMoves > 0 -> StageLine("Workspace cursor moves", Status.OK, "cursor events=$cursorMoves last ndc=$cursorNdc")
                    noRenderer > 0 -> StageLine("Workspace cursor moves", Status.FAIL, "$noRenderer mouse event(s) dropped: no workspace renderer attached")
                    else -> StageLine("Workspace cursor moves", Status.PENDING, "no cursor movement yet")
                }
                clickChain(path, "mouse click", out)
            }
            Path.KEYBOARD -> {
                out += when {
                    !devicesScanned -> StageLine("Android sees a keyboard", Status.PENDING, "device list not scanned yet")
                    keyboardNames.isNotEmpty() -> StageLine("Android sees a keyboard", Status.OK, keyboardNames.joinToString())
                    else -> StageLine(
                        "Android sees a keyboard", Status.FAIL,
                        "no physical keyboard in Android's input list: connect it in Bluetooth settings first",
                    )
                }
                val down = count(path, "keyDown")
                val helperKeys = helperStats["keys"]?.toLongOrNull() ?: 0L
                out += when {
                    down > 0 -> StageLine("Key presses reach Glashutte's phone window", Status.OK, "keyDown=$down last: ${lastDetail(path, "keyDown")}")
                    helperKeys > 0 -> StageLine(
                        "Key presses reach Glashutte's phone window", Status.WARN,
                        "the helper saw $helperKeys raw key event(s) but Glashutte's window got none: is Glashutte in front with the phone screen on?",
                    )
                    else -> StageLine("Key presses reach Glashutte's phone window", Status.PENDING, "no key yet: keep Glashutte in front on the phone and type")
                }
                out += if (typingTargetDisplay >= 0) {
                    StageLine("An app window is the typing target", Status.OK, "$typingTarget (display $typingTargetDisplay)")
                } else {
                    StageLine(
                        "An app window is the typing target", if (down > 0) Status.FAIL else Status.PENDING,
                        "none: click inside an app window with the trackpad or mouse first",
                    )
                }
                val forwarded = count(path, "forwarded")
                val skipped = count(path, "skipped")
                out += when {
                    forwarded > 0 -> StageLine("Keys are forwarded to the app", Status.OK, "forwarded=$forwarded skipped=$skipped")
                    down == 0L -> StageLine("Keys are forwarded to the app", Status.PENDING, "no keys yet")
                    else -> StageLine("Keys are forwarded to the app", Status.FAIL, "keys arrived but none were forwarded; last reason: ${lastDetail(path, "skipped").ifEmpty { "unknown" }}")
                }
            }
        }
        injectorStages(path, out)
        return out
    }

    /** One-line verdict for [path]: the first stage that is not OK. */
    fun verdict(path: Path): String {
        val lines = stages(path)
        val bad = lines.firstOrNull { it.status == Status.FAIL || it.status == Status.WARN }
        if (bad != null) return "${bad.title}: ${bad.status} — ${bad.detail}"
        val pending = lines.firstOrNull { it.status == Status.PENDING }
        return if (pending != null) "waiting at '${pending.title}' — ${pending.detail}" else "OK: input reached the dispatcher"
    }

    /** Overall verdict: helper first, then the first path that shows a failure. */
    fun verdict(): String {
        if (!helperReady) return "HELPER NOT CONNECTED (state $helperState${if (helperDetail.isEmpty()) "" else " — $helperDetail"}): nothing can be injected into apps"
        for (p in Path.values()) {
            val bad = stages(p).firstOrNull { it.status == Status.FAIL || it.status == Status.WARN }
            if (bad != null) return "${p.title}: ${bad.title}: ${bad.status} — ${bad.detail}"
        }
        return "helper connected; no failure recorded yet (run the trackpad, mouse and keyboard tests)"
    }

    /** Short line for the glasses overlay and the log heartbeat. */
    fun oneLine(): String = "helper=${if (helperReady) "CONNECTED" else "NOT CONNECTED ($helperState)"} | ${verdict()}"

    private fun stageText(lines: List<StageLine>, sb: StringBuilder) {
        lines.forEach { s ->
            val mark = when (s.status) {
                Status.OK -> "[ OK ]"
                Status.FAIL -> "[FAIL]"
                Status.WARN -> "[WARN]"
                Status.PENDING -> "[ .. ]"
            }
            sb.append(mark).append(' ').append(s.title).append('\n')
            sb.append("        ").append(s.detail).append('\n')
        }
    }

    private fun pathSection(path: Path, sb: StringBuilder) {
        sb.append("== PATH: ${path.title} ==\n")
        sb.append("PATH VERDICT: ").append(verdict(path)).append('\n')
        stageText(stages(path), sb)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = state(path)
            if (s.testStartedAtMs != 0L) sb.append("test started ${(now - s.testStartedAtMs) / 1000}s ago\n")
            sb.append("counters: ")
            if (s.counters.isEmpty()) sb.append("(none)") else sb.append(s.counters.entries.joinToString { "${it.key}=${it.value.count}" })
            sb.append('\n')
            val hit = s.lastHit
            if (hit != null) {
                sb.append("last hit-test (${(now - s.lastHitAtMs) / 1000}s ago): ${hit.kind} route=${hit.route} ${hit.reason}\n")
                sb.append("  cursorNdc=${hit.cursorNdc} screen=${hit.screenIdx} slotPx=${hit.slotPx}\n")
                if (hit.windowPkg != null) {
                    sb.append("  window=${hit.windowPkg} display=${hit.windowDisplayId} injected local=(${hit.localX},${hit.localY}) of ${hit.displaySize}\n")
                }
                sb.append("  projection round-trip error=")
                    .append(if (hit.roundTripErr.isNaN()) "n/a (curved/yawed screen)" else "%.5f NDC".format(hit.roundTripErr))
                    .append(" (tolerance $ROUND_TRIP_TOLERANCE); ${hit.projection}\n")
            }
            sb.append("clicks/scrolls seen=${s.hits} app=${s.appHits} desktopUi=${s.slotHits} missed=${s.missReasons.values.sum()}")
            if (s.missReasons.isNotEmpty()) sb.append(' ').append(s.missReasons)
            sb.append('\n')
            sb.append("injections ok=${s.injectOk} failed=${s.injectFail.values.sum()}")
            if (s.injectFail.isNotEmpty()) {
                sb.append(' ').append(s.injectFail.entries.joinToString(prefix = "{", postfix = "}") { "${InjectOutcome.describe(it.key).take(40)}=${it.value}" })
            }
            sb.append(" worstProjRoundTrip=%.5f\n".format(s.maxRoundTripErr))
        }
    }

    private fun commonSection(sb: StringBuilder, includeTimeline: Boolean) {
        val now = System.currentTimeMillis()
        sb.append("-- Helper and devices --\n")
        sb.append("helper: state=$helperState ready=$helperReady")
        if (helperReady && helperReadySinceMs != 0L) sb.append(" for ${(now - helperReadySinceMs) / 1000}s")
        sb.append(" deaths=$helperDeaths\n")
        if (helperDetail.isNotEmpty()) sb.append("helper detail: ").append(helperDetail).append('\n')
        if (helperInfo.isNotEmpty()) sb.append("helper info: ").append(helperInfo).append('\n')
        sb.append("phone screen: ").append(phoneScene).append('\n')
        sb.append("workspace cursor: ").append(cursorNdc).append('\n')
        sb.append("android input devices:\n")
        if (devices.isEmpty()) sb.append("  (not scanned yet)\n") else devices.forEach { sb.append("  ").append(it).append('\n') }
        if (includeTimeline) {
            sb.append("helper timeline (most recent last):\n")
            synchronized(lock) {
                if (timeline.isEmpty()) sb.append("  (nothing yet)\n") else timeline.forEach { sb.append("  ").append(it).append('\n') }
            }
        }
        val remote = helperReport
        sb.append("helper self-report")
        if (remote != null) sb.append(" (${(now - helperReportAtMs) / 1000}s ago)")
        sb.append(":\n")
        sb.append(remote?.prependIndent("  ") ?: "  (not fetched; open the Diagnostics screen with the helper connected)")
        sb.append('\n')
    }

    /** `HELPER CONNECTED: YES|NO (...)` — the first line of every report. */
    fun helperLine(): String =
        "HELPER CONNECTED: " +
            if (helperReady) "YES" else "NO (state $helperState${if (helperDetail.isEmpty()) "" else " — $helperDetail"})"

    private fun header(sb: StringBuilder) {
        sb.append(helperLine()).append('\n')
    }

    /** The whole input section: helper first, then each of the three paths, then helper detail. */
    fun report(includeTimeline: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("-- Input paths (phone trackpad / Bluetooth mouse / Bluetooth keyboard into apps) --\n")
        header(sb)
        sb.append("INPUT VERDICT: ").append(verdict()).append('\n')
        Path.values().forEach { sb.append('\n'); pathSection(it, sb) }
        sb.append('\n')
        commonSection(sb, includeTimeline)
        return sb.toString()
    }

    /** Just the helper facts and one path, for "send me the log for the mouse test". */
    fun pathReport(path: Path): String {
        val sb = StringBuilder()
        sb.append("-- Input test: ${path.title} --\n")
        header(sb)
        sb.append('\n')
        pathSection(path, sb)
        sb.append('\n')
        commonSection(sb, includeTimeline = true)
        return sb.toString()
    }

    /** Test hook: forget everything. */
    fun reset() {
        helperState = "not initialised"; helperDetail = ""; helperReady = false; helperReadySinceMs = 0L
        helperPingOk = null; helperDeaths = 0; helperInfo = ""; injectorOk = null
        injectorSummary = "not probed yet"; helperReport = null; helperReportAtMs = 0L; helperStats = emptyMap()
        phoneScene = "unknown (the phone screen has not been shown yet)"; trackpadVisible = null
        devices = emptyList(); mouseNames = emptyList(); keyboardNames = emptyList(); devicesScanned = false
        typingTarget = "none"; typingTargetDisplay = -1; lastHitPath = Path.TRACKPAD
        cursorNdc = "(unknown)"; activePath = null; scrollLoggedAtMs = 0L; lastFailLogAtMs = 0L
        synchronized(lock) {
            timeline.clear()
            paths.keys.forEach { paths[it] = PathState() }
        }
    }

    /** Max NDC distance between cursor and re-projected hit point before a hit is flagged. */
    const val ROUND_TRIP_TOLERANCE = 0.01f

    private const val MAX_TIMELINE = 80
    private const val SCROLL_LOG_INTERVAL_MS = 2_000L
    private const val FAIL_LOG_INTERVAL_MS = 1_000L
    private const val LOG_FIRST_N = 3
    private const val LOG_EVERY_N = 50L
}
