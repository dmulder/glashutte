package io.github.dmulder.glashutte.glasses

/** Compiled when the VITURE SDK is not present: there is no SDK-backed backend. */
internal object SdkBackendProvider {
    fun create(): GlassesBackend? = null
}
