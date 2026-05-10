plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Stable coordinates so composite builds in host apps (e.g. og-skill)
// resolve `io.opengraph.syncfield:<module>:0.4.0-SNAPSHOT` against the
// included build's modules without requiring a Maven publish.
subprojects {
    group = "io.opengraph.syncfield"
    version = "0.4.0-SNAPSHOT"
}
