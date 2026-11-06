package io.github.dmulder.glashutte.glasses

/**
 * The backend used when no vendor SDK was supplied at build time.
 *
 * It never opens a session, so [HeadTracking] reports "no tracker" and the workspace falls
 * back to PINNED view on whatever external display the glasses present. The rest of the app
 * (desktop, windows, input, privileged helper) is unaffected.
 */
class StubGlassesBackend(
    private val reason: String = "VITURE SDK not supplied at build time",
) : GlassesBackend {

    override val name: String = "stub"
    override val isSdkBacked: Boolean = false

    override fun open(productId: Int, usbFd: Int, options: SessionOptions): GlassesSession? {
        GlassesDiagnostics.i(TAG, "stub backend: not opening pid=0x${productId.toString(16)} ($reason)")
        return null
    }

    private companion object {
        const val TAG = "Glashutte/StubGlasses"
    }
}
