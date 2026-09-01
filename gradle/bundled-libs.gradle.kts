// Dev-time generation of the bundled library assets consumed by BundledLibraries.
//
//   ./gradlew :build-engine:generateBundledLibraryAssets
//
// Deliberately NOT wired into assembleDebug: it needs network access, and its outputs
// are committed binaries. Run it when a bundle's coordinates change, then commit the
// regenerated assets.
//
// Each bundle resolves a set of Maven coordinates, merges them into one jar, and writes it
// into src/main/assets as a zip whose single entry is the jar — matching the extraction
// contract in BuildModule.getBundledFile.
//
// Only plain-JVM (jar) coordinates are handled here. AAR bundles (Jetpack, ML Kit) need
// resource precompilation and manifest merging on top of this, and are not yet covered.
// A bundle whose transitive weight needs shrinking would also need an R8 pass here; no
// current bundle does, so that step is deliberately absent rather than untested.
//
// NOTE: this script lives in gradle/ rather than beside build-engine/build.gradle.kts on
// purpose. Any extra .gradle.kts inside an Android module's directory crashes lint's
// build-script visitor — `findFirCompiledSymbol only works on compiled declarations`, via
// SymbolLightClassForScript.getOwnFields — and fails :build-engine:lintAnalyzeDebug.
// Keeping it under the root project's gradle/ avoids that; declarations are kept
// task-local for the same reason.

tasks.register("generateBundledLibraryAssets") {
    group = "bundled libraries"
    description = "Regenerates src/main/assets jars for the bundled library catalog (needs network)."

    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val workRoot = layout.buildDirectory.dir("bundled-libs").get().asFile

    doLast {
        // id (matches BundledLibrary.id) to asset name (matches classesAsset) to coordinates.
        //
        // No bundle currently pulls Kotlin. When the AAR bundles land (CameraX, ML Kit
        // GenAI) they will, and a "kotlin-runtime" bundle of kotlin-stdlib 2.3.21 +
        // kotlinx-coroutines 1.10.1 (~2.9 MB) becomes a prerequisite. The assertion at the
        // end keeps every bundle on a single kotlin-stdlib version once that happens.
        val bundles = listOf(
            Triple(
                "retrofit",
                "retrofit.jar.zip",
                listOf(
                    "com.squareup.retrofit2:retrofit:2.9.0",
                    "com.squareup.retrofit2:converter-moshi:2.9.0",
                    "com.squareup.moshi:moshi:1.9.3",
                    "com.squareup.okhttp3:okhttp:3.14.9",
                    "com.squareup.okio:okio:1.17.2",
                ),
            ),
        )

        // Entries that make D8 fail or that duplicate across jars.
        fun isDroppableEntry(name: String): Boolean =
            name == "module-info.class" ||
                name.endsWith("/module-info.class") ||
                name.startsWith("META-INF/versions/") ||
                name.endsWith(".SF") ||
                name.endsWith(".DSA") ||
                name.endsWith(".RSA") ||
                name == "META-INF/MANIFEST.MF" ||
                name.startsWith("META-INF/maven/") ||
                name.endsWith("/")

        // These jars are committed, so byte-stable output keeps regeneration from
        // producing a diff when nothing actually changed. 1980-01-01 is the zip epoch.
        fun entry(name: String): java.util.zip.ZipEntry =
            java.util.zip.ZipEntry(name).apply { time = 315532800000L }

        fun mergeJars(inputs: List<java.io.File>, output: java.io.File) {
            output.parentFile.mkdirs()
            output.delete()
            val seen = mutableSetOf<String>()
            val duplicates = mutableListOf<String>()
            java.util.zip.ZipOutputStream(output.outputStream().buffered()).use { out ->
                inputs.forEach { jar ->
                    java.util.zip.ZipInputStream(jar.inputStream().buffered()).use { input ->
                        generateSequence { input.nextEntry }.forEach { entry ->
                            val name = entry.name
                            if (isDroppableEntry(name)) return@forEach
                            if (!seen.add(name)) {
                                // First jar wins, matching the catalog's classpath order.
                                if (name.endsWith(".class")) duplicates += "$name (from ${jar.name})"
                                return@forEach
                            }
                            out.putNextEntry(entry(name))
                            input.copyTo(out)
                            out.closeEntry()
                        }
                    }
                }
            }
            if (duplicates.isNotEmpty()) {
                logger.lifecycle("  ${duplicates.size} duplicate class entries dropped, e.g. ${duplicates.take(3)}")
            }
        }

        // Wraps the jar in a zip whose single entry is the jar under its own name.
        fun zipAsset(jar: java.io.File, assetFile: java.io.File) {
            assetFile.parentFile.mkdirs()
            assetFile.delete()
            java.util.zip.ZipOutputStream(assetFile.outputStream().buffered()).use { out ->
                out.putNextEntry(entry(jar.name))
                jar.inputStream().buffered().use { it.copyTo(out) }
                out.closeEntry()
            }
        }

        fun mb(bytes: Long): String = String.format("%.2f MB", bytes / 1048576.0)


        val resolvedKotlinVersions = mutableSetOf<String>()

        bundles.forEach { (id, assetName, coordinates) ->
            logger.lifecycle("Bundle '$id' -> $assetName")
            val config = configurations.detachedConfiguration(
                *coordinates.map { dependencies.create(it) }.toTypedArray(),
            ).apply { isTransitive = true }

            val jars = config.resolvedConfiguration.resolvedArtifacts
                .onEach { artifact ->
                    if (artifact.moduleVersion.id.group == "org.jetbrains.kotlin" &&
                        artifact.moduleVersion.id.name.startsWith("kotlin-stdlib")
                    ) {
                        resolvedKotlinVersions += artifact.moduleVersion.id.version
                    }
                }
                .map { it.file }
                .filter { it.extension == "jar" }
                .sortedBy { it.name }

            require(jars.isNotEmpty()) { "Bundle '$id' resolved no jars" }
            logger.lifecycle("  ${jars.size} jars, ${mb(jars.sumOf { it.length() })} raw")

            val work = java.io.File(workRoot, id).apply { deleteRecursively(); mkdirs() }
            val merged = java.io.File(work, assetName.removeSuffix(".zip"))
            mergeJars(jars, merged)
            logger.lifecycle("  merged -> ${mb(merged.length())}")

            val asset = java.io.File(assetsDir, assetName)
            zipAsset(merged, asset)
            logger.lifecycle("  asset ${asset.name} ${mb(asset.length())}")
        }

        require(resolvedKotlinVersions.size <= 1) {
            "Bundles resolved multiple kotlin-stdlib versions $resolvedKotlinVersions; " +
                "generated apps must carry exactly one Kotlin runtime"
        }
        logger.lifecycle("kotlin-stdlib versions resolved: ${resolvedKotlinVersions.ifEmpty { setOf("none") }}")
    }
}
