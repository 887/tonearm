import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
  alias(libs.plugins.licensee)
}

// D.16.4 — capture build-time metadata that the About screen renders.
// `git rev-parse --short HEAD` is shelled out at configuration time so
// the value is baked into BuildConfig.GIT_SHA. The ISO-8601 build date
// is captured at the same time; both fall back to sentinels if the
// underlying command fails (e.g. building from a tarball without git).
val gitShortSha: String = runCatching {
  val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
    .redirectErrorStream(true)
    .start()
  proc.waitFor()
  proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
}.getOrDefault("unknown")

val buildDateUtc: String = DateTimeFormatter.ISO_LOCAL_DATE
  .format(LocalDate.now(ZoneOffset.UTC))

android {
    namespace = "com.eight87.tonearmboy"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.eight87.tonearmboy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // D.16.4.1 / D.16.4.2 — surface build identity to runtime code.
        // BuildConfig is the conventional Android channel; we emit string
        // constants that the About screen reads via reflection-free code
        // (`BuildConfig.GIT_SHA`, `BuildConfig.BUILD_DATE`).
        buildConfigField("String", "GIT_SHA", "\"$gitShortSha\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDateUtc\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests {
        isIncludeAndroidResources = true
      }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// OSS Phase A.3 — declare allowed licenses for shipped deps. v1 ships
// reporting-only (no failOnDisallowed); we'll flip enforcement once the
// inventory has been reviewed once. EPL-1.0 (junit) is test-scope only,
// so it never enters the resolved release classpath Licensee inspects.
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
}

// OSS Phase A.4 — copy the per-variant Licensee `artifacts.json` into
// `src/main/assets/licenses/` so `LicensesScreen` can read it via
// AssetManager at runtime. Wired as a dependency of the variant's
// mergeAssets task so the asset is always self-consistent with the
// build's resolved classpath.
val licenseeReportDir = layout.buildDirectory.dir("reports/licensee")
val licensesAssetDir = layout.projectDirectory.dir("src/main/assets/licenses")

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<Copy>("copyLicensesAssetFor$variantName") {
            dependsOn("licenseeAndroid$variantName")
            from(licenseeReportDir.map { it.dir("android$variantName") }) {
                include("artifacts.json")
            }
            into(licensesAssetDir)
        }
        afterEvaluate {
            tasks.named("merge${variantName}Assets").configure {
                dependsOn(copyTask)
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Cover-art Phase B — MockWebServer for the Piped instance-failover
  // test. Test scope; never ships in the APK.
  testImplementation(libs.okhttp.mockwebserver)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  // Room MigrationTestHelper for `LibraryDatabaseMigrationTest`. Room
  // 2.8.x's schema-bundle deserializer expects
  // `kotlinx-serialization-core >= 1.8` (default impl of
  // `GeneratedSerializer.typeParametersSerializers()`); the
  // project-wide bump in `libs.versions.toml` keeps the production
  // classpath ahead of that floor.
  androidTestImplementation(libs.androidx.room.testing)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Media3 — ExoPlayer + MediaSession + UI helpers.
  // Note: androidx.media3 does not publish a Maven BOM; versions are
  // centralized via the `media3` key in libs.versions.toml so all three
  // modules upgrade as a unit.
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)

  // Room — local cache for the MediaStore-derived library (Phase C).
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // DataStore — persists Phase D Settings (theme preference).
  implementation(libs.androidx.datastore.preferences)

  // WorkManager — D.9d.2 LibraryRescanWorker debouncing the
  // automatic-reload content observer.
  implementation(libs.androidx.work.runtime.ktx)

  // D.17.2 — SplashScreen 1.x backport. Provides Theme.SplashScreen +
  // installSplashScreen() so the cold-boot frame stays on the dark
  // launcher background instead of flashing white before Compose
  // mounts.
  implementation(libs.androidx.core.splashscreen)

  // DocumentFile — D.9d.1 SAF tree walker for music-source URIs.
  implementation("androidx.documentfile:documentfile:1.0.1")

  // Coroutines bridge to Guava ListenableFuture for the Media3
  // MediaController.connect() handshake on the UI thread.
  implementation(libs.kotlinx.coroutines.guava)

  // kotlinx-serialization JSON for Phase E.5 queue persistence.
  implementation(libs.kotlinx.serialization.json)

  // Coil 3 — Compose-first image loader (Phase D.9b.3 album covers).
  // album-art Phase D pulls coil-network-okhttp so Coil can load
  // remote URLs directly (Cover Art Archive returns http(s) image
  // URLs). The dep transitively brings in okhttp itself, which the
  // MusicBrainzClient uses for the JSON API calls.
  implementation(libs.coil3.compose)
  implementation(libs.coil3.network.okhttp)

  // D.20.4 — Palette ktx for album-art-driven theming. Used by
  // `AlbumPaletteExtractor` to derive dark-muted / dark-vibrant
  // swatches from the playing track's cover bitmap.
  implementation(libs.androidx.palette.ktx)

  // Cover-art Phase D — Compose LazyColumn drag-to-reorder for the
  // user-prioritised provider list. Small, Apache-2.0, on the licensee
  // allowlist by default.
  implementation(libs.reorderable)

  // Robolectric-driven JVM unit tests for the data layer (Phase C verification).
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.arch.core.testing)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.androidx.work.testing)

  // D.11 — Compose UI testing on the JVM via Robolectric.
  // `ui-test-junit4` brings `createComposeRule()`; `ui-test-manifest`
  // ships the Activity manifest entry the rule needs at runtime.
  testImplementation(composeBom)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.compose.ui.test.manifest)
}
