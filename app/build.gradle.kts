plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.glasslauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.glasslauncher"
        minSdk = 28
        targetSdk = 36
        // A new versionCode for every build (P275), monotonic everywhere: minutes since 2026-01-01 when the APK was built. A system APK
        // replaced under the same versionCode keeps PackageManager's cached parse across a reboot, and Glass then died on resource IDs
        // from the old build (Glass Pro/OS updates write the system APK). A commit count would make an older checkout a downgrade,
        // which a user build refuses, so A/B installs of master and a branch would fail. A ValueSource, so the configuration cache
        // re-reads it on every build instead of keeping the first one.
        versionCode = 3 // replaced per build in androidComponents below (BuildMinute)
        versionName = "0.3.0"
        buildConfigField("String", "UPDATE_REPO", "\"AazainKhan/vitre\"")
    }

    signingConfigs {
        // CI supplies a real key through secrets. Local builds fall back to the debug key; release tags must not (see build.yml).
        val keystore = System.getenv("RELEASE_KEYSTORE")?.let { file(it) }
        if (keystore != null && keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Fire TV (32-bit userland) and every arm64 box: x86 builds are debug-only (the emulators).
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        // P271: the dex was stored uncompressed (AGP's default from minSdk 28), 90% of the APK. Compressed it is 2.5 MB smaller; the installer inflates it.
        dex { useLegacyPackaging = true }
        // Licence texts AndroidX repeats in every artifact (the notices ship in assets/licenses) and the coroutines debug-agent probe file.
        resources { excludes += setOf("META-INF/androidx/**", "DebugProbesKt.bin") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric screenshot tests (Roborazzi) need merged resources and the real manifest.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // No unit test touches the real network: every request fails fast against a dead proxy (localhost stays direct). Without this a golden
            // that shows Cinemeta data depended on whether an earlier test in the JVM had blocked the network (TvHarness does), so it flaked run to run.
            for (scheme in listOf("http", "https")) { it.systemProperty("$scheme.proxyHost", "127.0.0.1"); it.systemProperty("$scheme.proxyPort", "9") }
            // Except Maven Central: Robolectric downloads its android-all jar on a fresh CI runner (CI failed on every push after the proxy landed).
            it.systemProperty("http.nonProxyHosts", "localhost|127.*|repo1.maven.org|repo.maven.apache.org")
            it.maxHeapSize = "3g"
            // A flaky failure on CI must leave its whole stack in the log (P218: "IllegalStateException at LifecycleRegistry.kt:382" was all there was).
            it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showCauses = true; showStackTraces = true }
            // -Pstrips=all|<name> turns on the MotionStrips frame captures.
            it.systemProperty("strips", providers.gradleProperty("strips").getOrElse(""))
            // GLASS_SHOTS_TIME=HH:mm pins the clock the shots show (see TvHarness); declared as an input so
            // a run at another time is never a stale cached/up-to-date pass.
            it.inputs.property("glassShotsTime", providers.environmentVariable("GLASS_SHOTS_TIME").orElse(""))
            // NativeLibraryLoadsTest reads the committed native libraries: a rebuilt .so must rerun it, not pass as up to date (P347).
            it.inputs.files(fileTree("src/main/jniLibs")).withPropertyName("jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Every @Preview under dev.glasslauncher.preview (debug source set) becomes a screenshot test.
roborazzi {
    // Preview baselines live with the other screenshots (HomeShots pass explicit paths).
    outputDir.set(file("src/test/screenshots/previews"))
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("dev.glasslauncher.preview")
        robolectricConfig = mapOf("sdk" to "[35]", "qualifiers" to "\"w960dp-h540dp-land-television-xhdpi\"")
    }
}

dependencies {
    implementation(project(":addon-api"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.preview.scanner)
    testImplementation(libs.roborazzi.preview.scanner.support)
    debugImplementation(libs.compose.ui.test.manifest)
}

/** P275: minutes since 2026-01-01 when the build runs; Gradle re-reads a ValueSource on every build, so each APK gets a new versionCode. */
abstract class BuildMinute : ValueSource<Int, ValueSourceParameters.None> {
    override fun obtain(): Int = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
}

androidComponents {
    onVariants { variant ->
        val minute = providers.of(BuildMinute::class.java) {}
        variant.outputs.forEach { it.versionCode.set(minute) }
    }
}
