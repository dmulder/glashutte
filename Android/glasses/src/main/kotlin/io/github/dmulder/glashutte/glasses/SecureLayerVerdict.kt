package io.github.dmulder.glashutte.glasses

/**
 * Interprets the secure-layer self test: the app puts one ordinary window and one
 * FLAG_SECURE window on a display, reads back the pixels it is allowed to read (virtual
 * display) and compares them. SurfaceFlinger blanks a secure window on a display that is not
 * secure, which is exactly what blanks protected video in the workspace.
 */
object SecureLayerVerdict {

    enum class Outcome { SECURE_VISIBLE, SECURE_BLANKED, NORMAL_MISSING, INCONCLUSIVE }

    /** Both colours are ARGB ints as read from the display's frame; alpha is ignored. */
    fun classify(normalRgb: Int, secureRgb: Int, expectedNormal: Int, expectedSecure: Int): Outcome {
        val normalOk = near(normalRgb, expectedNormal)
        if (!normalOk) return if (isBlack(normalRgb)) Outcome.NORMAL_MISSING else Outcome.INCONCLUSIVE
        return when {
            near(secureRgb, expectedSecure) -> Outcome.SECURE_VISIBLE
            isBlack(secureRgb) -> Outcome.SECURE_BLANKED
            else -> Outcome.INCONCLUSIVE
        }
    }

    fun describe(outcome: Outcome): String = when (outcome) {
        Outcome.SECURE_VISIBLE ->
            "secure window was composed: this display accepts secure layers"
        Outcome.SECURE_BLANKED ->
            "secure window came out black while the normal one was fine: this display blanks " +
                "secure layers (protected video would show no picture here)"
        Outcome.NORMAL_MISSING ->
            "even the normal window was black: the test did not render, so nothing can be concluded"
        Outcome.INCONCLUSIVE ->
            "pixels were neither the expected colours nor black: nothing can be concluded"
    }

    private fun channel(argb: Int, shift: Int) = (argb shr shift) and 0xFF

    private fun isBlack(argb: Int) =
        channel(argb, 16) < 24 && channel(argb, 8) < 24 && channel(argb, 0) < 24

    private fun near(a: Int, b: Int, tolerance: Int = 40) =
        kotlin.math.abs(channel(a, 16) - channel(b, 16)) <= tolerance &&
            kotlin.math.abs(channel(a, 8) - channel(b, 8)) <= tolerance &&
            kotlin.math.abs(channel(a, 0) - channel(b, 0)) <= tolerance
}
