plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.opengraph.syncfield.insta360"
    compileSdk = 34

    defaultConfig {
        // 29: `WifiNetworkSpecifier` (the supported way to join the
        // camera AP for clip downloads) is API 29+. Pre-29 only has
        // the deprecated `WifiManager.enableNetwork`, which was
        // removed for app-side use in Android 10. Other syncfield
        // modules stay at API 26 — only Insta360 needs this floor.
        minSdk = 29
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
        }
    }
}

dependencies {
    api(project(":syncfield-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.insta.camera)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:rules:1.5.0")
}
