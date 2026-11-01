plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.jarvis.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.ai"
        minSdk = 29 // Android 10
        targetSdk = 35 // Android 15
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("ar", "fr", "en")
    }

    signingConfigs {
        /**
         * A fixed debug key, committed to the repository on purpose.
         *
         * Without it, Gradle signs with whatever ~/.android/debug.keystore
         * the build machine happens to have — and a CI runner generates a
         * fresh one every run. Every build then carries a different
         * signature, so installing a new APK over the previous one fails
         * with "App not installed", and the only way through is to
         * uninstall and lose the app's data each time.
         *
         * This is not a secret and must never sign a release: the password
         * is the Android convention, and the certificate is self-signed
         * with no identity behind it. Release signing stays in CI secrets.
         */
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // v2 alone is valid from API 24 up, and Gradle drops v1 when
            // minSdk allows it. But sideloading does not always go through
            // the platform installer: some OEM package installers and file
            // managers still look for the old JAR signature and refuse the
            // APK without it. Signing both ways costs nothing.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signing config committed. Release signing is supplied by CI
            // secrets once the owner provides a keystore.
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
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // Print findings to stdout: the raw CI log is not reachable from the
        // dev sandbox, so the text report is what makes them visible.
        textReport = true
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    debugImplementation(libs.okhttp.logging)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
