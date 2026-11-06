package io.github.dmulder.glashutte.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** A launchable application installed on the device. */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    /**
     * The launcher icon, pre-rasterised into a [BitmapDrawable] so each drawer cell is
     * a single texture blit at scroll time — adaptive icons are otherwise composited
     * from multiple layers on every `draw()` call and hitch the GridView during fast
     * scroll. See [InstalledApps.query].
     */
    val icon: Drawable,
)

/** Enumerates launchable apps installed on the device. */
object InstalledApps {

    /**
     * Pixel size each launcher icon is rasterised at. The drawer cell renders at
     * ~52dp × density (~156px on xxhdpi); 192px covers high-DPI screens with headroom
     * for scaling. Costs ~150 KB per icon × ~100 apps ≈ 15 MB resident — acceptable for
     * a launcher-grade app, and pays off in jank-free scroll.
     */
    private const val ICON_PX = 192

    /** Every app with a launcher activity, sorted by display name, icons pre-rasterised. */
    fun query(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val resources = context.resources
        val mainLauncher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(mainLauncher, 0)
            .mapNotNull { resolved -> resolved.toInstalledApp(context, pm, resources) }
            .sortedBy { it.label.lowercase() }
    }

    private fun ResolveInfo.toInstalledApp(
        context: Context,
        pm: PackageManager,
        resources: Resources,
    ): InstalledApp? {
        val activity = activityInfo ?: return null
        if (activity.packageName == context.packageName) return null
        return InstalledApp(
            label = loadLabel(pm).toString(),
            packageName = activity.packageName,
            activityName = activity.name,
            icon = loadIcon(pm).rasterize(resources, ICON_PX),
        )
    }

    /**
     * Render the drawable into a fixed-size bitmap and wrap it in a [BitmapDrawable].
     * Cheap to draw repeatedly afterwards — no per-frame layer composition.
     */
    private fun Drawable.rasterize(resources: Resources, sizePx: Int): Drawable {
        if (this is BitmapDrawable) {
            val bmp = bitmap
            if (bmp != null && bmp.width == sizePx && bmp.height == sizePx) return this
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val previousBounds = copyBounds()
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        bounds = previousBounds
        return BitmapDrawable(resources, bitmap)
    }
}
