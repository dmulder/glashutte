package io.github.dmulder.glashutte.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.dmulder.glashutte.spatial.WorkspaceController

/**
 * Per-screen desktop overlay that paints pinned [DesktopShortcut]s on the wallpaper and
 * lets the user tap them to launch the underlying app. One instance is embedded in each
 * [DesktopPresentation], between the wallpaper and the taskbar / drawer / settings
 * modals.
 *
 * Storage is keyed by *desktop index* (not the layout's slot ordinal) via
 * [DesktopShortcutsStore.desktopIdxFor], so the same desktop content appears on whichever
 * physical screen is wired to that index in a given layout.
 *
 * Items are absolutely positioned by their fractional `(x, y)` so they survive density
 * changes; the cell size is fixed in dp. Long-press a placed icon to pick it up — the
 * renderer detects it via the registered [WorkspaceController.registerDesktopShortcutLookup]
 * hook, arms a drag, and the icon is hidden until the drag finishes.
 */
class DesktopShortcutsView(
    context: Context,
    private val slotIdx: Int,
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cells indexed by package name so [shortcutAtPx] can map a hit back to its data. */
    private val cellsByPackage = mutableMapOf<String, View>()
    private val shortcutsByPackage = mutableMapOf<String, DesktopShortcut>()

    private fun currentDesktopIdx(): Int =
        DesktopShortcutsStore.desktopIdxFor(WorkspaceController.layout, slotIdx)

    private val storeListener: (Int) -> Unit = { changedIdx ->
        if (changedIdx == currentDesktopIdx()) {
            mainHandler.post { refresh() }
        }
    }

    private val armedDragListener: (WorkspaceController.ArmedDrag?) -> Unit = { _ ->
        // When a drag is armed/cleared, refresh — we may need to hide or show the
        // icon corresponding to the package currently being dragged from this slot.
        mainHandler.post { refresh() }
    }

    /**
     * Lookup registered with the controller — given a slot-local pixel, returns an
     * [WorkspaceController.ArmedDrag] for the icon under it (with `sourceSlotIdx` set
     * to this slot), or null if the cursor isn't on any icon.
     */
    private val pickupLookup: (Int, Int) -> WorkspaceController.ArmedDrag? = { slotPx, slotPy ->
        shortcutAtSlotPx(slotPx, slotPy)?.let { shortcut ->
            WorkspaceController.ArmedDrag(
                packageName = shortcut.packageName,
                activityName = shortcut.activityName,
                label = shortcut.label,
                iconBitmap = iconBitmapFor(shortcut.packageName),
                sourceSlotIdx = slotIdx,
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DesktopShortcutsStore.addChangeListener(storeListener)
        WorkspaceController.addArmedDragListener(armedDragListener)
        WorkspaceController.registerDesktopShortcutLookup(slotIdx, pickupLookup)
        refresh()
    }

    override fun onDetachedFromWindow() {
        DesktopShortcutsStore.removeChangeListener(storeListener)
        WorkspaceController.removeArmedDragListener(armedDragListener)
        WorkspaceController.unregisterDesktopShortcutLookup(slotIdx, pickupLookup)
        super.onDetachedFromWindow()
    }

    /** Re-read this desktop's shortcuts from the store and rebuild the icon cells. */
    fun refresh() {
        removeAllViews()
        cellsByPackage.clear()
        shortcutsByPackage.clear()
        val shortcuts = DesktopShortcutsStore.shortcutsFor(currentDesktopIdx())
        if (shortcuts.isEmpty()) return
        val parentW = width.takeIf { it > 0 } ?: return scheduleRefresh()
        val parentH = height.takeIf { it > 0 } ?: return scheduleRefresh()
        val cellW = dp(CELL_WIDTH_DP)
        val cellH = dp(CELL_HEIGHT_DP)
        // Hide the icon for the package currently being moved from this slot, so the
        // user doesn't see two copies. Cross-desktop transfers leave both visible.
        val hiddenPkg = WorkspaceController.armedDrawerDrag
            ?.takeIf { it.sourceSlotIdx == slotIdx }?.packageName
        shortcuts.forEach { shortcut ->
            shortcutsByPackage[shortcut.packageName] = shortcut
            if (shortcut.packageName == hiddenPkg) return@forEach
            val cell = buildCell(shortcut)
            cellsByPackage[shortcut.packageName] = cell
            val cx = (shortcut.xFraction * parentW).toInt()
            val cy = (shortcut.yFraction * parentH).toInt()
            val left = (cx - cellW / 2).coerceIn(0, parentW - cellW)
            val top = (cy - cellH / 2).coerceIn(0, parentH - cellH)
            addView(
                cell,
                LayoutParams(cellW, cellH).apply {
                    leftMargin = left
                    topMargin = top
                    gravity = Gravity.TOP or Gravity.START
                },
            )
        }
    }

    private var refreshPending = false
    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        post {
            refreshPending = false
            refresh()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) refresh()
    }

    /**
     * Map a slot-local pixel to the shortcut underneath, by hit-testing each cell's
     * layout rectangle. Returns null if the cursor isn't on any cell.
     */
    private fun shortcutAtSlotPx(slotPx: Int, slotPy: Int): DesktopShortcut? {
        // The view spans the whole slot, so slot-local px is also our local px.
        cellsByPackage.forEach { (pkg, cell) ->
            if (slotPx >= cell.left && slotPx < cell.right &&
                slotPy >= cell.top && slotPy < cell.bottom
            ) {
                return shortcutsByPackage[pkg]
            }
        }
        return null
    }

    private fun iconBitmapFor(packageName: String): Bitmap {
        val drawable = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull()
        return drawable.toBitmap()
    }

    private fun Drawable?.toBitmap(): Bitmap {
        if (this is BitmapDrawable) bitmap?.let { return it }
        val w = (this?.intrinsicWidth ?: 0).takeIf { it > 0 } ?: DRAG_ICON_PX
        val h = (this?.intrinsicHeight ?: 0).takeIf { it > 0 } ?: DRAG_ICON_PX
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (this != null) {
            val c = Canvas(bmp)
            val prev = copyBounds()
            setBounds(0, 0, w, h)
            draw(c)
            bounds = prev
        }
        return bmp
    }

    private fun buildCell(shortcut: DesktopShortcut): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
        addView(
            ImageView(context).apply {
                setImageDrawable(
                    runCatching {
                        context.packageManager.getApplicationIcon(shortcut.packageName)
                    }.getOrNull(),
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutParams(dp(ICON_PX_DP), dp(ICON_PX_DP)),
        )
        addView(
            TextView(context).apply {
                text = shortcut.label
                setTextColor(LABEL_COLOR)
                setShadowLayer(2f, 0f, 1f, LABEL_SHADOW)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) },
        )
        setOnClickListener {
            WorkspaceController.launchApp(
                shortcut.packageName, shortcut.activityName, shortcut.label,
            )
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val CELL_WIDTH_DP = 56
        const val CELL_HEIGHT_DP = 72
        const val ICON_PX_DP = 44
        const val LABEL_COLOR = 0xFFFFFFFF.toInt()
        const val LABEL_SHADOW = 0xCC000000.toInt()

        const val DRAG_ICON_PX = 192
    }
}
