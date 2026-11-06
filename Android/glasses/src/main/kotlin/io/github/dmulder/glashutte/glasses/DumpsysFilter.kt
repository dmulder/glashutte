package io.github.dmulder.glashutte.glasses

/**
 * Cuts the part of a `dumpsys input` / `dumpsys window displays` / `dumpsys display` dump
 * that concerns one display, so the diagnostics report can show "which windows can the input
 * dispatcher actually see on display N, and which one is focused" without pasting hundreds of
 * kilobytes. Deliberately format-tolerant: it keys on the words that have been stable across
 * Android releases (`Display: N`, `Display #N`, `displayId=N`, `Focused...`) and never fails.
 */
object DumpsysFilter {

    private val displayHeading = Regex("""^\s*Display(?::|\s*#)\s*(?:mDisplayId=)?(\d+)\b""")
    private val focusHeading = Regex("""^\s*(FocusedDisplayId|DefaultDisplayId|FocusedApplications|FocusedWindows|mCurrentFocus|mFocusedApp|mTopFocusedDisplayId)\b""")

    fun forDisplay(dump: String?, displayId: Int, maxLines: Int = 80): String {
        if (dump.isNullOrBlank()) return "(no dump)"
        val lines = dump.lines()
        val out = ArrayList<String>()
        val seen = HashSet<Int>()
        val idPattern = Regex("""(?i)displayid=$displayId\b""")

        fun add(index: Int) {
            if (seen.add(index)) out.add(lines[index].trimEnd())
        }

        fun indentOf(s: String) = s.length - s.trimStart().length

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val heading = displayHeading.find(line)
            if (heading != null) {
                if (heading.groupValues[1].toIntOrNull() == displayId) {
                    val base = indentOf(line)
                    add(i)
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || indentOf(lines[j]) > base)) {
                        if (lines[j].isNotBlank()) add(j)
                        j++
                    }
                    i = j
                    continue
                }
            } else if (focusHeading.containsMatchIn(line)) {
                val base = indentOf(line)
                add(i)
                var j = i + 1
                var taken = 0
                while (j < lines.size && taken < FOCUS_CHILD_LINES && indentOf(lines[j]) > base && lines[j].isNotBlank()) {
                    add(j)
                    taken++
                    j++
                }
            } else if (idPattern.containsMatchIn(line)) {
                add(i)
            }
            i++
        }
        if (out.isEmpty()) return "(nothing mentions display $displayId)"
        if (out.size > maxLines) {
            val extra = out.size - maxLines
            return out.take(maxLines).joinToString("\n") + "\n... ($extra more lines)"
        }
        return out.joinToString("\n")
    }

    private const val FOCUS_CHILD_LINES = 8
}
