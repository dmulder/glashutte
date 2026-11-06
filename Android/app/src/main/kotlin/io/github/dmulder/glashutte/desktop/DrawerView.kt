package io.github.dmulder.glashutte.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.dmulder.glashutte.R
import io.github.dmulder.glashutte.apps.AppCache
import io.github.dmulder.glashutte.apps.InstalledApp
import io.github.dmulder.glashutte.spatial.WorkspaceController

/**
 * Glashutte's app drawer panel — a regular Android view (not a Presentation), embedded
 * as a child of each screen's [DesktopPresentation]. The screen's surface texture
 * therefore contains everything for that screen (wallpaper, taskbar, drawer when
 * visible, launched apps on top), so the renderer just samples one surface per
 * screen — no special drawer geometry, no scrim quad, sizing always matches the
 * host screen by construction.
 *
 * Visibility is driven by the screen's `DesktopPresentation` via a
 * [WorkspaceController.addDrawerStateListener] filter on its own screen index.
 */
class DrawerView(context: Context) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter = AppGridAdapter()
    private val search: EditText
    private val grid: GridView
    private val allAppsTab: TextView
    private val recentTab: TextView

    private val searchQueryListener: (String) -> Unit = { q ->
        mainHandler.post { setSearchText(q) }
    }
    private val modeChangedListener: (WorkspaceController.DrawerMode) -> Unit = { m ->
        mainHandler.post { applyMode(m) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(PANEL_COLOR)
        setPadding(dp(22), dp(22), dp(22), dp(20))

        allAppsTab = tabLabel("All apps") {
            WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.ALL)
        }
        recentTab = tabLabel("Recent") {
            WorkspaceController.setDrawerMode(WorkspaceController.DrawerMode.RECENT)
        }
        val tabs = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(allAppsTab)
            addView(
                recentTab,
                LayoutParams(WRAP, WRAP).apply { marginStart = dp(36) },
            )
        }

        grid = GridView(context).apply {
            numColumns = DRAWER_COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8)
            isVerticalScrollBarEnabled = false
            adapter = this@DrawerView.adapter
            setOnItemClickListener { _, _, position, _ ->
                (this@DrawerView.adapter.getItem(position) as? InstalledApp)?.let(::launch)
            }
        }

        search = EditText(context).apply {
            hint = "Search"
            setHintTextColor(HINT_COLOR)
            setTextColor(TAB_ACTIVE)
            textSize = 14f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(SEARCH_COLOR)
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    adapter.setQuery(s?.toString().orEmpty())
                }
            })
        }

        addView(tabs, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        addView(grid, LayoutParams(MATCH, 0, 1f))
        addView(search, LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        applyMode(WorkspaceController.drawerMode)
        loadApps()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WorkspaceController.onDrawerSearchQuery = searchQueryListener
        WorkspaceController.addDrawerModeListener(modeChangedListener)
        registerDragLookup()
    }

    override fun onDetachedFromWindow() {
        if (WorkspaceController.onDrawerSearchQuery === searchQueryListener) {
            WorkspaceController.onDrawerSearchQuery = null
        }
        WorkspaceController.removeDrawerModeListener(modeChangedListener)
        unregisterDragLookup()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Re-sync to the controller's current mode whenever this drawer becomes visible.
        // Defensive: ensures the tab + grid reflect the mode the App-drawer button just
        // set, even if our listener was lost or this DrawerView was constructed while
        // the controller held a different (stale) mode.
        if (changedView === this && visibility == VISIBLE) {
            applyMode(WorkspaceController.drawerMode)
            registerDragLookup()
        } else if (changedView === this && visibility != VISIBLE) {
            unregisterDragLookup()
        }
    }

    /**
     * While this drawer is visible, expose a slot-pixel → grid-item lookup so the
     * renderer can arm a drag-from-drawer when the cursor press-and-holds on a cell.
     * Only one drawer is ever visible at a time (modal exclusivity is owned by
     * [WorkspaceController]), so a single global hook is enough.
     */
    private val dragLookup: (Int, Int) -> WorkspaceController.ArmedDrag? = { slotPx, slotPy ->
        appAtSlotPx(slotPx, slotPy)?.let { app ->
            WorkspaceController.ArmedDrag(
                packageName = app.packageName,
                activityName = app.activityName,
                label = app.label,
                iconBitmap = app.icon.toBitmap(),
            )
        }
    }

    private fun registerDragLookup() {
        WorkspaceController.drawerItemAt = dragLookup
        android.util.Log.i(
            "Glashutte/DrawerDrag",
            "DrawerView.registerDragLookup attached=$isAttachedToWindow vis=${visibility} bounds=(L=$left T=$top W=$width H=$height) grid=(L=${grid.left} T=${grid.top} W=${grid.width} H=${grid.height}) items=${adapter.count}",
        )
    }

    private fun unregisterDragLookup() {
        if (WorkspaceController.drawerItemAt === dragLookup) {
            WorkspaceController.drawerItemAt = null
            android.util.Log.i("Glashutte/DrawerDrag", "DrawerView.unregisterDragLookup")
        }
    }

    /**
     * Map a slot-local pixel (cursor projected onto the slot's surface) to the drawer
     * grid item under it. Walks the slot → DrawerView → GridView coordinate chain by
     * subtracting view offsets; the GridView's own `pointToPosition` finds the cell.
     * Returns null when the cursor is outside the drawer or not over a cell.
     */
    private fun appAtSlotPx(slotPx: Int, slotPy: Int): InstalledApp? {
        val drawerX = slotPx - left
        val drawerY = slotPy - top
        if (drawerX < 0 || drawerY < 0 || drawerX >= width || drawerY >= height) return null
        val gridX = drawerX - grid.left
        val gridY = drawerY - grid.top
        if (gridX < 0 || gridY < 0 || gridX >= grid.width || gridY >= grid.height) return null
        val pos = grid.pointToPosition(gridX, gridY)
        if (pos < 0 || pos >= adapter.count) return null
        return adapter.getItem(pos) as? InstalledApp
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) {
            bitmap?.let { return it }
        }
        val w = intrinsicWidth.takeIf { it > 0 } ?: DRAG_ICON_PX
        val h = intrinsicHeight.takeIf { it > 0 } ?: DRAG_ICON_PX
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val prev = copyBounds()
        setBounds(0, 0, w, h)
        draw(c)
        bounds = prev
        return bmp
    }

    private fun applyMode(mode: WorkspaceController.DrawerMode) {
        styleTab(allAppsTab, mode == WorkspaceController.DrawerMode.ALL)
        styleTab(recentTab, mode == WorkspaceController.DrawerMode.RECENT)
        adapter.setMode(mode, WorkspaceController.recentApps)
    }

    private fun styleTab(tab: TextView, active: Boolean) {
        if (active) {
            tab.setTextColor(TAB_ACTIVE)
            tab.typeface = Typeface.DEFAULT_BOLD
        } else {
            tab.setTextColor(TAB_INACTIVE)
            tab.typeface = Typeface.DEFAULT
        }
    }

    private fun setSearchText(query: String) {
        if (search.text.toString() == query) return
        search.setText(query)
        search.setSelection(query.length)
    }

    private fun tabLabel(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setTextColor(TAB_INACTIVE)
        setOnClickListener { onClick() }
    }

    private fun launch(app: InstalledApp) {
        android.util.Log.i(
            "Glashutte/Launch",
            "1) drawer tap pkg=${app.packageName} act=${app.activityName} label='${app.label}'",
        )
        WorkspaceController.launchApp(app.packageName, app.activityName, app.label)
        WorkspaceController.setDrawerOpen(false)
    }

    private fun loadApps() {
        AppCache.whenReady { list ->
            android.util.Log.i(
                "Glashutte/Drawer",
                "DrawerView whenReady got ${list.size} apps; mode=${WorkspaceController.drawerMode}",
            )
            mainHandler.post { adapter.submit(list) }
        }
    }

    private inner class AppGridAdapter : BaseAdapter() {
        private val full = ArrayList<InstalledApp>()
        private val items = ArrayList<InstalledApp>()
        private var query = ""
        private var mode: WorkspaceController.DrawerMode = WorkspaceController.DrawerMode.ALL
        private var recentOrder: List<String> = emptyList()

        fun submit(apps: List<InstalledApp>) {
            full.clear()
            full.addAll(apps)
            recompute()
            android.util.Log.i(
                "Glashutte/Drawer",
                "AppGridAdapter.submit: full=${full.size} items=${items.size} mode=$mode",
            )
        }

        fun setQuery(text: String) {
            query = text.trim()
            recompute()
        }

        fun setMode(mode: WorkspaceController.DrawerMode, recentPackages: List<String>) {
            this.mode = mode
            this.recentOrder = recentPackages
            recompute()
        }

        private fun recompute() {
            val base = when (mode) {
                WorkspaceController.DrawerMode.ALL -> full
                WorkspaceController.DrawerMode.RECENT -> {
                    val byPkg = full.associateBy { it.packageName }
                    recentOrder.mapNotNull { byPkg[it] }
                }
            }
            items.clear()
            items.addAll(
                if (query.isEmpty()) base
                else base.filter { it.label.contains(query, ignoreCase = true) },
            )
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? LinearLayout ?: newCell()
            val app = items[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }

        private fun newCell(): LinearLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            addView(ImageView(context), LayoutParams(dp(52), dp(52)))
            addView(
                TextView(context).apply {
                    setTextColor(LABEL_COLOR)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) },
            )
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DRAWER_COLUMNS = 8

        const val PANEL_COLOR = 0xFFECEDEF.toInt()
        const val SEARCH_COLOR = 0xFFE3E4E8.toInt()
        const val LABEL_COLOR = 0xFF33353B.toInt()
        const val TAB_ACTIVE = 0xFF1A1B1F.toInt()
        const val TAB_INACTIVE = 0xFF9A9CA3.toInt()
        const val HINT_COLOR = 0xFF8A8C93.toInt()

        /** Fallback rasterisation size for an app icon with no intrinsic dims. */
        const val DRAG_ICON_PX = 192
    }
}
