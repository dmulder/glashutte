package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test

class StubGlassesBackendTest {

    @Test
    fun stubNeverOpensASession() {
        val stub = StubGlassesBackend()
        assertFalse(stub.isSdkBacked)
        assertNull(stub.open(0x1104, 42, SessionOptions()))
    }

    @Test
    fun defaultBackendIsStubWhenSdkIsAbsent() {
        assumeFalse("built with the VITURE SDK", GlassesBackends.sdkAvailable)
        val backend = GlassesBackends.default()
        assertEquals("stub", backend.name)
        assertNull(backend.open(0x1104, 42, SessionOptions(sixDof = true)))
    }
}
