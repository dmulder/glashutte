package io.github.dmulder.glashutte.handoff

import android.content.Context
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Log for the flat-launch path. Lines go to logcat and the diagnostics ring under
 * `GlashutteDiag` like everything else, and also to a small file that Share log includes, so a
 * busy ring buffer or a process restart cannot lose the evidence of a failed handoff.
 */
object FlatLog {
    private const val FILE = "flat-handoff.log"
    private const val MAX_BYTES = 160_000
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
    }

    /** [tag] is one of FLAT-ROUTE, DRM-PROBE, NATIVE-HANDOFF, FLAT-FIT. */
    fun i(tag: String, message: String) {
        GlassesDiagnostics.i(GlassesDiagnostics.TAG, "$tag: $message")
        write("${LocalTime.now().format(fmt)} $tag: $message")
    }

    /** A multi-line dump, logged one line per entry so logcat does not truncate it. */
    fun block(tag: String, title: String, text: String, maxLines: Int = 80) {
        i(tag, "$title (${text.lineSequence().count()} lines)")
        text.lineSequence().filter { it.isNotBlank() }.take(maxLines).forEach { i(tag, "  $it") }
    }

    fun clear() {
        synchronized(lock) { runCatching { file?.delete() } }
    }

    fun read(): String = synchronized(lock) {
        runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull()?.ifBlank { null } ?: "(no flat-launch log yet)"
    }

    private fun write(line: String) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val keep = f.readText().takeLast(MAX_BYTES / 2)
                    f.writeText(keep.substringAfter('\n'))
                }
                f.appendText(line + "\n")
            }
        }
    }
}
