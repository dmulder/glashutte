package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatPointerTest {
    @Test fun startsCentered() {
        val p = FlatPointer(1920, 1200)
        assertEquals(960, p.ix)
        assertEquals(600, p.iy)
    }

    @Test fun movesAndClampsInsideTheDisplay() {
        val p = FlatPointer(1920, 1200)
        p.moveByFraction(10f, -10f)
        assertEquals(1919, p.ix)
        assertEquals(0, p.iy)
        p.moveByFraction(-10f, 10f)
        assertEquals(0, p.ix)
        assertEquals(1199, p.iy)
    }

    @Test fun horizontalAndVerticalShareOneScale() {
        val p = FlatPointer(1920, 1200)
        p.moveByFraction(0.1f, 0.1f)
        val dx = p.ix - 960
        val dy = p.iy - 600
        assertTrue(kotlin.math.abs(dx - dy) <= 1)
    }

    @Test fun sensitivityScalesMotion() {
        val slow = FlatPointer(1920, 1200).also { it.moveByFraction(0.1f, 0f, 1f) }
        val fast = FlatPointer(1920, 1200).also { it.moveByFraction(0.1f, 0f, 2f) }
        assertTrue(fast.ix - 960 > slow.ix - 960)
    }

    @Test fun resizeKeepsTheRelativePosition() {
        val p = FlatPointer(1000, 500)
        p.moveByFraction(0.1f, 0f)
        val fraction = p.x / 1000f
        p.resize(2000, 1000)
        assertEquals(fraction, p.x / 2000f, 0.001f)
    }
}
