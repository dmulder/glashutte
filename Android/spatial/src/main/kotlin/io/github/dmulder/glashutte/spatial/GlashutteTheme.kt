package io.github.dmulder.glashutte.spatial

/**
 * Glashutte's colour palette.
 *
 * Every piece of Glashutte-drawn chrome — window frames, the taskbar — takes its colours from
 * here, so the workspace has one consistent look and a single place to retheme it.
 */
object GlashutteTheme {

    /** Window border: the light-grey frame around an app, à la a desktop OS window. */
    const val windowBorder: Int = 0xFFEAEAEA.toInt()

    /** Window title bar background — its own entry; currently the same grey as the border. */
    const val windowTitleBar: Int = 0xFFEAEAEA.toInt()

    /** Text and button glyphs drawn on the (light) window frame. */
    const val windowFrameText: Int = 0xFF2B2B2B.toInt()

    /**
     * Background for the floating chrome toolbar shown over an app window in
     * FULLSCREEN — a dark colour close to the taskbar's, so the toolbar reads
     * as part of the workspace shell rather than the running app. The slot's
     * Presentation can't have *real* alpha here without hiding the wallpaper
     * view under the chrome rect, so this opaque tone mimics the look of a
     * translucent dark panel.
     */
    const val windowChromeCompactBg: Int = 0xFF1A1F2A.toInt()

    /** Icon tint for the compact (fullscreen) chrome — light on dark. */
    const val windowChromeCompactIcon: Int = 0xFFE6E6E6.toInt()

    /** Taskbar background. */
    const val taskbar: Int = 0xF0121620.toInt()

    /** Taskbar text. */
    const val taskbarText: Int = 0xFFE6E8EE.toInt()
}
