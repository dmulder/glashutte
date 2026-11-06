package io.github.dmulder.glashutte.glasses

import android.os.Build
import android.view.Display
import android.view.Window
import android.view.WindowManager

/** One entry of `Display.getSupportedModes()`, reduced to what mode selection needs. */
data class DisplayModeInfo(val modeId: Int, val width: Int, val height: Int, val refreshHz: Float)

/**
 * Picks and requests the glasses display's highest refresh rate.
 *
 * The workspace is rendered for the panel's current resolution, so only modes at that
 * resolution are candidates: a faster mode at a different size would rescale the whole
 * picture. [chooseHighest] is pure so it is unit-tested; [apply] is the thin Android shell.
 */
object DisplayRefresh {

    /**
     * The fastest mode with the same pixel size as [width] x [height], or null when no mode has
     * that size. Ties on rate keep the first listed mode.
     */
    fun chooseHighest(modes: List<DisplayModeInfo>, width: Int, height: Int): DisplayModeInfo? =
        modes
            .filter { it.width == width && it.height == height }
            .fold(null as DisplayModeInfo?) { acc, m ->
                if (acc == null || m.refreshHz > acc.refreshHz + 0.01f) m else acc
            }

    /** What [apply] did, for the diagnostics log. */
    class Result(val requestedModeId: Int, val description: String)

    /**
     * Request the highest mode ([enabled]) or clear the request (`!enabled`) for [window] on
     * [display]. Always safe: on failure the display simply stays in its current mode.
     * Needs API 23+ (`preferredDisplayModeId`); older devices are left alone.
     */
    fun apply(window: Window?, display: Display?, enabled: Boolean): Result {
        if (window == null || display == null) return Result(0, "no window/display")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Result(0, "API < 23: refresh selection unavailable")
        val modes = display.supportedModes.map {
            DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        val current = display.mode
        val target = if (enabled) {
            chooseHighest(modes, current.physicalWidth, current.physicalHeight)
        } else {
            null
        }
        val wanted = target?.modeId ?: 0
        val params = window.attributes
        if (params.preferredDisplayModeId != wanted) {
            window.attributes = WindowManager.LayoutParams().also {
                it.copyFrom(params)
                it.preferredDisplayModeId = wanted
            }
        }
        val list = modes.joinToString(",") { "${it.modeId}:${it.width}x${it.height}@${"%.0f".format(it.refreshHz)}" }
        return Result(
            wanted,
            "requested=" + (target?.let { "mode ${it.modeId} @${"%.0f".format(it.refreshHz)}Hz" }
                ?: if (enabled) "none (no mode at ${current.physicalWidth}x${current.physicalHeight})" else "system default") +
                " supported=[$list]",
        )
    }

    /** `1920x1200@120Hz (mode 3)` for the live mode of [display]. */
    fun describeActive(display: Display?): String {
        if (display == null) return "display: none"
        val m = display.mode
        return "display: ${m.physicalWidth}x${m.physicalHeight}@${"%.0f".format(display.refreshRate)}Hz (mode ${m.modeId})"
    }
}
