package io.github.dmulder.glashutte.apps

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Eager, app-start app-list cache. Triggered once from [io.github.dmulder.glashutte.GlashutteApp.onCreate] on
 * a background thread; by the time the user opens the drawer, the list is ready and the
 * icons are already rasterised into bitmaps — no [PackageManager] hits, no
 * `AdaptiveIconDrawable.draw()` overhead, no jank during scroll.
 *
 * The result is published through an [AtomicReference], so readers see either `null`
 * (still loading) or the full list (ready). Callers that hit `null` should fall back to a
 * synchronous query.
 */
object AppCache {
    private const val TAG = "Glashutte/AppCache"
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppCache-loader").apply { isDaemon = true }
    }
    private val cache = AtomicReference<List<InstalledApp>?>(null)
    private val listeners = mutableListOf<(List<InstalledApp>) -> Unit>()
    @Volatile private var loaded = false

    /** Kicks off the background load. Safe to call more than once — only the first wins. */
    fun preload(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
        }
        val appContext = context.applicationContext
        loader.execute {
            val t = System.currentTimeMillis()
            val apps = runCatching { InstalledApps.query(appContext) }
                .onFailure { Log.e(TAG, "preload failed", it) }
                .getOrDefault(emptyList())
            cache.set(apps)
            Log.d(TAG, "preloaded ${apps.size} apps in ${System.currentTimeMillis() - t}ms")
            val snapshot = synchronized(listeners) {
                val l = listeners.toList()
                listeners.clear()
                l
            }
            snapshot.forEach { runCatching { it(apps) } }
        }
    }

    /**
     * Run [block] on the loader thread once the cache is ready (immediately if already
     * loaded). Use to populate the drawer adapter without blocking the UI thread.
     */
    fun whenReady(block: (List<InstalledApp>) -> Unit) {
        val ready = cache.get()
        if (ready != null) {
            block(ready)
            return
        }
        synchronized(listeners) {
            // Double-check: load may have completed while we waited for the lock.
            val now = cache.get()
            if (now != null) {
                block(now)
                return
            }
            listeners.add(block)
        }
    }
}
