package io.github.dmulder.glashutte.glasses

/**
 * The pointer that the phone trackpad (or a mouse) drives while an app runs flat on the
 * glasses' display. Positions are in that display's own pixels, so the injected touch lands
 * exactly where the on-glasses cursor is drawn.
 *
 * Trackpad deltas arrive as a fraction of the trackpad's width (the same unit the workspace
 * cursor uses); [GAIN] sets how many display widths a full trackpad sweep covers.
 */
class FlatPointer(width: Int, height: Int) {
    var width: Int = width.coerceAtLeast(1)
        private set
    var height: Int = height.coerceAtLeast(1)
        private set

    var x: Float = this.width / 2f
        private set
    var y: Float = this.height / 2f
        private set

    fun resize(newWidth: Int, newHeight: Int) {
        val nw = newWidth.coerceAtLeast(1)
        val nh = newHeight.coerceAtLeast(1)
        if (nw == width && nh == height) return
        x = x / width * nw
        y = y / height * nh
        width = nw
        height = nh
    }

    fun center() {
        x = width / 2f
        y = height / 2f
    }

    /** Move by trackpad fractions; [sensitivity] is the user's cursor sensitivity multiplier. */
    fun moveByFraction(dxFraction: Float, dyFraction: Float, sensitivity: Float = 1f) {
        x = (x + dxFraction * width * GAIN * sensitivity).coerceIn(0f, (width - 1).toFloat())
        y = (y + dyFraction * width * GAIN * sensitivity).coerceIn(0f, (height - 1).toFloat())
    }

    /** Move by raw device ticks (mouse); [ticksPerWidth] ticks sweep one display width. */
    fun moveByTicks(dx: Int, dy: Int, ticksPerWidth: Float, sensitivity: Float = 1f) {
        moveByFraction(dx / ticksPerWidth, dy / ticksPerWidth, sensitivity / GAIN)
    }

    val ix: Int get() = x.toInt()
    val iy: Int get() = y.toInt()

    companion object {
        const val GAIN = 1.4f
    }
}
