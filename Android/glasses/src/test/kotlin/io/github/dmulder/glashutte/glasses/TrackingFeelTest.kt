package io.github.dmulder.glashutte.glasses

import io.github.dmulder.glashutte.glasses.TrackingFeel.DofMode
import io.github.dmulder.glashutte.glasses.TrackingFeel.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingFeelTest {

    @Test
    fun defaultsAreConservativeAboutDof() {
        val s = Snapshot()
        assertEquals(DofMode.APP_SETTING, s.dofMode)
        assertFalse("app Settings default is 3DoF", s.wantsSixDof(appSettingSixDof = false))
        assertTrue(s.deliversPosition)
        assertEquals(NeckPivot.DEFAULT_OFFSET_M, s.neckOffsetM, 0f)
        assertEquals(10, s.calibrationWaitSec)
    }

    @Test
    fun appSettingModeFollowsTheSettingsToggle() {
        val s = Snapshot(dofMode = DofMode.APP_SETTING)
        assertTrue(s.wantsSixDof(true))
        assertFalse(s.wantsSixDof(false))
    }

    @Test
    fun explicitModesIgnoreTheSettingsToggle() {
        assertFalse(Snapshot(dofMode = DofMode.CARINA_3DOF).wantsSixDof(true))
        assertTrue(Snapshot(dofMode = DofMode.CARINA_6DOF).wantsSixDof(false))
        assertTrue(Snapshot(dofMode = DofMode.CARINA_6DOF_ORIENTATION_ONLY).wantsSixDof(false))
    }

    @Test
    fun onlyOrientationOnlyModeWithholdsPosition() {
        assertTrue(Snapshot(dofMode = DofMode.CARINA_6DOF).deliversPosition)
        assertTrue(Snapshot(dofMode = DofMode.CARINA_3DOF).deliversPosition)
        assertFalse(Snapshot(dofMode = DofMode.CARINA_6DOF_ORIENTATION_ONLY).deliversPosition)
    }

    @Test
    fun summaryNamesEveryActiveSetting() {
        val text = Snapshot(
            neckPivotEnabled = false, neckOffsetM = 0.2f, waitForCalibration = true,
            calibrationWaitSec = 7, dofMode = DofMode.CARINA_6DOF,
        ).summary()
        assertTrue(text, text.contains("neckPivot=off"))
        assertTrue(text, text.contains("20.0cm"))
        assertTrue(text, text.contains("waitForCalibration=on(7s)"))
        assertTrue(text, text.contains("dofMode=CARINA_6DOF"))
    }
}
