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

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// No bundle currently pulls Kotlin. When the AAR bundles land (CameraX, ML Kit GenAI) they
// will, and a "kotlin-runtime" bundle of kotlin-stdlib 2.3.21 + kotlinx-coroutines 1.10.1
// (~2.9 MB) becomes a prerequisite. The assertion at the end of the task keeps every bundle
// on a single kotlin-stdlib version once that happens.

data class JarBundle(
    /** Matches BundledLibrary.id. */
    val id: String,
    /** Asset file name; must equal BundledLibrary.classesAsset. */
    val assetName: String,
    val coordinates: List<String>,
)

val jarBundles = listOf(
    JarBundle(
        id = "retrofit",
        assetName = "retrofit.jar.zip",
        coordinates = listOf(
            "com.squareup.retrofit2:retrofit:2.9.0",
            "com.squareup.retrofit2:converter-moshi:2.9.0",
            "com.squareup.moshi:moshi:1.9.3",
            "com.squareup.okhttp3:okhttp:3.14.9",
            "com.squareup.okio:okio:1.17.2",
        ),
    ),
)

/** Entries that make D8 fail or that duplicate across jars. */
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

fun mergeJars(inputs: List<File>, output: File, logger: org.gradle.api.logging.Logger) {
    output.parentFile.mkdirs()
    output.delete()
    val seen = mutableSetOf<String>()
    val duplicates = mutableListOf<String>()
    ZipOutputStream(output.outputStream().buffered()).use { out ->
        inputs.forEach { jar ->
            ZipInputStream(jar.inputStream().buffered()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    val name = entry.name
                    if (isDroppableEntry(name)) return@forEach
                    if (!seen.add(name)) {
                        // First jar wins, matching the classpath order in the catalog.
                        if (name.endsWith(".class")) duplicates += "$name (from ${jar.name})"
                        return@forEach
                    }
                    out.putNextEntry(ZipEntry(name))
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

/** Wraps [jar] in a zip whose single entry is the jar under its own name. */
fun zipAsset(jar: File, assetFile: File) {
    assetFile.parentFile.mkdirs()
    assetFile.delete()
    ZipOutputStream(assetFile.outputStream().buffered()).use { out ->
        out.putNextEntry(ZipEntry(jar.name))
        jar.inputStream().buffered().use { it.copyTo(out) }
        out.closeEntry()
    }
}

fun mb(file: File): String = String.format("%.2f MB", file.length() / 1048576.0)


tasks.register("generateBundledLibraryAssets") {
    group = "bundled libraries"
    description = "Regenerates src/main/assets jars for the bundled library catalog (needs network)."

    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val workRoot = layout.buildDirectory.dir("bundled-libs").get().asFile

    doLast {
        val resolvedKotlinVersions = mutableSetOf<String>()

        jarBundles.forEach { bundle ->
            logger.lifecycle("Bundle '${bundle.id}' -> ${bundle.assetName}")
            val config = configurations.detachedConfiguration(
                *bundle.coordinates.map { dependencies.create(it) }.toTypedArray(),
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

            require(jars.isNotEmpty()) { "Bundle '${bundle.id}' resolved no jars" }
            logger.lifecycle("  ${jars.size} jars, ${String.format("%.2f MB", jars.sumOf { it.length() } / 1048576.0)} raw")

            val work = File(workRoot, bundle.id).apply { deleteRecursively(); mkdirs() }
            val extractedName = bundle.assetName.removeSuffix(".zip")
            val merged = File(work, extractedName)
            mergeJars(jars, merged, logger)
            logger.lifecycle("  merged -> ${mb(merged)}")

            val asset = File(assetsDir, bundle.assetName)
            zipAsset(merged, asset)
            logger.lifecycle("  asset ${asset.name} ${mb(asset)}")
        }

        require(resolvedKotlinVersions.size <= 1) {
            "Bundles resolved multiple kotlin-stdlib versions $resolvedKotlinVersions; " +
                "generated apps must carry exactly one Kotlin runtime"
        }
        logger.lifecycle("kotlin-stdlib versions resolved: ${resolvedKotlinVersions.ifEmpty { setOf("none") }}")
    }
}
