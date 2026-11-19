import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scanwhirl.app"
    compileSdk = 36

    defaultConfig {
        // Test builds (-PtestBuild) install side-by-side as "ScanWhirl Test"
        // and never touch the Play package.
        // -PdemoBuild (with -PtestBuild): a clean third copy, "ScanWhirl", for store screenshots
        // (com.scanwhirl.app.demo) so the QA library and the Play app are never touched.
        applicationId = when {
            project.hasProperty("demoBuild") -> "com.scanwhirl.app.demo"
            project.hasProperty("testBuild") -> "com.scanwhirl.app.test"
            else -> "com.scanwhirl.app"
        }
        manifestPlaceholders["appLabel"] =
            if (project.hasProperty("testBuild") && !project.hasProperty("demoBuild")) "ScanWhirl Test" else "ScanWhirl"
        minSdk = 24
        targetSdk = 36
        versionCode = 26111901
        versionName = "1.2.3"

        // Pro features are unlocked in test builds so they can be tried free.
        buildConfigField(
            "boolean",
            "TEST_BUILD",
            if (project.hasProperty("testBuild")) "true" else "false"
        )
    }

    signingConfigs {
        create("release") {
            // Credentials come from environment variables or an untracked
            // keystore.properties next to this file — never from the repo.
            val props = Properties().apply {
                val f = rootProject.file("keystore.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun secret(name: String): String? =
                System.getenv("SCANWHIRL_$name") ?: props.getProperty(name.lowercase())
            storeFile = file("../keystore/scanwhirl.keystore")
            storePassword = secret("STORE_PASSWORD")
            keyAlias = secret("KEY_ALIAS") ?: "scanwhirl"
            keyPassword = secret("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Test builds (-PtestBuild) ship OPEN: no R8 minify, no resource
            // shrinking, so class and method names stay readable for code
            // review. The Play build keeps minification for small downloads.
            isMinifyEnabled = !project.hasProperty("testBuild")
            isShrinkResources = !project.hasProperty("testBuild")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The cloud gate builds this variant on a runner that never has the
            // owner's keystore, so `-PciUnsignedRelease` swaps in the debug key:
            // the APK is installable for a smoke test and NOT distributable.
            // Without the switch the release build signs exactly as before.
            signingConfig = if (project.hasProperty("ciUnsignedRelease")) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        // WP-04: all 13 known errors fixed; now enforce lint gate.
        // checkReleaseBuilds=false keeps lint out of assembleRelease path
        // so it remains a deliberate manual gate (./gradlew lintDebug).
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric sandboxes accumulate memory over 1300+ tests; the WSL gate box has ~3.5 GB.
            // A fresh test JVM every 40 classes with a fixed 1.5 GB heap keeps the full suite alive
            // (120 classes / 1 GB still hit OutOfMemoryError in "SDK 35 Main Thread").
            // The cloud gate (16 GB runner) passes -PtestHeap=4g -PtestForkEvery=200.
            // UI-ATLAS (tools/atlas/run_atlas.ps1) is an on-demand job: with no atlas flag the
            // class is filtered out instead of skipped, because the fail-closed gate counts a
            // skipped test as a failure. The test itself also Assume-guards on the property.
            val atlasRun = ((project.findProperty("scanwhirl.atlas") as String?)
                ?: System.getProperty("scanwhirl.atlas")) == "true"
            val corpusRun = System.getProperty("scanwhirl.corpus") == "true"
            all {
                it.maxHeapSize = (project.findProperty("testHeap") as String?) ?: "1536m"
                it.forkEvery = (project.findProperty("testForkEvery") as String?)?.toLongOrNull() ?: 40L
                it.systemProperty("scanwhirl.atlas", if (atlasRun) "true" else "false")
                it.systemProperty(
                    "scanwhirl.atlas.out",
                    File(project.layout.buildDirectory.get().asFile, "atlas").absolutePath
                )
                if (!atlasRun) {
                    it.filter { excludeTestsMatching("com.scanwhirl.app.atlas.*") }
                }
                if (corpusRun) {
                    it.systemProperty("scanwhirl.corpus", "true")
                    for (key in listOf("path", "out", "revision")) {
                        System.getProperty("scanwhirl.corpus.$key")?.let { value ->
                            it.systemProperty("scanwhirl.corpus.$key", value)
                        }
                    }
                    // Fixtures live outside the repo and may change with no Gradle input change.
                    it.outputs.upToDateWhen { false }
                } else {
                    it.filter { excludeTestsMatching("com.scanwhirl.app.corpus.*") }
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // M07: device-acceptance fixtures (a test-only file-picker provider) exist only
    // in -PtestBuild APKs. Production builds never compile or declare them.
    if (project.hasProperty("testBuild")) {
        sourceSets {
            listOf("debug", "release").forEach { type ->
                getByName(type) {
                    java.srcDir("src/testHarness/java")
                    manifest.srcFile("src/testHarness/AndroidManifest.xml")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // On-device text recognition through Play services (model downloads on
    // first use; keeps the APK small, consistent with the scanner).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:18.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Vault unlock (70_APP_DESIGN §4.1): in-app fingerprint/face sheet with device-credential fallback. Offline, no permission beyond USE_BIOMETRIC.
    implementation("androidx.biometric:biometric:1.1.0")
    // Play Billing for the one-time Pro unlock. The store talks to Play itself; ScanWhirl keeps
    // no network permission (the manifest strips INTERNET/ACCESS_NETWORK_STATE).
    implementation("com.android.billingclient:billing-ktx:8.3.0")
    // QR code reader tool (lead-approved): Google code scanner UI, same delivery model as the document scanner; no camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Hindi OCR (lead-approved, PM-6.3): bundled Devanagari model, works fully offline.
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.apache.pdfbox:pdfbox:3.0.8") // Independent corpus oracle; never in the APK.
}
