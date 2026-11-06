package io.github.dmulder.glashutte.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaDrm
import android.media.MediaCodecInfo
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import java.util.UUID

/**
 * Read-only DRM and secure-display probe, in the app's own process. It only asks the platform
 * what it supports: Widevine level, HDCP, secure decoders, and how each display describes
 * itself. The shell-helper half (can the helper create a secure display, hold
 * CAPTURE_SECURE_VIDEO_OUTPUT, desktop/XR flags, SurfaceFlinger HDCP lines) is gathered by the
 * app module and passed in as [helperSection].
 *
 * Output goes to the Diagnostics screen, the exported log, and logcat under `GlashutteDiag`.
 * Nothing here captures, decrypts or routes protected content.
 */
object DrmProbe {

    private const val TAG = GlassesDiagnostics.TAG
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    @Volatile var lastReport: String = "(DRM probe not run yet; tap 'Run DRM probe')"
        private set

    @Volatile var lastSummary: String = "not run"
        private set

    class Result(val text: String, val summary: String)

    fun run(context: Context, helperSection: String?): Result {
        val sb = StringBuilder()
        sb.append("=== DRM / secure-video probe ===\n")
        sb.append("time=").append(java.time.OffsetDateTime.now()).append('\n')
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
            .append(" build=").append(Build.ID).append(" device=").append(Build.MODEL).append('\n')

        val drm = probeWidevine(sb)
        probeSecureDecoders(sb)

        val displays = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays
        sb.append("\n-- Displays as Android describes them (FLAG_SECURE / protected-buffer support) --\n")
        displays.forEach { describeDisplay(sb, it) }

        val glasses = GlassesDisplay.find(context)
        val glassesFlags = glasses?.flags
        val separate = glasses != null && glasses.displayId != Display.DEFAULT_DISPLAY
        sb.append("\nglasses display: ")
            .append(glasses?.let { "id=${it.displayId} '${it.name}' flags=${DrmVerdict.displayFlagNames(it.flags)}" } ?: "none connected")
            .append('\n')

        val statements = DrmVerdict.evaluate(
            DrmVerdict.Input(
                widevineLevel = drm.securityLevel,
                connectedHdcp = drm.connectedHdcp,
                maxHdcp = drm.maxHdcp,
                glassesDisplayFlags = glassesFlags,
                glassesIsSeparateFromPhone = separate,
            ),
        )
        sb.append("\n-- What this means --\n")
        statements.forEach { sb.append('[').append(it.severity.name).append("] ").append(it.text).append('\n') }

        if (glasses != null) {
            val metrics = DisplayMetrics().also { glasses.getRealMetrics(it) }
            sb.append("\n-- Flat-mode fit of the glasses display --\n")
            FlatFit.analyze(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                .forEach { sb.append(it.line()).append('\n') }
        }

        if (!helperSection.isNullOrBlank()) {
            sb.append("\n-- Shell helper (uid 2000) view --\n").append(helperSection.trimEnd()).append('\n')
        } else {
            sb.append("\n-- Shell helper (uid 2000) view --\n(helper not connected: pair it from the Glashutte screen, then run again)\n")
        }

        val summary = buildString {
            append("Widevine ").append(drm.securityLevel ?: "unavailable")
            append(" | HDCP connected ").append(DrmVerdict.hdcpName(drm.connectedHdcp))
            append(" | glasses ")
            append(glassesFlags?.let { DrmVerdict.displayFlagNames(it) } ?: "not connected")
        }
        statements.forEach { logStatement(it) }
        GlassesDiagnostics.i(TAG, "DRM-PROBE summary: $summary")
        lastReport = sb.toString()
        lastSummary = summary
        return Result(lastReport, summary)
    }

    private fun logStatement(statement: DrmVerdict.Statement) =
        GlassesDiagnostics.i(TAG, "DRM-PROBE ${statement.severity.name}: ${statement.text}")

    private class DrmFacts(val securityLevel: String?, val connectedHdcp: Int, val maxHdcp: Int)

    private fun probeWidevine(sb: StringBuilder): DrmFacts {
        sb.append("\n-- Widevine (MediaDrm) --\n")
        val supported = runCatching { MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID) }.getOrDefault(false)
        sb.append("scheme supported: ").append(supported).append('\n')
        if (!supported) return DrmFacts(null, DrmVerdict.HDCP_LEVEL_UNKNOWN, DrmVerdict.HDCP_LEVEL_UNKNOWN)
        var level: String? = null
        var connected = DrmVerdict.HDCP_LEVEL_UNKNOWN
        var max = DrmVerdict.HDCP_LEVEL_UNKNOWN
        val drm = try {
            MediaDrm(WIDEVINE_UUID)
        } catch (t: Throwable) {
            sb.append("MediaDrm open failed: ").append(t).append('\n')
            return DrmFacts(null, connected, max)
        }
        try {
            for (name in listOf(
                "vendor", "version", "description", "securityLevel", "systemId", "algorithms",
                "usageReportingSupport", "maxNumberOfSessions", "numberOfOpenSessions",
                "hdcpLevel", "maxHdcpLevel",
            )) {
                val v = runCatching { drm.getPropertyString(name) }.getOrElse { "(unreadable: ${it.javaClass.simpleName})" }
                sb.append(name).append(": ").append(v).append('\n')
                if (name == "securityLevel") level = v
            }
            runCatching { connected = drm.connectedHdcpLevel }.onFailure { sb.append("connectedHdcpLevel: unreadable ($it)\n") }
            runCatching { max = drm.maxHdcpLevel }.onFailure { sb.append("maxHdcpLevel: unreadable ($it)\n") }
            sb.append("MediaDrm.getConnectedHdcpLevel: ").append(DrmVerdict.hdcpName(connected)).append(" ($connected)\n")
            sb.append("MediaDrm.getMaxHdcpLevel: ").append(DrmVerdict.hdcpName(max)).append(" ($max)\n")
        } finally {
            runCatching { drm.close() }
        }
        return DrmFacts(level, connected, max)
    }

    private fun probeSecureDecoders(sb: StringBuilder) {
        sb.append("\n-- Secure video decoders (MediaCodec, FEATURE_SecurePlayback) --\n")
        val found = ArrayList<String>()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .forEach { info ->
                    info.supportedTypes.filter { it.startsWith("video/") }.forEach { type ->
                        val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@forEach
                        if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) {
                            found += "${info.name} ($type)"
                        }
                    }
                }
        }.onFailure { sb.append("(codec list unreadable: $it)\n") }
        if (found.isEmpty()) sb.append("none: no hardware secure decoder, so only software DRM (L3) playback is possible\n")
        else found.forEach { sb.append(it).append('\n') }
    }

    private fun describeDisplay(sb: StringBuilder, d: Display) {
        val metrics = DisplayMetrics().also { d.getRealMetrics(it) }
        sb.append("id=").append(d.displayId).append(" '").append(d.name).append("' ")
            .append(metrics.widthPixels).append('x').append(metrics.heightPixels)
            .append(" @").append(metrics.densityDpi).append("dpi ")
            .append("%.0fHz".format(d.refreshRate))
            .append(" state=").append(d.state)
            .append(" aspect=").append("%.3f".format(FlatFit.aspect(metrics.widthPixels, metrics.heightPixels)))
            .append('\n')
        sb.append("    flags=0x").append(Integer.toHexString(d.flags)).append(' ')
            .append(DrmVerdict.displayFlagNames(d.flags)).append('\n')
        sb.append("    FLAG_SECURE=").append(d.flags and Display.FLAG_SECURE != 0)
            .append(" FLAG_SUPPORTS_PROTECTED_BUFFERS=").append(d.flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0)
            .append(" isHdr=").append(runCatching { d.isHdr }.getOrDefault(false))
            .append('\n')
        val hidden = hiddenReadouts(d)
        if (hidden.isNotEmpty()) sb.append("    hidden getters: ").append(hidden).append('\n')
    }

    /**
     * Zero-argument Display getters that are not public API but whose names say HDCP, secure or
     * protected (Android 17 added HDCP-aware external display state). Reflection can be denied
     * by the hidden-API policy; whatever it returns, including "denied", is evidence.
     */
    private fun hiddenReadouts(d: Display): String {
        val out = ArrayList<String>()
        val methods = runCatching { Display::class.java.declaredMethods }.getOrDefault(emptyArray())
        methods.filter { m ->
            m.parameterCount == 0 &&
                (m.name.contains("hdcp", true) || m.name.contains("secure", true) || m.name.contains("protected", true) ||
                    m.name == "getType" || m.name == "isTrusted")
        }.sortedBy { it.name }.forEach { m ->
            val v = try {
                m.isAccessible = true
                m.invoke(d)?.toString()
            } catch (t: Throwable) {
                "denied(${(t.cause ?: t).javaClass.simpleName})"
            }
            out += "${m.name}=$v"
        }
        return out.joinToString(" ").ifEmpty { "(none visible to this app)" }
    }
}
