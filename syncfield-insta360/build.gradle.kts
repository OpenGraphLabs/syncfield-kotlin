plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.opengraph.syncfield.insta360"
    compileSdk = 34

    defaultConfig {
        // 28: `Insta360WiFiDownloader` joins the camera AP via the
        // app-scoped `WifiNetworkSpecifier` path on Q+, and falls
        // back to the legacy `WifiManager.addNetwork` + `enableNetwork`
        // + `bindProcessToNetwork` flow on P. The legacy path is
        // system-scoped (it briefly takes over the device Wi-Fi)
        // and best-effort restores the previous SSID on cleanup;
        // see `Insta360WiFiDownloader.applyNetworkSuggestionLegacy`.
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":syncfield-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.insta.camera)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation("androidx.test:rules:1.5.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.truth)
}
