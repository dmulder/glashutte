package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationGateTest {

    private val calibrating = CalibrationGate.Phase.CALIBRATING
    private val ready = CalibrationGate.Phase.READY
    private val stable = CalibrationGate.STATUS_STABLE
    private val unstable = 1

    private fun gate(waitMs: Long = 10_000, hold: Long = 500, extra: Long = 20_000) =
        CalibrationGate(enabled = true, waitMs = waitMs, stableHoldMs = hold, maxExtraMs = extra)

    @Test
    fun staysClosedUntilTheMinimumWaitHasPassedWithoutAStatus() {
        val g = gate()
        g.begin(1_000)
        assertEquals(calibrating, g.update(1_000, statusUsable = false, status = -1))
        assertEquals(calibrating, g.update(10_999, statusUsable = false, status = -1))
        assertEquals(ready, g.update(11_000, statusUsable = false, status = -1))
        assertFalse(g.timedOut)
    }

    @Test
    fun aStableStatusAloneDoesNotOpenTheGateBeforeTheWait() {
        val g = gate()
        g.begin(0)
        for (t in 0..9_000 step 500) assertEquals(calibrating, g.update(t.toLong(), true, stable))
    }

    @Test
    fun needsTheStatusStableForTheHoldTimeAfterTheWait() {
        val g = gate()
        g.begin(0)
        g.update(9_000, true, unstable)
        assertEquals(calibrating, g.update(10_000, true, unstable))
        assertEquals(calibrating, g.update(10_100, true, stable))
        assertEquals(calibrating, g.update(10_599, true, stable))
        assertEquals(ready, g.update(10_600, true, stable))
        assertFalse(g.timedOut)
    }

    @Test
    fun anUnstableBlipRestartsTheHold() {
        val g = gate()
        g.begin(0)
        g.update(10_000, true, stable)
        g.update(10_300, true, unstable)
        assertEquals(calibrating, g.update(10_700, true, stable))
        assertEquals(calibrating, g.update(11_100, true, stable))
        assertEquals(ready, g.update(11_200, true, stable))
    }

    @Test
    fun anUnsettledStatusEventuallyTimesOutInsteadOfBlockingForever() {
        val g = gate(waitMs = 10_000, extra = 20_000)
        g.begin(0)
        assertEquals(calibrating, g.update(29_999, true, unstable))
        assertEquals(ready, g.update(30_000, true, unstable))
        assertTrue(g.timedOut)
    }

    @Test
    fun statusIsIgnoredWhenNotUsable() {
        val g = gate()
        g.begin(0)
        assertEquals(ready, g.update(10_000, statusUsable = false, status = unstable))
    }

    @Test
    fun disabledGateOpensImmediately() {
        val g = CalibrationGate(enabled = false)
        g.begin(0)
        assertEquals(ready, g.update(0, true, unstable))
    }

    @Test
    fun disablingMidCalibrationOpensOnTheNextPose() {
        val g = gate()
        g.begin(0)
        assertEquals(calibrating, g.update(2_000, true, unstable))
        g.enabled = false
        assertEquals(ready, g.update(2_008, true, unstable))
    }

    @Test
    fun staysReadyOnceOpenAndBeginRearmsIt() {
        val g = gate(waitMs = 1_000)
        g.begin(0)
        assertEquals(ready, g.update(1_000, false, -1))
        assertEquals(ready, g.update(1_008, true, unstable))
        g.begin(5_000)
        assertEquals(calibrating, g.phase)
        assertEquals(calibrating, g.update(5_500, false, -1))
    }

    @Test
    fun remainingTimeCountsDownAndIsZeroWhenReady() {
        val g = gate()
        g.begin(1_000)
        assertEquals(10_000L, g.remainingMs(1_000))
        assertEquals(4_000L, g.remainingMs(7_000))
        assertEquals(0L, g.remainingMs(20_000))
        g.update(11_000, false, -1)
        assertEquals(0L, g.remainingMs(11_000))
    }

    @Test
    fun zeroWaitWithStableStatusOpensAfterTheHoldOnly() {
        val g = gate(waitMs = 0)
        g.begin(0)
        assertEquals(calibrating, g.update(0, true, stable))
        assertEquals(ready, g.update(500, true, stable))
    }
}
