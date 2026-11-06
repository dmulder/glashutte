package io.github.dmulder.glashutte.spatial

/**
 * One launched app, wrapped in the **window framework**: a slot-local rectangle
 * the renderer composites the app's bare display into, plus the state machine
 * that decides what that rectangle is at any moment.
 *
 * The class is split conceptually into three layers:
 *
 *  1. **Content** — `ui` (bare trusted display) + `packageName` / `activityName`
 *     / `label`. Immutable after construction.
 *  2. **Geometry** — the outer pixel rect (`xPx, yPx, widthPx, heightPx`),
 *     placed by [bind]. The outer rect spans the chrome strip + activity area.
 *     [activityRect] and [chromeOverlayRect] derive their rectangles from it.
 *  3. **State** — [mode] (one of [WorkspaceController.WindowMode]), [minimized],
 *     [restoreBounds] (the floating rect to return to when un-tiling /
 *     un-maximising), and [interaction] (current drag / resize, idle for now).
 *
 * Each window owns its own bare trusted VirtualDisplay (no Presentation
 * attached — that's what keeps Samsung One UI's transient KEYGUARD_DIALOG off
 * the display and lets injected touches reach the activity). The activity's
 * surface is sampled by [WorkspaceRenderer] as a quad drawn inside the slot's
 * quad at the window's activity area.
 *
 * Layout:
 * ```
 *   ┌─────────────────────────────────────────┐
 *   │  slot wallpaper / desktop               │
 *   │                                         │
 *   │     ┌───────────── window ─────────┐    │
 *   │     │ chrome (icon + title + btns) │    │  ← chromeOverlayRect()
 *   │     ├──────────────────────────────┤    │
 *   │     │                              │    │
 *   │     │   activity content           │    │  ← activityRect()
 *   │     │   (sampled from the bare     │    │
 *   │     │    trusted display)          │    │
 *   │     │                              │    │
 *   │     └──────────────────────────────┘    │
 *   │                                         │
 *   │  taskbar (slot Presentation, fixed)     │
 *   └─────────────────────────────────────────┘
 * ```
 *
 * Subsequent phases fill in the [interaction] state machine: window drag
 * (chrome press-and-hold), edge / corner resize, and tile-on-drag-to-edge.
 */
class AppWindow(
    /** The activity's surface — a bare trusted [UiScreen] (no Presentation on it). */
    val ui: UiScreen,
    /** Package + activity launched onto this window's display. */
    val packageName: String,
    val activityName: String,
    val label: String,
    /** Slot the window currently belongs to (host monitor in the active layout). */
    var slotIdx: Int,
    /** Height of the chrome strip at the top of the window, in slot-local pixels. */
    val chromePx: Int,
) {
    /** Outer window bounds in slot-local pixel coords. Updated by [bind]. */
    var xPx: Int = 0
    var yPx: Int = 0
    var widthPx: Int = 0
    var heightPx: Int = 0

    /**
     * Minimised — the activity keeps running on its bare display, but the renderer
     * skips drawing its quad and the chrome bar is hidden. Restored by tapping the
     * window's taskbar icon, which calls [WorkspaceController.focusApp]. The window
     * record itself stays in `runningWindows` so the activity isn't torn down.
     */
    var minimized: Boolean = false

    /**
     * Window size mode. The chrome's Maximize button cycles
     * NORMAL → MAXIMIZED → FULLSCREEN → NORMAL via [setState]. [bind] picks the
     * matching outer rectangle so a layout move or slot resize keeps the chosen
     * mode intact.
     */
    var mode: WorkspaceController.WindowMode = WorkspaceController.WindowMode.NORMAL

    /**
     * Floating-mode rectangle to return to when leaving a non-NORMAL state.
     * Stays null until the first NORMAL → {MAXIMIZED|FULLSCREEN} transition
     * via [setState] saves the current outer bounds, so any user-positioned
     * floating layout survives a maximise/fullscreen round trip. Null = use
     * the default centred preset on the next NORMAL bind.
     */
    private var restoreBounds: IntArray? = null

    /**
     * Current direct-manipulation state. [Interaction.Idle] when the window is
     * just sitting there; later phases of the window framework add
     * `Dragging(grabOffset)` (chrome press-and-hold) and `Resizing(edge)`
     * (edge / corner press-and-hold).
     */
    sealed class Interaction {
        object Idle : Interaction()
        // Dragging, Resizing: filled in by phases 2 and 3.
    }

    var interaction: Interaction = Interaction.Idle

    /**
     * Activity's trusted display id, or `null` until the bare display has been
     * created (the helper retries on a cold start before READY).
     */
    val displayId: Int? get() = ui.displayId

    /**
     * Activity rectangle inside the slot:
     *  - NORMAL / MAXIMIZED: outer minus the chrome strip at the top.
     *  - FULLSCREEN: the full outer rectangle — the floating chrome toolbar
     *    overlays the activity instead of carving a strip out of it.
     */
    fun activityRect(): IntArray = when (mode) {
        WorkspaceController.WindowMode.FULLSCREEN ->
            intArrayOf(xPx, yPx, widthPx, heightPx)
        else -> intArrayOf(
            xPx, yPx + chromePx, widthPx, (heightPx - chromePx).coerceAtLeast(1),
        )
    }

    /**
     * Slot-local rectangle the chrome view occupies inside the slot's Presentation
     * surface.
     *  - NORMAL / MAXIMIZED: a full-width strip above the activity at the window's
     *    outer top (`xPx, yPx, widthPx, chromePx`).
     *  - FULLSCREEN: a thin toolbar pinned to the slot's top-centre, just wide
     *    enough for the four buttons (back / minimise / maximise / close).
     */
    fun chromeOverlayRect(slotWidthPx: Int): IntArray = when (mode) {
        WorkspaceController.WindowMode.FULLSCREEN -> {
            val w = FULLSCREEN_TOOLBAR_WIDTH_PX.coerceAtMost(slotWidthPx)
            val x = ((slotWidthPx - w) / 2).coerceAtLeast(0)
            intArrayOf(x, 0, w, chromePx)
        }
        else -> intArrayOf(xPx, yPx, widthPx, chromePx)
    }

    /**
     * Transition this window's [mode] and re-bind its outer bounds inside
     * the slot. When leaving NORMAL, the current floating rect is saved to
     * [restoreBounds] so a later return to NORMAL restores the user's
     * position instead of resetting to the centred preset.
     */
    fun setState(
        newMode: WorkspaceController.WindowMode,
        slotWidthPx: Int,
        slotHeightPx: Int,
        taskbarPx: Int,
    ) {
        if (mode == WorkspaceController.WindowMode.NORMAL &&
            newMode != WorkspaceController.WindowMode.NORMAL
        ) {
            restoreBounds = intArrayOf(xPx, yPx, widthPx, heightPx)
        }
        mode = newMode
        bind(slotWidthPx, slotHeightPx, taskbarPx)
    }

    /**
     * Place the window inside its slot based on the current [mode]:
     *  - NORMAL: [restoreBounds] if set (and still fits), otherwise the
     *    default centred 70%×75% preset above the taskbar.
     *  - MAXIMIZED: full slot width × full height above the taskbar.
     *  - FULLSCREEN: full slot, taskbar visually covered by the activity quad.
     */
    fun bind(slotWidthPx: Int, slotHeightPx: Int, taskbarPx: Int) {
        val outer = when (mode) {
            WorkspaceController.WindowMode.NORMAL ->
                restoreBounds?.takeIf { fitsSlot(it, slotWidthPx, slotHeightPx) }
                    ?: computeOuterBounds(slotWidthPx, slotHeightPx, taskbarPx)
            WorkspaceController.WindowMode.MAXIMIZED ->
                computeMaximizedBounds(slotWidthPx, slotHeightPx, taskbarPx)
            WorkspaceController.WindowMode.FULLSCREEN ->
                intArrayOf(0, 0, slotWidthPx, slotHeightPx)
            WorkspaceController.WindowMode.TILED_LEFT ->
                computeTiledBounds(slotWidthPx, slotHeightPx, taskbarPx, left = true)
            WorkspaceController.WindowMode.TILED_RIGHT ->
                computeTiledBounds(slotWidthPx, slotHeightPx, taskbarPx, left = false)
        }
        xPx = outer[0]
        yPx = outer[1]
        widthPx = outer[2]
        heightPx = outer[3]
    }

    private fun fitsSlot(rect: IntArray, slotW: Int, slotH: Int): Boolean =
        rect.size == 4 &&
            rect[0] >= 0 && rect[1] >= 0 &&
            rect[2] >= MIN_USABLE_HEIGHT_PX && rect[3] >= MIN_USABLE_HEIGHT_PX &&
            rect[0] + rect[2] <= slotW && rect[1] + rect[3] <= slotH

    /** Release the trusted display + GL resources. Call on the GL thread. */
    fun release() {
        ui.release()
    }

    companion object {
        /** Fraction of slot width the window occupies at launch (first iteration). */
        const val WINDOW_WIDTH_FRACTION = 0.50f

        /** Fraction of slot height above the taskbar the window occupies at launch. */
        const val WINDOW_HEIGHT_FRACTION = 0.60f

        /** Lower bound on either pixel dimension to keep a window usable. */
        const val MIN_USABLE_HEIGHT_PX = 240

        /**
         * Slot-local pixel width of the floating chrome toolbar shown in
         * FULLSCREEN — exactly four 34 dp buttons wide (back / minimize /
         * maximize / close), no padding, so the row fills the toolbar with
         * no slack. Slot density is 200 dpi, so 4 × 34 dp = 136 dp = 168 px.
         * Button size is [io.github.dmulder.glashutte.desktop.WindowChromeView]'s
         * BUTTON_WIDTH_DP (30% smaller than the original 48 dp).
         */
        const val FULLSCREEN_TOOLBAR_WIDTH_PX = 168

        /**
         * Outer window bounds `[x, y, width, height]` (slot-local pixels) for a window
         * placed by [bind]. Exposed as a static helper so the renderer can size the
         * bare activity display *before* the [AppWindow] is built.
         */
        fun computeOuterBounds(slotWidthPx: Int, slotHeightPx: Int, taskbarPx: Int): IntArray {
            val usableH = (slotHeightPx - taskbarPx).coerceAtLeast(MIN_USABLE_HEIGHT_PX)
            val w = (slotWidthPx * WINDOW_WIDTH_FRACTION).toInt().coerceAtLeast(MIN_USABLE_HEIGHT_PX)
            val h = (usableH * WINDOW_HEIGHT_FRACTION).toInt().coerceAtLeast(MIN_USABLE_HEIGHT_PX)
            val x = ((slotWidthPx - w) / 2).coerceAtLeast(0)
            val y = ((usableH - h) / 2).coerceAtLeast(0)
            return intArrayOf(x, y, w, h)
        }

        /**
         * Maximised outer bounds — the window fills the slot's usable area (full
         * width, full height above the taskbar). Activity area is still the outer
         * minus the chrome strip via [activityRect].
         */
        fun computeMaximizedBounds(slotWidthPx: Int, slotHeightPx: Int, taskbarPx: Int): IntArray {
            val usableH = (slotHeightPx - taskbarPx).coerceAtLeast(MIN_USABLE_HEIGHT_PX)
            return intArrayOf(0, 0, slotWidthPx, usableH)
        }

        /**
         * Half-slot tiled outer bounds — full height above the taskbar, half
         * the slot width. [left] = true → left half (x=0), false → right half
         * (x=slot/2). Uses the same `usableH` as MAXIMIZED so a tiled window
         * stacks neatly with the taskbar.
         */
        fun computeTiledBounds(
            slotWidthPx: Int,
            slotHeightPx: Int,
            taskbarPx: Int,
            left: Boolean,
        ): IntArray {
            val usableH = (slotHeightPx - taskbarPx).coerceAtLeast(MIN_USABLE_HEIGHT_PX)
            val halfW = slotWidthPx / 2
            val x = if (left) 0 else halfW
            val w = if (left) halfW else slotWidthPx - halfW
            return intArrayOf(x, 0, w, usableH)
        }
    }
}
