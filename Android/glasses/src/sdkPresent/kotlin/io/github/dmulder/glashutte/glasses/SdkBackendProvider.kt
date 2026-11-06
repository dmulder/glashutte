package io.github.dmulder.glashutte.glasses

/** Compiled when the VITURE SDK was found at build time. */
internal object SdkBackendProvider {
    private val backend: GlassesBackend by lazy { VitureGlassesBackend() }

    fun create(): GlassesBackend? = backend
}
