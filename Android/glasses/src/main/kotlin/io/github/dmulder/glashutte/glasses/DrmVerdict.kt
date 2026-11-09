package io.github.dmulder.glashutte.glasses

/**
 * Turns the raw DRM probe numbers into plain statements about what the glasses can show.
 * Pure (no Android types) so the interpretation is unit-tested; [DrmProbe] feeds it.
 */
object DrmVerdict {

    // Values of MediaDrm.HDCP_*.
    const val HDCP_LEVEL_UNKNOWN = 0
    const val HDCP_NONE = 1
    const val HDCP_V1 = 2
    const val HDCP_V2 = 3
    const val HDCP_V2_1 = 4
    const val HDCP_V2_2 = 5
    const val HDCP_V2_3 = 6
    const val HDCP_NO_DIGITAL_OUTPUT = Int.MAX_VALUE

    // Values of Display.FLAG_*.
    const val DISPLAY_FLAG_SUPPORTS_PROTECTED_BUFFERS = 1 shl 0
    const val DISPLAY_FLAG_SECURE = 1 shl 1

    fun hdcpName(level: Int): String = when (level) {
        HDCP_LEVEL_UNKNOWN -> "unknown"
        HDCP_NONE -> "none (no HDCP on the link)"
        HDCP_V1 -> "HDCP 1.x"
        HDCP_V2 -> "HDCP 2.0"
        HDCP_V2_1 -> "HDCP 2.1"
        HDCP_V2_2 -> "HDCP 2.2"
        HDCP_V2_3 -> "HDCP 2.3"
        HDCP_NO_DIGITAL_OUTPUT -> "no digital output"
        else -> "level $level"
    }

    fun displayFlagNames(flags: Int): String {
        val names = ArrayList<String>()
        if (flags and DISPLAY_FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) names += "SUPPORTS_PROTECTED_BUFFERS"
        if (flags and DISPLAY_FLAG_SECURE != 0) names += "SECURE"
        if (flags and (1 shl 2) != 0) names += "PRIVATE"
        if (flags and (1 shl 3) != 0) names += "PRESENTATION"
        if (flags and (1 shl 4) != 0) names += "ROUND"
        if (flags and (1 shl 5) != 0) names += "CAN_SHOW_WITH_INSECURE_KEYGUARD"
        if (flags and (1 shl 6) != 0) names += "TRUSTED"
        return if (names.isEmpty()) "none" else names.joinToString("|")
    }

    data class Input(
        /** "L1", "L3", other string, or null when MediaDrm could not be opened. */
        val widevineLevel: String?,
        val connectedHdcp: Int,
        val maxHdcp: Int,
        /** Flags of the glasses' display, or null when no glasses display is connected. */
        val glassesDisplayFlags: Int?,
        val glassesIsSeparateFromPhone: Boolean,
    )

    class Statement(val severity: DrmSeverity, val text: String)

    enum class DrmSeverity { GOOD, WARN, BAD, INFO }

    fun evaluate(input: Input): List<Statement> {
        val out = ArrayList<Statement>()
        val level = input.widevineLevel
        when {
            level == null ->
                out += Statement(DrmSeverity.BAD, "Widevine: MediaDrm could not be opened, so protected video cannot play at all on this device.")
            level == "L1" ->
                out += Statement(DrmSeverity.GOOD, "Widevine L1: hardware-protected video is available (streaming apps can offer HD).")
            else ->
                out += Statement(
                    DrmSeverity.WARN,
                    "Widevine $level: software-only DRM. Streaming apps cap quality (usually SD) and some still mark their video surface secure.",
                )
        }
        if (input.glassesDisplayFlags == null) {
            out += Statement(DrmSeverity.INFO, "No glasses display is connected, so the glasses' secure-display state was not measured. Connect them and run again.")
            return out
        }
        val flags = input.glassesDisplayFlags
        val secure = flags and DISPLAY_FLAG_SECURE != 0
        val protectedBuffers = flags and DISPLAY_FLAG_SUPPORTS_PROTECTED_BUFFERS != 0
        out += when {
            secure && protectedBuffers ->
                Statement(DrmSeverity.GOOD, "Glasses display reports FLAG_SECURE + PROTECTED_BUFFERS: Android treats it as able to show secure, protected video natively.")
            secure ->
                Statement(DrmSeverity.GOOD, "Glasses display reports FLAG_SECURE: secure windows are allowed on it.")
            else ->
                Statement(
                    DrmSeverity.WARN,
                    "Glasses display does NOT report FLAG_SECURE. Android may only mark an external display secure while HDCP is active, " +
                        "so this can be normal until a protected video is actually playing; the playback test settles it.",
                )
        }
        val connected = input.connectedHdcp
        out += when {
            connected == HDCP_NO_DIGITAL_OUTPUT ->
                Statement(DrmSeverity.INFO, "HDCP: no digital output is connected (as seen by MediaDrm).")
            connected >= HDCP_V1 ->
                Statement(DrmSeverity.GOOD, "HDCP: connected link negotiated ${hdcpName(connected)} (max supported ${hdcpName(input.maxHdcp)}). L1 playback on the glasses is possible.")
            connected == HDCP_NONE ->
                Statement(DrmSeverity.BAD, "HDCP: the connected display has NO HDCP. Protected video will be blocked or downgraded on the glasses even when flat; no software change fixes that.")
            else ->
                Statement(DrmSeverity.WARN, "HDCP: connected level unknown (${hdcpName(connected)}); run the playback test with the glasses attached.")
        }
        if (!input.glassesIsSeparateFromPhone) {
            out += Statement(DrmSeverity.INFO, "The glasses display looks like a mirror of the phone, not an independent display.")
        }
        return out
    }
}
