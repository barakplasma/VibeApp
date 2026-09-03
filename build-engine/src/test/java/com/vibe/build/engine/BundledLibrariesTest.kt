package com.vibe.build.engine

import com.vibe.build.engine.internal.BundledLibraries
import com.vibe.build.engine.internal.BundledLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the catalog's shape. [BundledLibraries.resolve] needs an Android context, so
 * these cover the declarative half only — which is where a bad entry actually originates.
 */
class BundledLibrariesTest {

    @Test
    fun `ids are unique`() {
        val ids = BundledLibraries.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `class assets are unique`() {
        val assets = BundledLibraries.ALL.map { it.classesAsset }
        assertEquals(assets.distinct(), assets)
    }

    /** Two entries unzipping to the same path would clobber each other in filesDir. */
    @Test
    fun `extracted names are unique`() {
        val names = BundledLibraries.ALL.map { it.extractedName }
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `extracted name strips only the zip wrapper`() {
        assertEquals("jsoup.jar", BundledLibrary("x", "jsoup.jar.zip").extractedName)
        assertEquals("androidx-classes.jar", BundledLibrary("x", "androidx-classes.jar.zip").extractedName)
        // Raw assets are copied verbatim, so the name is already final.
        assertEquals("shadow-runtime.jar", BundledLibrary("x", "shadow-runtime.jar").extractedName)
    }

    @Test
    fun `compiled resource directory name is derived only when an asset is declared`() {
        assertEquals(
            "androidx-res-compiled",
            BundledLibrary("x", "a.jar.zip", resCompiledAsset = "androidx-res-compiled.zip").resCompiledDirName,
        )
        assertEquals(null, BundledLibrary("x", "a.jar.zip").resCompiledDirName)
    }

    /** An empty or blank entry would produce a malformed `--extra-packages` argument. */
    @Test
    fun `extra packages are non-blank and unique across the catalog`() {
        val all = BundledLibraries.ALL.flatMap { it.extraPackages }
        assertTrue("extra packages must not be blank", all.none { it.isBlank() })
        assertTrue("extra packages must not contain the ':' separator", all.none { it.contains(':') })
        assertEquals(all.distinct(), all)
    }

    /** `--extra-packages` only makes sense for a library that ships resources. */
    @Test
    fun `only libraries with compiled resources declare extra packages`() {
        BundledLibraries.ALL.filter { it.extraPackages.isNotEmpty() }.forEach {
            assertTrue("${it.id} declares extraPackages but no resCompiledAsset", it.resCompiledAsset != null)
        }
    }
}
