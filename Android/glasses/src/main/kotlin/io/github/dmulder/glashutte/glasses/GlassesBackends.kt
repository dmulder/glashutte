package io.github.dmulder.glashutte.glasses

/**
 * Picks the [GlassesBackend] for this build.
 *
 * Which `SdkBackendProvider` is compiled in is decided by the `:glasses` Gradle build: the
 * real one when the VITURE SDK was found locally, otherwise one that returns `null`.
 */
object GlassesBackends {

    /** True when this build was compiled against the VITURE SDK. */
    val sdkAvailable: Boolean get() = SdkBackendProvider.create() != null

    /** The SDK-backed backend if this build has one, else a [StubGlassesBackend]. */
    fun default(): GlassesBackend = SdkBackendProvider.create() ?: StubGlassesBackend()
}
