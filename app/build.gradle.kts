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
        applicationId = if (project.hasProperty("testBuild")) "com.scanwhirl.app.test" else "com.scanwhirl.app"
        manifestPlaceholders["appLabel"] =
            if (project.hasProperty("testBuild")) "ScanWhirl Test" else "ScanWhirl"
        minSdk = 24
        targetSdk = 36
        versionCode = 20
        versionName = "1.1.1-test20"

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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
