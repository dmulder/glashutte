package io.github.dmulder.glashutte.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Finds the external display the VITURE glasses present themselves as.
 *
 * The glasses are a presentation-category display; Glashutte shows a `Presentation` on it so the
 * glasses show the workspace while the phone keeps its own screen. Glashutte's own virtual
 * screens (the off-screen buffers that host launched apps) are *also* presentation displays,
 * so they are filtered out by name — picking one of those was the bug that broke this before.
 */
object GlassesDisplay {

    private const val TAG = "Glashutte/Display"

    /**
     * Prefix every Glashutte-owned virtual display name shares. The per-slot UI
     * Presentations are named `glashutte-desktop` / `glashutte-desktop-screenN`, and the
     * per-window bare trusted displays are `glashutte-app-N`. We must filter out *all*
     * of them — picking one of our own as "the glasses" was the bug that put the
     * workspace Presentation on top of our own app display, recursing. The prefix
     * has shifted twice now (was `glashutte-screen`, then `glashutte-desktop`); using
     * the broader `glashutte-` removes the need for future updates if more display
     * categories are added.
     */
    private const val GLASHUTTE_SCREEN_PREFIX = "glashutte-"

    /** The glasses' display, or `null` when they are not connected. */
    fun find(context: Context): Display? {
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        Log.i(TAG, "displays visible to Glashutte:")
        displayManager.displays.forEach {
            Log.i(
                TAG,
                "  id=${it.displayId} '${it.name}' " +
                    "${it.mode.physicalWidth}x${it.mode.physicalHeight} state=${it.state}",
            )
        }

        val glasses = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull {
                !it.name.startsWith(GLASHUTTE_SCREEN_PREFIX) && it.state != Display.STATE_OFF
            }
        Log.i(TAG, "glasses display: ${glasses?.let { "id=${it.displayId} '${it.name}'" } ?: "none"}")
        return glasses
    }
}
