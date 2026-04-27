plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.opengraph.syncfield.insta360"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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

    // Insta360's Android SDK (`OneSDK`) is distributed as an AAR. Host apps
    // drop `OneSDK.aar` into their `libs/` directory and add a `flatDir`
    // repository plus a flatfile dependency:
    //   implementation(name = "OneSDK", ext = "aar")
    // This module declares its compile-time integration through the
    // `OneSDKBridge` indirection in `Insta360OneSDKBridge.kt` so the
    // module compiles cleanly without the AAR present (host apps that
    // don't need Insta360 don't pay the cost).

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:rules:1.5.0")
}
