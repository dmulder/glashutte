package io.github.dmulder.glashutte.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlatAppsTest {

    private val netflix = FlatAppEntry("com.netflix.mediaclient", "Netflix")
    private val prime = FlatAppEntry("com.amazon.avod.thirdpartyclient", "Prime Video", FlatMode.MIRROR)
    private val base = FlatAppState(listOf(netflix, prime))

    @Test fun parseReadsPackageLabelAndMode() {
        val parsed = FlatAppsFormat.parse(
            """
            # comment
            com.netflix.mediaclient|Netflix
            com.example.mirrorme|Mirror Me|mirror   # trailing comment
            com.example.nolabel
            not a package|Bad
            com.example.nolabel|Duplicate
            """.trimIndent(),
        )
        assertEquals(listOf("com.netflix.mediaclient", "com.example.mirrorme", "com.example.nolabel"), parsed.map { it.packageName })
        assertEquals(FlatMode.MIRROR, parsed[1].mode)
        assertNull(parsed[0].mode)
        assertEquals("com.example.nolabel", parsed[2].label)
    }

    @Test fun shippedDefaultsFileParsesAndCoversTheMainServices() {
        val file = listOf("src/main/assets/flat_apps_default.txt", "glasses/src/main/assets/flat_apps_default.txt")
            .map { File(it) }.first { it.exists() }
        val entries = FlatAppsFormat.parse(file.readText())
        val packages = entries.map { it.packageName }.toSet()
        listOf(
            "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
            "com.hulu.plus", "com.peacocktv.peacockandroid", "com.cbs.app", "com.crunchyroll.crunchyroid",
            "com.vidangel", "com.apple.atve.androidtv.appletv", "com.google.android.apps.youtube.unplugged",
        ).forEach { assertTrue("$it missing from defaults", it in packages) }
        assertEquals("package names must be unique", entries.size, packages.size)
    }

    @Test fun effectiveListIsDefaultsMinusRemovedPlusAdded() {
        val state = FlatAppResolver.withAdded(FlatAppResolver.withRemoved(base, netflix.packageName), FlatAppEntry("com.new.app", "New"))
        assertEquals(listOf("com.amazon.avod.thirdpartyclient", "com.new.app"), FlatAppResolver.effective(state).map { it.packageName })
        assertFalse(FlatAppResolver.isFlat(state, netflix.packageName))
        assertTrue(FlatAppResolver.isFlat(state, "com.new.app"))
    }

    @Test fun readdingARemovedDefaultBringsItBack() {
        val removed = FlatAppResolver.withRemoved(base, netflix.packageName)
        val back = FlatAppResolver.withAdded(removed, netflix)
        assertTrue(FlatAppResolver.isFlat(back, netflix.packageName))
    }

    @Test fun modeFallsBackToGlobalDefaultUnlessSet() {
        assertEquals(FlatMode.FLAT_TRACKPAD, FlatAppResolver.modeFor(base, netflix.packageName, FlatMode.FLAT_TRACKPAD))
        assertEquals(FlatMode.MIRROR, FlatAppResolver.modeFor(base, netflix.packageName, FlatMode.MIRROR))
        assertEquals(FlatMode.MIRROR, FlatAppResolver.modeFor(base, prime.packageName, FlatMode.FLAT_TRACKPAD))
        val overridden = FlatAppResolver.withMode(base, prime.packageName, FlatMode.FLAT_TRACKPAD)
        assertEquals(FlatMode.FLAT_TRACKPAD, FlatAppResolver.modeFor(overridden, prime.packageName, FlatMode.MIRROR))
    }

    @Test fun unknownPackageIsNotFlat() {
        assertNull(FlatAppResolver.find(base, "com.other.app"))
        assertNotNull(FlatAppResolver.find(base, netflix.packageName))
    }

    @Test fun packageNameValidation() {
        assertTrue(FlatAppsFormat.isPackageName("com.vidangel"))
        assertFalse(FlatAppsFormat.isPackageName("vidangel"))
        assertFalse(FlatAppsFormat.isPackageName("com.bad name"))
        assertFalse(FlatAppsFormat.isPackageName(""))
    }
}
