package io.github.dmulder.glashutte.glasses

/**
 * Result of one input injection attempt, as reported by the privileged helper (or synthesised
 * on the app side when the helper could not even be reached).
 *
 * Travels over the helper's AIDL as a single tab-separated string, `"<code>\t<strategy>\t<detail>"`,
 * so the helper interface needs no new parcelable.
 */
data class InjectOutcome(val code: Int, val strategy: String, val detail: String) {

    val ok: Boolean get() = code == OK

    fun encode(): String = "$code\t$strategy\t${detail.replace('\t', ' ').replace('\n', ' ')}"

    override fun toString(): String =
        "${describe(code)}${if (strategy.isEmpty()) "" else " via $strategy"}" +
            if (detail.isEmpty()) "" else " ($detail)"

    companion object {
        /** The dispatcher accepted the event (with the "wait for result" mode this means it found a target). */
        const val OK = 0

        /** No way to reach `InputManager` could be resolved in the helper. */
        const val NO_INJECTOR = 1

        /** `injectInputEvent` returned false: the dispatcher refused or found no touchable window. */
        const val REJECTED = 2

        /** A reflective call threw. */
        const val EXCEPTION = 3

        /** The helper was asked for something it cannot do (unknown action, bad display). */
        const val BAD_ARGS = 4

        /** App side: there is no helper binder to call. */
        const val NO_HELPER = 5

        /** App side: the AIDL call itself failed (helper died, version mismatch). */
        const val HELPER_CALL_FAILED = 6

        fun describe(code: Int): String = when (code) {
            OK -> "OK"
            NO_INJECTOR -> "FAIL no InputManager reachable from the helper"
            REJECTED -> "FAIL dispatcher rejected the event (no touchable window at that point on that display?)"
            EXCEPTION -> "FAIL exception in the helper"
            BAD_ARGS -> "FAIL bad arguments"
            NO_HELPER -> "FAIL helper not connected"
            HELPER_CALL_FAILED -> "FAIL call to the helper failed"
            else -> "FAIL code $code"
        }

        fun parse(encoded: String?): InjectOutcome {
            if (encoded.isNullOrEmpty()) return InjectOutcome(HELPER_CALL_FAILED, "", "empty reply from helper")
            val parts = encoded.split('\t', limit = 3)
            val code = parts.getOrNull(0)?.trim()?.toIntOrNull()
                ?: return InjectOutcome(HELPER_CALL_FAILED, "", "unparseable reply: ${encoded.take(80)}")
            return InjectOutcome(code, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        }

        fun ok(strategy: String, detail: String = "") = InjectOutcome(OK, strategy, detail)
    }
}
