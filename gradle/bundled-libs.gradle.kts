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
// Both jar and aar coordinates are handled. An aar contributes its classes.jar to the
// bundle's merged jar, and its res/ to a second asset of AAPT2-precompiled .flat files
// that the build engine passes to `aapt2 link` as -R overlays.
//
// AAR coordinates are resolved artifact-only (`@aar`, non-transitive) on purpose. Most
// Jetpack transitives — appcompat, core, fragment, recyclerview — are already in the
// androidx bundle, and pulling them in again would put duplicate classes in front of D8.
// List every artifact a bundle needs explicitly; the duplicate check below enforces it.
//
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
        // A bundle's `id` and `assetName` must match its BundledLibrary row.
        //
        // No bundle currently pulls Kotlin. When CameraX or ML Kit GenAI land they will,
        // and a "kotlin-runtime" bundle of kotlin-stdlib 2.3.21 + kotlinx-coroutines
        // 1.10.1 (~2.9 MB) becomes a prerequisite. The assertion at the end keeps every
        // bundle on a single kotlin-stdlib version once that happens.
        data class Bundle(
            val id: String,
            val assetName: String,
            val jars: List<String> = emptyList(),
            /** Resolved artifact-only; list transitives explicitly. */
            val aars: List<String> = emptyList(),
            /** Asset for AAPT2-precompiled resources; omit for libraries without res/. */
            val resAssetName: String? = null,
        )

        val bundles = listOf(
            Bundle(
                id = "retrofit",
                assetName = "retrofit.jar.zip",
                jars = listOf(
                    "com.squareup.retrofit2:retrofit:2.9.0",
                    "com.squareup.retrofit2:converter-moshi:2.9.0",
                    "com.squareup.moshi:moshi:1.9.3",
                    "com.squareup.okhttp3:okhttp:3.14.9",
                    "com.squareup.okio:okio:1.17.2",
                ),
            ),
            // Ships no res/ and declares no manifest nodes, so it needs no resource asset.
            Bundle(
                id = "exifinterface",
                assetName = "exifinterface.jar.zip",
                aars = listOf("androidx.exifinterface:exifinterface:1.3.7"),
            ),
            // Both ship res/, so they need the overlay asset and matching --extra-packages
            // rows in BundledLibraries. Their transitives (appcompat, core, fragment,
            // recyclerview) are already in the androidx bundle and must stay out of here.
            Bundle(
                id = "jetpack-ui",
                assetName = "jetpack-ui.jar.zip",
                aars = listOf(
                    "androidx.preference:preference:1.1.1",
                    "androidx.biometric:biometric:1.1.0",
                ),
                resAssetName = "jetpack-ui-res-compiled.zip",
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


        // Classes already shipped by another bundle. Duplicates would reach D8 twice.
        fun classesInAsset(assetFile: java.io.File): Set<String> {
            if (!assetFile.exists()) return emptySet()
            val names = mutableSetOf<String>()
            java.util.zip.ZipInputStream(assetFile.inputStream().buffered()).use { outer ->
                generateSequence { outer.nextEntry }.forEach { _ ->
                    val bytes = outer.readBytes()
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { inner ->
                        generateSequence { inner.nextEntry }.forEach { e ->
                            if (e.name.endsWith(".class")) names += e.name
                        }
                    }
                }
            }
            return names
        }

        fun resolveFiles(coords: List<String>, transitive: Boolean, versions: MutableSet<String>): List<java.io.File> {
            if (coords.isEmpty()) return emptyList()
            val config = configurations.detachedConfiguration(
                *coords.map { dependencies.create(it) }.toTypedArray(),
            ).apply { isTransitive = transitive }
            return config.resolvedConfiguration.resolvedArtifacts
                .onEach { artifact ->
                    if (artifact.moduleVersion.id.group == "org.jetbrains.kotlin" &&
                        artifact.moduleVersion.id.name.startsWith("kotlin-stdlib")
                    ) {
                        versions += artifact.moduleVersion.id.version
                    }
                }
                .map { it.file }
                .sortedBy { it.name }
        }

        // Manifest nodes an AAR may declare without needing a merge into the app template.
        // Anything else must be hand-merged there first, so fail loudly instead.
        fun assertManifestIsBenign(id: String, manifest: java.io.File) {
            if (!manifest.exists()) return
            val text = manifest.readText()
            val nodes = Regex("<(provider|service|receiver|activity|activity-alias)\\b")
                .findAll(text).map { it.groupValues[1] }.toSet()
            require(nodes.isEmpty()) {
                "AAR in bundle '$id' declares $nodes in its manifest. The build engine does no " +
                    "manifest merging, so these must be hand-merged into the project template " +
                    "(app/src/main/assets/templates/EmptyActivity/.../AndroidManifest.xml) first."
            }
            val permissions = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
                .findAll(text).map { it.groupValues[1] }.toList()
            if (permissions.isNotEmpty()) {
                logger.lifecycle("  note: declares permissions $permissions - add them to the template manifest")
            }
        }

        // Locales kept in bundled library resources. Everything else is stripped: the
        // AndroidX bundle already ships only these, and ~80 locales of strings per library
        // is dead weight in every generated APK.
        //
        // NOTE: Hebrew's Android qualifier is the legacy "iw", not "he". Writing "he" here
        // silently drops every Hebrew string from Material/AppCompat with no error.
        val keptLocales = setOf("zh-rCN", "zh-rTW", "zh-rHK", "ja", "ko", "en-rGB", "iw")

        /** The locale qualifier of a res config dir, or null if it carries none. */
        fun localeOf(configDir: String): String? {
            val qualifiers = configDir.substringAfter('-', "").split('-').filter { it.isNotEmpty() }
            var i = 0
            while (i < qualifiers.size) {
                val q = qualifiers[i]
                if (q.startsWith("b+")) return q
                if (q.length == 2 && q.all { it.isLowerCase() && it.isLetter() }) {
                    val region = qualifiers.getOrNull(i + 1)
                    return if (region != null && region.length == 3 && region.startsWith("r")) "$q-$region" else q
                }
                i++
            }
            return null
        }

        /**
         * Merges AAR res/ trees into one directory, the way AGP would.
         *
         * XML under a values config dir is concatenated, because every AAR ships its own
         * res/values/values.xml and a plain copy would keep only the last one. Other
         * resource types have library-prefixed file names (abc_*, mtrl_*, preference_*)
         * so they copy across directly; a genuine clash fails the build.
         */
        fun mergeResDirs(id: String, resDirs: List<java.io.File>, outDir: java.io.File) {
            outDir.deleteRecursively()
            outDir.mkdirs()
            val valuesByConfig = linkedMapOf<String, MutableList<String>>()
            // Some libraries declare namespaces on <resources> (XLIFF placeholders in
            // translatable strings). Dropping them makes aapt2 fail with "unbound prefix",
            // so carry the union across to the merged file.
            val namespaceDecls = linkedSetOf<String>()
            var droppedLocales = 0
            resDirs.forEach { res ->
                res.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { configDir ->
                    val locale = localeOf(configDir.name)
                    if (locale != null && locale !in keptLocales) {
                        droppedLocales++
                        return@forEach
                    }
                    if (configDir.name.startsWith("values")) {
                        configDir.listFiles { f -> f.extension == "xml" }?.sortedBy { it.name }?.forEach { xml ->
                            val match = Regex("<resources([^>]*)>(.*)</resources>", RegexOption.DOT_MATCHES_ALL)
                                .find(xml.readText())
                            if (match != null) {
                                Regex("xmlns:[A-Za-z0-9_.-]+=\"[^\"]*\"")
                                    .findAll(match.groupValues[1])
                                    .forEach { namespaceDecls += it.value }
                                val inner = match.groupValues[2]
                                if (inner.isNotBlank()) {
                                    valuesByConfig.getOrPut(configDir.name) { mutableListOf() } += inner.trim()
                                }
                            }
                        }
                    } else {
                        val target = java.io.File(outDir, configDir.name).apply { mkdirs() }
                        configDir.listFiles()?.forEach { f ->
                            val dest = java.io.File(target, f.name)
                            require(!dest.exists()) {
                                "Bundle '$id': two libraries both ship ${configDir.name}/${f.name}. " +
                                    "Resource file names must be unique across bundled libraries."
                            }
                            f.copyTo(dest)
                        }
                    }
                }
            }
            valuesByConfig.forEach { (config, chunks) ->
                java.io.File(outDir, config).mkdirs()
                val openTag = if (namespaceDecls.isEmpty()) {
                    "<resources>"
                } else {
                    "<resources " + namespaceDecls.joinToString(" ") + ">"
                }
                java.io.File(outDir, "$config/values.xml").writeText(
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + openTag + "\n" +
                        chunks.joinToString("\n") + "\n</resources>\n",
                )
            }
            logger.lifecycle(
                "  merged res: ${valuesByConfig.size} values configs, " +
                    "${outDir.listFiles()?.size ?: 0} config dirs, $droppedLocales locale dirs dropped",
            )
        }

        /** Desktop AAPT2 from the local SDK - not the on-device libaapt2.so. */
        fun findAapt2(): java.io.File {
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            require(!sdk.isNullOrBlank()) { "Compiling library resources needs ANDROID_HOME set" }
            val candidate = java.io.File(sdk, "build-tools").listFiles()
                ?.filter { java.io.File(it, "aapt2").canExecute() }
                ?.maxByOrNull { it.name }
                ?.let { java.io.File(it, "aapt2") }
            require(candidate != null) { "No aapt2 found under $sdk/build-tools" }
            return candidate
        }

        val resolvedKotlinVersions = mutableSetOf<String>()
        val alreadyBundled = classesInAsset(java.io.File(assetsDir, "androidx-classes.jar.zip"))
        logger.lifecycle("androidx bundle contributes ${alreadyBundled.size} classes (duplicate guard)")

        bundles.forEach { bundle ->
            logger.lifecycle("Bundle '${bundle.id}' -> ${bundle.assetName}")
            val work = java.io.File(workRoot, bundle.id).apply { deleteRecursively(); mkdirs() }

            val jars = resolveFiles(bundle.jars, transitive = true, versions = resolvedKotlinVersions)
                .filter { it.extension == "jar" }
                .toMutableList()

            // Artifact-only: @aar with transitives off. See the header note.
            val aarFiles = resolveFiles(bundle.aars.map { "$it@aar" }, transitive = false, versions = resolvedKotlinVersions)
            val resDirs = mutableListOf<java.io.File>()
            aarFiles.forEach { aar ->
                val exploded = java.io.File(work, "aar/${aar.nameWithoutExtension}").apply { mkdirs() }
                copy {
                    from(zipTree(aar))
                    into(exploded)
                }
                val classes = java.io.File(exploded, "classes.jar")
                require(classes.exists()) { "AAR ${aar.name} in bundle '${bundle.id}' has no classes.jar" }
                jars += classes
                assertManifestIsBenign(bundle.id, java.io.File(exploded, "AndroidManifest.xml"))
                java.io.File(exploded, "res").takeIf { it.isDirectory }?.let { resDirs += it }
            }

            require(jars.isNotEmpty()) { "Bundle '${bundle.id}' resolved no jars" }
            logger.lifecycle(
                "  ${bundle.jars.size} jar coords + ${aarFiles.size} aars -> ${jars.size} class jars, " +
                    "${mb(jars.sumOf { it.length() })} raw",
            )

            val merged = java.io.File(work, bundle.assetName.removeSuffix(".zip"))
            mergeJars(jars.sortedBy { it.name }, merged)
            logger.lifecycle("  merged -> ${mb(merged.length())}")

            val collisions = java.util.zip.ZipInputStream(merged.inputStream().buffered()).use { zis ->
                generateSequence { zis.nextEntry }.map { it.name }
                    .filter { it.endsWith(".class") && it in alreadyBundled }
                    .toList()
            }
            require(collisions.isEmpty()) {
                "Bundle '${bundle.id}' duplicates ${collisions.size} classes already in the androidx " +
                    "bundle, e.g. ${collisions.take(3)}. Drop those coordinates from the bundle."
            }

            if (bundle.resAssetName != null) {
                require(resDirs.isNotEmpty()) { "Bundle '${bundle.id}' declares resAssetName but no AAR shipped res/" }
                val mergedRes = java.io.File(work, "res-merged")
                mergeResDirs(bundle.id, resDirs, mergedRes)
                val resAsset = java.io.File(assetsDir, bundle.resAssetName)
                resAsset.delete()
                // `aapt2 compile --dir` emits a zip of .flat files at the root, which is
                // exactly the shape BuildModule.getBundledDir expands into filesDir.
                providers.exec {
                    commandLine(
                        findAapt2().absolutePath, "compile",
                        "--dir", mergedRes.absolutePath,
                        "-o", resAsset.absolutePath,
                    )
                }.result.get().assertNormalExitValue()
                val flatCount = java.util.zip.ZipInputStream(resAsset.inputStream().buffered()).use { zis ->
                    generateSequence { zis.nextEntry }.count()
                }
                logger.lifecycle("  res asset ${resAsset.name} ${mb(resAsset.length())} ($flatCount .flat)")
            } else {
                require(resDirs.isEmpty()) {
                    "Bundle '${bundle.id}' has AARs with res/ but declares no resAssetName; " +
                        "their resources would be missing at runtime"
                }
            }

            val asset = java.io.File(assetsDir, bundle.assetName)
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
