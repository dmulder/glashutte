package io.github.dmulder.glashutte.handoff

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import io.github.dmulder.glashutte.glasses.FlatPointer

/**
 * A transparent, untouchable window on the glasses' display that draws only the cursor, so the
 * user can see where the phone trackpad is pointing while an app runs flat underneath.
 *
 * It is an ordinary non-secure window layered above the app. It adds nothing the app can read
 * and draws nothing over the video except the arrow; it exists purely as a pointer. If a
 * device refuses a second window on the display, [NativeHandoff] logs that and carries on
 * without a visible cursor (input still works).
 */
class FlatCursorPresentation(
    outer: Context,
    display: Display,
    private val pointer: FlatPointer,
) : Presentation(outer, display) {

    private var cursorView: CursorView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.setFormat(PixelFormat.TRANSLUCENT)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        val view = CursorView(context, pointer)
        cursorView = view
        setContentView(view)
    }

    fun refresh() {
        cursorView?.postInvalidateOnAnimation()
    }

    private class CursorView(context: Context, private val pointer: FlatPointer) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()

        override fun onDraw(canvas: Canvas) {
            val unit = resources.displayMetrics.density * ARROW_DP
            outline.strokeWidth = unit * 0.09f
            arrow.reset()
            arrow.moveTo(0f, 0f)
            arrow.lineTo(0f, unit * 1.0f)
            arrow.lineTo(unit * 0.26f, unit * 0.76f)
            arrow.lineTo(unit * 0.46f, unit * 1.18f)
            arrow.lineTo(unit * 0.62f, unit * 1.10f)
            arrow.lineTo(unit * 0.42f, unit * 0.70f)
            arrow.lineTo(unit * 0.74f, unit * 0.70f)
            arrow.close()
            val sx = pointer.x * width / pointer.width.toFloat()
            val sy = pointer.y * height / pointer.height.toFloat()
            canvas.save()
            canvas.translate(sx, sy)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
            canvas.restore()
        }

        private companion object {
            const val ARROW_DP = 20f
        }
    }
}
