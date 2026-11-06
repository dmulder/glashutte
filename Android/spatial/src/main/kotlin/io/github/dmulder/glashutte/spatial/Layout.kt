package io.github.dmulder.glashutte.spatial

/**
 * Multi-screen workspace layout cycled by the toolbar layout button.
 *
 * Terminology (per the user's vocabulary):
 *  - **Virtual space** = the whole 3D scene the glasses see.
 *  - **Display** / **Screen** = one virtual display with its own UI on it. Each layout
 *    declares a fixed list of [Screen]s — pixel resolution for the backing VirtualDisplay
 *    plus a geometric placement in virtual space.
 *
 * Switching the layout tears down and rebuilds the per-screen UiScreens / Presentations;
 * apps that were running are saved per-(layout, screenIdx) and relaunched when the
 * layout comes back.
 */
enum class Layout(
    val displayName: String,
    val screens: List<Screen>,
    /**
     * Hidden projection multiplier per layout — compensates for the fact that some
     * layouts (Single Wide, V/H/V) have smaller default world-size than Single, so the
     * *displayed* zoom % can mean the same visual scale across all layouts. The toolbar
     * zoom button + pinch HUD always show the user's displayed %; the projection
     * applies `displayedZoom × zoomBaseScale`. 1.0 = no adjustment (Single baseline).
     */
    val zoomBaseScale: Float = 1f,
    /**
     * Default "main screen" index for the layout — Two SBS uses left (0), Three SBS the
     * centre (1), V/H/V the H (1). Single layouts default to their only screen. The user
     * can override this per-layout in settings; the override lives in WorkspaceSettings.
     */
    val defaultMainScreen: Int = 0,
) {
    SINGLE(
        "Single",
        listOf(
            Screen(1920, 1080, centerXFraction = 0f, widthFraction = 1.0f),
        ),
    ),
    SINGLE_WIDE(
        "Single Wide",
        listOf(
            // widthFraction is unused for curved screens — drawCurvedScreen computes its
            // geometry from curveDeg + radius.
            Screen(
                3840, 1200,
                centerXFraction = 0f, widthFraction = 1.0f,
                curveDeg = 90f,
            ),
        ),
        // Wide's natural world height (~1.96m) is ~52% of Single's (~3.66m). Boost the
        // projection so its 100% displayed zoom fills the view to a similar degree.
        zoomBaseScale = 1.87f,
    ),
    TWO_SBS(
        "Two SBS",
        listOf(
            // centerXFraction = (half_world_width × cos(yaw)) / desktopHalfWidth so the
            // two screens' inner edges meet at world x ≈ 0 (no visible gap). yaw ±10°
            // keeps the Z-displacement at the join small (~0.3 m).
            Screen(1920, 1080, centerXFraction = -0.47f, widthFraction = 0.48f, yawDeg = 10f),
            Screen(1920, 1080, centerXFraction = 0.47f, widthFraction = 0.48f, yawDeg = -10f),
        ),
        // Each screen is ~2.0m tall vs Single's ~3.66m; compensate so "100% zoom" gives
        // the same perceived size as Single's 100%.
        zoomBaseScale = 1.83f,
        defaultMainScreen = 0,
    ),
    THREE_SBS(
        "Three SBS",
        listOf(
            // Side screens sit on the same 4 m surround arc as the (flat) middle screen,
            // so each side screen's inner edge lands at the exact world point (x=±1.18 m,
            // z=−4 m) of the middle screen's left/right edge — no apparent Z overlap and
            // no foreshortening difference.
            //   Geometry: solve R(1−cos d) = half_w · sin d  →  d ≈ 33°.
            //   centerX = R·sin d ≈ 2.18 m  (→ centerXFraction 2.18/3.7 ≈ 0.59)
            //   centerZ = −R·cos d ≈ −3.35 m  (→ centerZOffset = +0.65 m)
            Screen(
                1920, 1080,
                centerXFraction = -0.59f, widthFraction = 0.32f,
                yawDeg = 33f, centerZOffset = 0.65f,
            ),
            Screen(1920, 1080, centerXFraction = 0f, widthFraction = 0.32f, yawDeg = 0f),
            Screen(
                1920, 1080,
                centerXFraction = 0.59f, widthFraction = 0.32f,
                yawDeg = -33f, centerZOffset = 0.65f,
            ),
        ),
        // Per-screen perceived size matches Single's at the same displayed %.
        zoomBaseScale = 2.75f,
        defaultMainScreen = 1,
    ),
    THREE_VHV(
        "Three V/H/V",
        listOf(
            // V screens arranged so their inner edges butt-join the (flat) H screen's
            // edges at the exact world point (x=±1.85 m, z=−4 m). With widthFraction
            // 0.24 → half_w 0.89, solving R(1−cos d) = half_w·sin d under the H-edge
            // constraint gives d≈36.4°, R≈4.32 m → centerXFraction ±0.69, centerZOffset +0.53.
            Screen(
                1080, 1920,
                centerXFraction = -0.69f, widthFraction = 0.24f,
                yawDeg = 36.4f, centerZOffset = 0.53f, showTaskbar = true,
            ),
            Screen(
                1920, 1080,
                centerXFraction = 0f, widthFraction = 0.50f,
                yawDeg = 0f, showTaskbar = true,
            ),
            Screen(
                1080, 1920,
                centerXFraction = 0.69f, widthFraction = 0.24f,
                yawDeg = -36.4f, centerZOffset = 0.53f, showTaskbar = true,
            ),
        ),
        // H screen ~2.08m tall vs Single's 3.66m; zoom comp keeps perceived size in line
        // with other layouts at the same displayed %.
        zoomBaseScale = 1.76f,
        defaultMainScreen = 1,
    ),
}

/**
 * One virtual screen inside a [Layout]. The screen's UI is backed by a VirtualDisplay
 * of [contentWidthPx] × [contentHeightPx]; the quad is positioned by [centerXFraction]
 * (of desktop half-width) at a target width of [widthFraction] of the desktop area's
 * width. Height follows from the content aspect, shrunk height-first to fit.
 */
data class Screen(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val centerXFraction: Float,
    val widthFraction: Float,
    /**
     * Yaw (degrees about the Y axis) applied to the screen's quad. Positive yaws the
     * screen to face left, negative to face right — used to surround the user with side
     * screens in multi-screen layouts.
     */
    val yawDeg: Float = 0f,
    /**
     * Pull the screen closer to the viewer by this many metres (added to the default
     * `−SCREEN_DISTANCE` z). Lets a yawed side screen sit on the same surround arc as a
     * flat centre screen, so inner edges butt-join at the exact world point of the
     * centre's edge instead of swinging behind it in Z and appearing to overlap.
     */
    val centerZOffset: Float = 0f,
    /**
     * Whether this screen's per-screen [io.github.dmulder.glashutte.desktop.DesktopPresentation] shows
     * its status / taskbar row. Defaults to true; V screens in the V/H/V layout turn it
     * off so the H screen owns the taskbar.
     */
    val showTaskbar: Boolean = true,
    /**
     * Total horizontal arc (degrees) the screen subtends when curved — > 0 renders the
     * screen as a triangle-strip mesh wrapped on a cylinder, like a curved ultrawide
     * monitor. 0 = flat. Used by the Single Wide layout.
     */
    val curveDeg: Float = 0f,
) {
    companion object {
        /** Distance of the screen plane from the viewer, in metres. */
        const val SCREEN_DISTANCE = 4.0f

        /** Fraction of the desktop height reserved at the bottom for the taskbar. */
        private const val TASKBAR_RESERVE = 0.085f

        /** Screen height shrinks by this margin so it never bumps the taskbar. */
        private const val SCREEN_HEIGHT_MARGIN = 0.96f

        /**
         * World-space placement rectangle for a screen — `[centerX, centerY, z, width,
         * height]` in metres. Width follows `widthFraction` of the desktop area; height
         * follows the screen's content aspect, shrunk height-first to fit.
         */
        fun worldRect(
            screen: Screen,
            desktopHalfWidth: Float,
            desktopHalfHeight: Float,
        ): FloatArray {
            val topY = desktopHalfHeight
            val bottomY = -desktopHalfHeight + TASKBAR_RESERVE * (2f * desktopHalfHeight)
            val areaW = 2f * desktopHalfWidth
            val areaH = topY - bottomY
            val aspect = screen.contentWidthPx.toFloat() / screen.contentHeightPx
            var fw = areaW * screen.widthFraction
            var fh = fw / aspect
            val maxH = areaH * SCREEN_HEIGHT_MARGIN
            if (fh > maxH) { fh = maxH; fw = fh * aspect }
            val cx = screen.centerXFraction * desktopHalfWidth
            val cy = (topY + bottomY) / 2f
            val cz = -SCREEN_DISTANCE + screen.centerZOffset
            return floatArrayOf(cx, cy, cz, fw, fh)
        }
    }
}
