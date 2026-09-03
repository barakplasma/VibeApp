# Bundled Libraries

> How VibeApp decides what generated apps can `import`, and how to change it.

Generated apps are compiled on-device by `build-engine` (AAPT2 → nb-javac → D8 → ApkBuilder
→ apksig). There is no Gradle, no dependency resolution, and no annotation processing at
build time. So the set of libraries a generated app may use is fixed at **VibeApp build
time**: each one ships as an asset inside VibeApp and is extracted to `filesDir` on first
use.

Every bundled library is **always-on** — it lands on the javac classpath and in every
generated APK. There is no per-project opt-in.

## Current catalog

| id | Contents | Asset |
|---|---|---|
| `androidx` | AndroidX + Material Components (2021 pins), with precompiled `.flat` resources | `androidx-classes.jar.zip`, `androidx-res-compiled.zip` |
| `shadow-runtime` | `ShadowActivity` and friends, for plugin mode | `shadow-runtime.jar` |
| `jsoup` | HTTP + HTML parsing | `jsoup.jar.zip` |
| `retrofit` | Retrofit + Moshi + OkHttp + Okio | `retrofit.jar.zip` |
| `exifinterface` | AndroidX ExifInterface (image orientation) | `exifinterface.jar.zip` |
| `jetpack-ui` | AndroidX Preference + Biometric | `jetpack-ui.jar.zip`, `jetpack-ui-res-compiled.zip` |
| `mlkit` | ML Kit barcode scanning + text recognition (unbundled, GMS-backed) | `mlkit.jar.zip`, `mlkit-res-compiled.zip` |

Not yet bundled: CameraX, which would give ML Kit a live camera preview instead of
gallery-only input, and ML Kit translation. CameraX 1.1.0+ needs the Kotlin runtime below;
translation carries a ~15.6 MB native engine per ABI.

Those bundles are Kotlin-written, so they also need a `kotlin-runtime` bundle
(`kotlin-stdlib` 2.3.21 + `kotlinx-coroutines` 1.10.1, ~2.9 MB). It is **not** bundled today:
nothing in the current catalog uses Kotlin — the Retrofit stack was verified to contain zero
Kotlin classes — and shipping an unused runtime would add ~3 MB of dex to every generated
app. Add it in the same change as the first Kotlin-dependent library.

## The catalog

`build-engine/src/main/java/com/vibe/build/engine/internal/BundledLibraries.kt` is the
single source of truth. One row per library:

```kotlin
BundledLibrary(
    id = "jsoup",
    classesAsset = "jsoup.jar.zip",
)
```

`BundledLibraries.resolve()` extracts each entry's assets and returns the ones that are
present. `BuildWorkspace.libraries` carries that list, and the pipeline stages consume it:

| Stage | File | Use |
|---|---|---|
| RESOURCE | `resource/Aapt2ResourceCompiler.kt` | `-R` for each `.flat` overlay, `--extra-packages` for library R classes |
| COMPILE | `compiler/JavacCompiler.kt` | `workspace.libraryJars()` on the javac classpath |
| DEX | `dex/PreDexCache.kt`, `dex/D8DexConverter.kt` | pre-dexed once and cached; user code dexed per build |

A library whose asset is missing is **skipped, not fatal**. That is how a build flavour
ships a subset of the catalog.

> ⚠️ The `mlkit` bundle is the exception. The project template's manifest declares its
> components and references `@integer/google_play_services_version`, which resolves only
> from that bundle's resources. Dropping `mlkit` from the catalog without also removing
> those manifest nodes fails every generated build at the AAPT2 link step. Remove the two
> together, or not at all.

### Adding a library

1. Add its coordinates to a bundle in `gradle/bundled-libs.gradle.kts`.
2. Run `./gradlew :build-engine:generateBundledLibraryAssets` (needs network and, for
   shrunk bundles, `ANDROID_HOME`). Commit the regenerated asset.
3. Add the matching `BundledLibrary` row to `BundledLibraries.ALL`.
4. Bump `CACHE_VERSION` in `PreDexCache.kt` so devices re-dex.
5. Describe it in `app/src/main/assets/agent-system-prompt.md` — the agent only uses what
   that file advertises.

The generation task is deliberately **not** wired into `assembleDebug`: it needs the
network and its outputs are committed binaries.

It also lives in `gradle/` rather than beside `build-engine/build.gradle.kts`. Any extra
`.gradle.kts` inside an Android module's directory crashes lint's build-script visitor
(`findFirCompiledSymbol only works on compiled declarations`) and fails
`:build-engine:lintAnalyzeDebug`. Keep new build scripts out of module directories.

## Kotlin: a runtime dependency, not a source language

Generated app **source** is Java 8 and must stay that way — that constraint is what keeps
on-device compilation reliable (no lambdas, no method references, no try-with-resources).

That is separate from whether a *bundled library* was written in Kotlin. `kotlin-stdlib`
is an ordinary jar that D8 dexes with no Kotlin compiler involved, so generated Java can
call Kotlin-compiled libraries freely. Practical consequences:

- Exactly **one** `kotlin-stdlib` version may be resolved across all bundles. The
  generation task fails the build otherwise.
- Never call a `suspend` function from generated Java. Use the Java-facing surface
  (`ListenableFuture`, plain interfaces).
- `META-INF/versions/**` (multi-release jars) is stripped during merging; D8 chokes on it.

## Deliberately excluded

Recorded with evidence so these are not re-proposed.

### Persistence

Every *maintained* option depends on build-time codegen, which a Gradle-less on-device
pipeline cannot run:

| Candidate | Last release | Blocker |
|---|---|---|
| Room | current | Annotation processor generates all DAOs; `JavacCompiler` passes `processors = null` |
| SQLDelight 2.3.2 | 2026-03 | Gradle plugin generates from `.sq`, emits Kotlin, needs kotlin-stdlib |
| ObjectBox 6.0.0-beta | 2026-07 | Gradle plugin for `@Entity` codegen, plus native libs |
| ORMLite 6.1 | 2021 | Codegen-free, but no longer actively developed |
| requery 1.6.0 | 2019 | Dead; also APT-based |

Embedded databases avoiding codegen fail on other hard constraints:

| Candidate | Bytecode | Blocker |
|---|---|---|
| H2 2.4.240 | Java 11 | `java.lang.invoke.VarHandle` in 21 classes — ART does not provide it; also `javax.naming`, `javax.management`, `javax.tools`, `java.awt` |
| ArcadeDB 26.8.1 | **Java 21** | The bundled dexer (`com.android.tools:r8:3.1.51`) cannot read Java 21 class files; server-oriented, 6.8 MB engine |

MapDB came closest and was attempted: Java 8 bytecode, 652 KB, no `sun.misc.Unsafe`. But it
resolves to **16.84 MB across 14 jars** (eclipse-collections and Guava dominate), and D8
does no shrinking, so unshrunk it would dominate every generated APK. A dev-time R8 shrink
was tried and did not work; rather than ship 16 MB or a half-working shrink step, MapDB is
out.

**Generated apps should use `SQLiteOpenHelper`** — it is part of the Android SDK, needs no
dependency, and cannot go unmaintained. Paired with the bundled Moshi it also covers
document-style storage: serialise a POJO to JSON, store the string, and query it with
SQLite's JSON1 functions (`json_extract`), which are available at `minSdk = 29`.

### Other

- **DataStore** — AAR plus `kotlin-stdlib`, and a `Flow`/`suspend`-only API. `SharedPreferences`
  covers the same ground with no dependency.
- **Gson** — superseded by Moshi, which depends only on okio.
- **ML Kit translation** — no `play-services-mlkit-translate` exists, and
  `com.google.mlkit:translate` carries a ~15.6 MB native engine per ABI.
- **ML Kit GenAI** — requires AICore / Gemini Nano (Pixel 8/9- and Galaxy S24/S25-class,
  Android 14/15+), APIs are alpha/beta, and it has no tool-calling API so it cannot back
  VibeApp's own agent loop either.

## The cost of `--extra-packages`

AAPT2 generates a **complete copy** of the resource table as `R.java` for every package in
`--extra-packages`, not just that package's own resources. Measured on the current catalog:
19 R.java files, ~2 MB of Java source each, ~38 MB total.

That matters because `JavacCompiler` compiles R.java in batches of three with `System.gc()`
between them, against a device heap of roughly 192 MB. Every package added to a bundle's
`extraPackages` costs another ~2 MB of generated source to compile on device. A bundle that
adds six packages (ML Kit and play-services would) adds ~12 MB.

Verify a new resource bundle links before shipping it — the failure otherwise only appears
on a phone. The device pipeline can be simulated with the desktop AAPT2:

```sh
aapt2 link -I $ANDROID_HOME/platforms/android-36/android.jar \
  --manifest <template manifest> --java gen --custom-package com.vibe.generated.p1 \
  -o /dev/null --allow-reserved-package-id --auto-add-overlay \
  --min-sdk-version 29 --target-sdk-version 36 \
  --extra-packages <colon-separated packages> \
  $(for f in <all .flat files>; do echo -n " -R $f"; done)
```

## Locales and RTL

AAR-based libraries ship strings for ~80 locales. When the AAR pipeline lands, the
generation task must strip them to zh-rCN, zh-rTW, zh-rHK, ja, ko, en-rGB and **`iw`**.

> ⚠️ Android's Hebrew resource qualifier is the legacy **`iw`**, not `he`. `play-services-basement`
> ships `res/values-iw`. A locale filter written as `he` silently drops every Hebrew string
> in AndroidX, Material and play-services — no error, just English fallback at runtime.

The project template already sets `android:supportsRtl="true"` and its layouts use
`gravity="center"` rather than left/right, so RTL works out of the box. Generated layouts
must use `start`/`end` attributes; that rule lives in the agent system prompt.

**If ML Kit text recognition is added later, note it cannot read Hebrew.** Google ships
models for Latin, Chinese, Devanagari, Japanese and Korean only — there is no Hebrew or
Arabic model. Hebrew *UI* is fully supported; Hebrew *OCR* is out of scope.

## ABIs

No bundled library ships native code today. When one does, filter it to **arm64-v8a** only. `minSdk = 29` means effectively every
target device is arm64; keeping x86/x86_64 would add ~22 MB and armeabi-v7a a further
~6.5 MB to every generated APK.
