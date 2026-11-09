package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureLayerVerdictTest {
    private val normal = 0xFFE53935.toInt()
    private val secure = 0xFF1E88E5.toInt()

    @Test fun secureVisible() =
        assertEquals(SecureLayerVerdict.Outcome.SECURE_VISIBLE, SecureLayerVerdict.classify(normal, secure, normal, secure))

    @Test fun secureBlanked() =
        assertEquals(SecureLayerVerdict.Outcome.SECURE_BLANKED, SecureLayerVerdict.classify(normal, 0xFF000000.toInt(), normal, secure))

    @Test fun normalMissing() =
        assertEquals(SecureLayerVerdict.Outcome.NORMAL_MISSING, SecureLayerVerdict.classify(0xFF000000.toInt(), 0xFF000000.toInt(), normal, secure))

    @Test fun otherColoursAreInconclusive() =
        assertEquals(SecureLayerVerdict.Outcome.INCONCLUSIVE, SecureLayerVerdict.classify(normal, 0xFF00FF00.toInt(), normal, secure))
}
