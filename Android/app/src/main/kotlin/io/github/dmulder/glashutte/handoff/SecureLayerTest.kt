package io.github.dmulder.glashutte.handoff

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import io.github.dmulder.glashutte.glasses.GlassesDiagnostics
import io.github.dmulder.glashutte.glasses.SecureLayerVerdict

/**
 * Secure-layer self tests. Each puts one ordinary window (red) and one FLAG_SECURE window
 * (blue) side by side on a display. It draws only its own test colours and never touches
 * anyone else's content.
 *
 *  - [runOnVirtualDisplay]: a private virtual display of this app's, read back through an
 *    ImageReader (permitted: it is this app's own display). Shows what the workspace's
 *    composition path does to secure layers.
 *  - [showOnGlasses]: the same two windows on the real glasses display. Nothing can be read
 *    back from a real display, so the person looks: if the blue half is black or absent, that
 *    display does not accept secure layers.
 */
object SecureLayerTest {
    private const val TAG = GlassesDiagnostics.TAG
    private const val NORMAL = 0xFFE53935.toInt()
    private const val SECURE = 0xFF1E88E5.toInt()
    private const val W = 640
    private const val H = 400

    private val main = Handler(Looper.getMainLooper())

    private class HalfPresentation(
        context: Context,
        display: Display,
        private val left: Boolean,
        private val secure: Boolean,
        private val colour: Int,
        private val text: String,
    ) : Presentation(context, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val w = window ?: return
            w.setFormat(PixelFormat.OPAQUE)
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            if (secure) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val metrics = context.resources.displayMetrics
            val attrs = w.attributes
            attrs.width = metrics.widthPixels / 2
            attrs.height = WindowManager.LayoutParams.MATCH_PARENT
            attrs.gravity = if (left) Gravity.START else Gravity.END
            w.attributes = attrs
            setContentView(
                TextView(context).apply {
                    setBackgroundColor(colour)
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    this.text = this@HalfPresentation.text
                },
            )
        }
    }

    fun runOnVirtualDisplay(context: Context, done: (String) -> Unit) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        val vd = try {
            dm.createVirtualDisplay(
                "glashutte-secure-test", W, H, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } catch (t: Throwable) {
            reader.close()
            finish(done, "virtual display could not be created: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (vd == null) {
            reader.close()
            finish(done, "virtual display could not be created (null)")
            return
        }
        val a = HalfPresentation(context, vd.display, true, false, NORMAL, "NORMAL")
        val b = HalfPresentation(context, vd.display, false, true, SECURE, "SECURE")
        try {
            a.show()
            b.show()
        } catch (t: Throwable) {
            runCatching { a.dismiss() }; runCatching { b.dismiss() }
            vd.release(); reader.close()
            finish(done, "test windows were refused on the virtual display: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        main.postDelayed({
            val message = try {
                val image = reader.acquireLatestImage()
                if (image == null) {
                    "no frame arrived from the virtual display"
                } else {
                    image.use {
                        val plane = it.planes[0]
                        val buf = plane.buffer
                        fun pixel(x: Int, y: Int): Int {
                            val o = y * plane.rowStride + x * plane.pixelStride
                            val r = buf.get(o).toInt() and 0xFF
                            val g = buf.get(o + 1).toInt() and 0xFF
                            val bl = buf.get(o + 2).toInt() and 0xFF
                            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                        }
                        val n = pixel(W / 4, H / 2)
                        val s = pixel(3 * W / 4, H / 2)
                        val outcome = SecureLayerVerdict.classify(n, s, NORMAL, SECURE)
                        "normal=#%06X secure=#%06X -> %s: %s".format(n and 0xFFFFFF, s and 0xFFFFFF, outcome, SecureLayerVerdict.describe(outcome))
                    }
                }
            } catch (t: Throwable) {
                "readback failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            runCatching { a.dismiss() }; runCatching { b.dismiss() }
            vd.release(); reader.close()
            finish(done, message)
        }, 1500)
    }

    private var onGlasses: List<Presentation> = emptyList()

    fun showOnGlasses(context: Context, display: Display, done: (String) -> Unit) {
        stopOnGlasses()
        val a = HalfPresentation(context, display, true, false, NORMAL, "NORMAL window\nyou should see this red half")
        val b = HalfPresentation(context, display, false, true, SECURE, "FLAG_SECURE window\nblue = secure layers allowed\nblack/missing = blanked")
        try {
            a.show()
            b.show()
        } catch (t: Throwable) {
            runCatching { a.dismiss() }; runCatching { b.dismiss() }
            finish(done, "test windows were refused on the glasses display: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        onGlasses = listOf(a, b)
        main.postDelayed({ stopOnGlasses() }, 30_000)
        finish(
            done,
            "showing a red (normal) and a blue (FLAG_SECURE) half on the glasses display for 30 s. " +
                "Report what you see: both halves, or red only / red plus black.",
        )
    }

    fun stopOnGlasses() {
        onGlasses.forEach { runCatching { it.dismiss() } }
        onGlasses = emptyList()
    }

    private fun finish(done: (String) -> Unit, message: String) {
        GlassesDiagnostics.i(TAG, "SECURE-LAYER-TEST: $message")
        done(message)
    }
}
