package com.vibe.build.engine

import com.vibe.build.engine.internal.BuildWorkspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuildWorkspaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `dexOrdinal parses secondary dex indices`() {
        assertEquals(2, BuildWorkspace.dexOrdinal("classes2.dex"))
        assertEquals(10, BuildWorkspace.dexOrdinal("classes10.dex"))
        assertEquals(123, BuildWorkspace.dexOrdinal("classes123.dex"))
    }

    @Test
    fun `dexOrdinal sorts unparseable names last instead of throwing`() {
        assertEquals(Int.MAX_VALUE, BuildWorkspace.dexOrdinal("unexpected.dex"))
    }

    /**
     * Regression: sorting by name puts `classes10.dex` before `classes2.dex`, and
     * AndroidApkBuilder renames by position, so the dex contents would be shuffled
     * once a build produces ten or more secondary dex files.
     */
    @Test
    fun `additionalDexFiles orders numerically beyond nine`() {
        val binDir = temp.newFolder("bin")
        // Created out of order so the result cannot come from directory ordering.
        listOf(12, 2, 10, 3, 9, 11, 4, 5, 6, 7, 8).forEach {
            File(binDir, "classes$it.dex").writeText("")
        }
        File(binDir, "classes.dex").writeText("")

        val names = workspaceWithBin(binDir).additionalDexFiles().map { it.name }

        assertEquals((2..12).map { "classes$it.dex" }, names)
    }

    @Test
    fun `additionalDexFiles excludes the primary dex`() {
        val binDir = temp.newFolder("bin")
        File(binDir, "classes.dex").writeText("")
        File(binDir, "classes2.dex").writeText("")

        assertEquals(listOf("classes2.dex"), workspaceWithBin(binDir).additionalDexFiles().map { it.name })
    }

    /** Only [BuildWorkspace.binDir] matters here; every other path is a placeholder. */
    private fun workspaceWithBin(binDir: File): BuildWorkspace {
        val stub = File(temp.root, "stub")
        return BuildWorkspace(
            rootDir = stub,
            sourceDir = stub,
            resDir = stub,
            assetsDir = stub,
            nativeLibsDir = stub,
            javaResourcesDir = stub,
            manifestFile = stub,
            buildDir = stub,
            binDir = binDir,
            generatedSourcesDir = stub,
            classesDir = stub,
            compiledResZip = stub,
            resourcePackage = stub,
            rTxtFile = stub,
            unsignedApk = stub,
            signedApk = stub,
            bootstrapJar = stub,
            lambdaStubsJar = stub,
            libraries = emptyList(),
        )
    }
}
