package io.github.dmulder.glashutte.glasses

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure checks that a flat (native-handoff) app is actually shown at the glasses panel's own
 * shape and a readable size, rather than the stretched / letterboxed / tiny result of plain
 * screen mirroring. Everything here is arithmetic on numbers read from the display, so it
 * is unit-tested and also drives the diagnostics lines.
 */
object FlatFit {

    /** Luma Ultra panel: 1920x1200 (16:10). */
    const val PANEL_WIDTH = 1920
    const val PANEL_HEIGHT = 1200
    const val PANEL_ASPECT = PANEL_WIDTH.toFloat() / PANEL_HEIGHT

    /** Density used for apps on the glasses' display unless the user changes it. */
    const val DEFAULT_DENSITY_DPI = 240
    const val MIN_DENSITY_DPI = 160
    const val MAX_DENSITY_DPI = 360

    /** Below this many dp of height the UI is a phone-sized column, above it tablet-class. */
    private const val MIN_READABLE_DP_HEIGHT = 480

    /** Icons/text smaller than this fraction of a phone's (420 dpi) size are "tiny". */
    private const val TINY_DENSITY_DPI = 130

    private const val ASPECT_TOLERANCE = 0.02f

    enum class Verdict { OK, WARN, FAIL }

    data class Check(val name: String, val verdict: Verdict, val detail: String) {
        fun line() = "[${verdict.name}] $name: $detail"
    }

    fun aspect(width: Int, height: Int): Float = if (height <= 0) 0f else width.toFloat() / height

    fun isSixteenTen(width: Int, height: Int): Boolean =
        abs(aspect(width, height) - PANEL_ASPECT) <= ASPECT_TOLERANCE * PANEL_ASPECT

    fun clampDensity(dpi: Int): Int = dpi.coerceIn(MIN_DENSITY_DPI, MAX_DENSITY_DPI)

    fun dpSize(px: Int, densityDpi: Int): Int = if (densityDpi <= 0) 0 else (px * 160f / densityDpi).roundToInt()

    /**
     * Where an app of aspect [contentAspect] lands inside a [displayW] x [displayH] display when
     * the platform letterboxes it: the rectangle (left, top, width, height) it occupies.
     */
    fun letterbox(displayW: Int, displayH: Int, contentAspect: Float): IntArray {
        if (displayW <= 0 || displayH <= 0 || contentAspect <= 0f) return intArrayOf(0, 0, displayW, displayH)
        val displayAspect = displayW.toFloat() / displayH
        return if (contentAspect > displayAspect) {
            val h = (displayW / contentAspect).roundToInt()
            intArrayOf(0, (displayH - h) / 2, displayW, h)
        } else {
            val w = (displayH * contentAspect).roundToInt()
            intArrayOf((displayW - w) / 2, 0, w, displayH)
        }
    }

    /**
     * Judge a display the app runs on. [boundsW] x [boundsH] is the activity's window bounds if
     * known (from the window dump), `0` when unknown.
     */
    fun analyze(
        displayW: Int,
        displayH: Int,
        densityDpi: Int,
        boundsW: Int = 0,
        boundsH: Int = 0,
    ): List<Check> {
        val out = ArrayList<Check>()
        val a = aspect(displayW, displayH)
        out += when {
            displayW <= 0 || displayH <= 0 ->
                Check("display shape", Verdict.FAIL, "no size known")
            isSixteenTen(displayW, displayH) ->
                Check("display shape", Verdict.OK, "${displayW}x$displayH is the panel's native 16:10 (aspect %.3f)".format(a))
            a < 1f ->
                Check(
                    "display shape", Verdict.FAIL,
                    "${displayW}x$displayH is portrait (aspect %.3f): this is the phone screen, not the glasses panel".format(a),
                )
            else ->
                Check(
                    "display shape", Verdict.WARN,
                    "${displayW}x$displayH (aspect %.3f) is not the panel's 16:10; expect letterboxing or stretching".format(a),
                )
        }
        val dpH = dpSize(displayH, densityDpi)
        out += when {
            densityDpi <= 0 -> Check("UI size", Verdict.FAIL, "density unknown")
            densityDpi < TINY_DENSITY_DPI ->
                Check("UI size", Verdict.FAIL, "density $densityDpi dpi makes icons and text tiny (${dpSize(displayW, densityDpi)}x${dpH}dp); raise it")
            dpH < MIN_READABLE_DP_HEIGHT ->
                Check("UI size", Verdict.WARN, "density $densityDpi dpi gives only ${dpH}dp of height: UI will be oversized and cramped")
            else ->
                Check("UI size", Verdict.OK, "density $densityDpi dpi -> ${dpSize(displayW, densityDpi)}x${dpH}dp (readable)")
        }
        if (boundsW > 0 && boundsH > 0) {
            val fills = boundsW >= displayW * 0.98f && boundsH >= displayH * 0.98f
            out += if (fills) {
                Check("window fills display", Verdict.OK, "app window ${boundsW}x$boundsH covers the ${displayW}x$displayH display")
            } else {
                val box = letterbox(displayW, displayH, aspect(boundsW, boundsH))
                Check(
                    "window fills display", Verdict.WARN,
                    "app window ${boundsW}x$boundsH does not cover ${displayW}x$displayH " +
                        "(the app is boxed to ${box[2]}x${box[3]} at ${box[0]},${box[1]}); some apps are locked to portrait",
                )
            }
        }
        return out
    }
}
