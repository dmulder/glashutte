package io.github.dmulder.glashutte.glasses

import io.github.dmulder.glashutte.glasses.DrmVerdict.DrmSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmVerdictTest {

    private fun input(
        level: String? = "L1",
        connected: Int = DrmVerdict.HDCP_V2_2,
        flags: Int? = DrmVerdict.DISPLAY_FLAG_SECURE or DrmVerdict.DISPLAY_FLAG_SUPPORTS_PROTECTED_BUFFERS,
        separate: Boolean = true,
    ) = DrmVerdict.Input(level, connected, DrmVerdict.HDCP_V2_2, flags, separate)

    private fun severities(i: DrmVerdict.Input) = DrmVerdict.evaluate(i).map { it.severity }

    @Test fun fullyCapableSetupIsAllGood() {
        assertTrue(severities(input()).all { it == DrmSeverity.GOOD })
    }

    @Test fun widevineL3IsAWarning() {
        assertEquals(DrmSeverity.WARN, severities(input(level = "L3")).first())
    }

    @Test fun missingMediaDrmIsBad() {
        assertEquals(DrmSeverity.BAD, severities(input(level = null)).first())
    }

    @Test fun noHdcpOnTheLinkIsBad() {
        assertTrue(DrmSeverity.BAD in severities(input(connected = DrmVerdict.HDCP_NONE)))
    }

    @Test fun displayWithoutSecureFlagIsAWarningNotAFailure() {
        val s = severities(input(flags = 0))
        assertTrue(DrmSeverity.WARN in s)
        assertTrue(DrmSeverity.BAD !in s)
    }

    @Test fun noGlassesStopsAfterWidevineWithAnInfoLine() {
        val s = severities(input(flags = null))
        assertEquals(listOf(DrmSeverity.GOOD, DrmSeverity.INFO), s)
    }

    @Test fun namesAreHumanReadable() {
        assertEquals("SUPPORTS_PROTECTED_BUFFERS|SECURE", DrmVerdict.displayFlagNames(3))
        assertEquals("none", DrmVerdict.displayFlagNames(0))
        assertTrue(DrmVerdict.hdcpName(DrmVerdict.HDCP_NONE).startsWith("none"))
    }
}
