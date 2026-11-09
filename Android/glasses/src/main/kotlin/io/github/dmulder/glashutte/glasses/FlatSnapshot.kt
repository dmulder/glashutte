package io.github.dmulder.glashutte.glasses

/**
 * Reads `dumpsys activity activities` and `dumpsys window windows` to answer, for one app and
 * one display: is the app's task on that display, and does it have a visible window there?
 *
 * Pure text parsing so it is unit-tested; the shell helper feeds it the dumps and sends back
 * [Result.encode]. Used to verify a flat launch really landed on the glasses, to decide
 * whether to fall back, and to leave evidence in the log of what is (and is not) on the display.
 */
object FlatSnapshot {

    enum class Verdict {
        /** App has a visible window on the target display. */
        VISIBLE_ON_DISPLAY,

        /** App's activity is on the target display but no visible window was found (yet). */
        ON_DISPLAY_NOT_VISIBLE,

        /** App's activity exists but on other display(s) only: the task bounced or never moved. */
        ELSEWHERE,

        /** No activity of the app exists anywhere: it crashed, finished or never started. */
        NOT_RUNNING,

        /** App is on the target display but the window dump had nothing parseable to confirm it. */
        ON_DISPLAY_UNVERIFIED,
    }

    data class Window(
        val title: String,
        val displayId: Int?,
        val visible: Boolean,
        val hasSurface: Boolean?,
        val secure: Boolean,
        val alpha: String?,
    )

    class Result(
        val packageName: String,
        val displayId: Int,
        val verdict: Verdict,
        /** Displays on which the app has an ActivityRecord. */
        val activityDisplays: Set<Int>,
        /** Every window on the target display (any owner), so a covering window shows up. */
        val windowsOnTarget: List<Window>,
        val appWindows: List<Window>,
        val windowDumpParsed: Boolean,
        val excerpt: String,
    ) {
        val passes: Boolean
            get() = verdict == Verdict.VISIBLE_ON_DISPLAY || verdict == Verdict.ON_DISPLAY_UNVERIFIED

        val appWindowSecure: Boolean get() = appWindows.any { it.secure && it.displayId == displayId }

        fun header(): String =
            "SNAP verdict=${verdict.name} display=$displayId pkg=$packageName activityDisplays=${activityDisplays.sorted().joinToString(",")} " +
                "appWindows=${appWindows.size} appVisible=${appWindows.count { it.visible && it.displayId == displayId }} " +
                "appSecure=${if (appWindowSecure) 1 else 0} windowsOnDisplay=${windowsOnTarget.size} parsed=$windowDumpParsed"

        fun encode(): String = header() + "\n" + excerpt
    }

    /** Verdict parsed back out of [Result.encode]'s first line; `null` for anything else. */
    fun decodeVerdict(encoded: String): Verdict? {
        val line = encoded.lineSequence().firstOrNull { it.startsWith("SNAP ") } ?: return null
        val name = Regex("""verdict=(\w+)""").find(line)?.groupValues?.get(1) ?: return null
        return Verdict.values().firstOrNull { it.name == name }
    }

    fun passes(verdict: Verdict?): Boolean =
        verdict == Verdict.VISIBLE_ON_DISPLAY || verdict == Verdict.ON_DISPLAY_UNVERIFIED

    fun analyze(activityDump: String?, windowDump: String?, displayId: Int, packageName: String): Result {
        val activityDisplays = activityDisplays(activityDump.orEmpty(), packageName)
        val windows = parseWindows(windowDump.orEmpty())
        val onTarget = windows.filter { it.displayId == displayId }
        val app = windows.filter { it.title.contains("$packageName/") || it.title.contains("$packageName}") || it.title.contains(" $packageName ") }
        val appOnTarget = app.filter { it.displayId == displayId }
        val verdict = when {
            displayId in activityDisplays || appOnTarget.isNotEmpty() -> when {
                appOnTarget.any { it.visible } -> Verdict.VISIBLE_ON_DISPLAY
                windows.isEmpty() -> Verdict.ON_DISPLAY_UNVERIFIED
                else -> Verdict.ON_DISPLAY_NOT_VISIBLE
            }
            activityDisplays.isNotEmpty() || app.isNotEmpty() -> Verdict.ELSEWHERE
            else -> Verdict.NOT_RUNNING
        }
        val sb = StringBuilder()
        sb.append("app windows (any display):\n")
        if (app.isEmpty()) sb.append("  (none)\n") else app.forEach { sb.append("  ").append(describe(it)).append('\n') }
        sb.append("all windows on display ").append(displayId).append(":\n")
        if (onTarget.isEmpty()) sb.append("  (none)\n") else onTarget.take(12).forEach { sb.append("  ").append(describe(it)).append('\n') }
        sb.append("activity records for the app, by display: ")
            .append(if (activityDisplays.isEmpty()) "(none)" else activityDisplays.sorted().joinToString(", ") { "display $it" }).append('\n')
        sb.append("task/activity lines on display ").append(displayId).append(":\n")
        sb.append(displaySection(activityDump.orEmpty(), displayId, 24).ifEmpty { "  (no such display section in the dump)" }).append('\n')
        return Result(packageName, displayId, verdict, activityDisplays, onTarget, app, windows.isNotEmpty(), sb.toString())
    }

    private fun describe(w: Window) =
        "${w.title} display=${w.displayId} visible=${w.visible} surface=${w.hasSurface} secure=${w.secure}" +
            (w.alpha?.let { " alpha=$it" } ?: "")

    private val DISPLAY_HEADER = Regex("""^Display #(\d+)""")

    internal fun activityDisplays(dump: String, packageName: String): Set<Int> {
        val out = LinkedHashSet<Int>()
        var current = -1
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            DISPLAY_HEADER.find(line)?.let { current = it.groupValues[1].toInt(); return@let }
            if (current >= 0 && line.contains("ActivityRecord{") && line.contains("$packageName/")) out += current
        }
        return out
    }

    internal fun displaySection(dump: String, displayId: Int, maxLines: Int): String {
        val out = ArrayList<String>()
        var inDisplay = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val m = DISPLAY_HEADER.find(line)
            if (m != null) {
                inDisplay = m.groupValues[1].toInt() == displayId
                if (inDisplay) out += "  $line"
                continue
            }
            if (inDisplay && line.isNotEmpty() && (
                    line.contains("ActivityRecord{") || line.contains("Task{") || line.contains("mBounds") ||
                        line.contains("mResumedActivity") || line.contains("windowingMode") || line.startsWith("* ")
                    )
            ) {
                out += "    $line"
            }
            if (out.size >= maxLines) break
        }
        return out.joinToString("\n")
    }

    private val WINDOW_HEADER = Regex("""^Window #\d+ (Window\{[^}]*\}):?""")

    internal fun parseWindows(dump: String): List<Window> {
        val windows = ArrayList<Window>()
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            val t = title ?: return
            windows += toWindow(t, body.toString())
            body.setLength(0)
        }
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val m = WINDOW_HEADER.find(line)
            if (m != null) {
                flush()
                title = m.groupValues[1]
            } else if (title != null) {
                if (line.startsWith("Window #")) { flush(); title = null } else body.append(line).append('\n')
            }
        }
        flush()
        return windows
    }

    private fun toWindow(title: String, body: String): Window {
        val displayId = Regex("""mDisplayId=(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
        val ready = Regex("""isReadyForDisplay\(\)=(true|false)""").find(body)?.groupValues?.get(1)
        val shown = Regex("""shown=(true|false)""").find(body)?.groupValues?.get(1)
        val isVisible = Regex("""\bisVisible=(true|false)""").find(body)?.groupValues?.get(1)
        val hasSurface = Regex("""mHasSurface=(true|false)""").find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        val visible = ready == "true" || shown == "true" || isVisible == "true"
        val flags = Regex("""\bfl=([^\n}]*)""").find(body)?.groupValues?.get(1).orEmpty()
        val flagTokens = flags.substringBefore(" pfl=").substringBefore(" apr=").split(' ')
        val secure = flagTokens.any { it == "SECURE" || it == "FLAG_SECURE" }
        val alpha = Regex("""\balpha=([0-9.]+)""").find(body)?.groupValues?.get(1)
        return Window(title, displayId, visible, hasSurface, secure, alpha)
    }
}
