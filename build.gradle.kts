import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val syncFieldReleaseVersion = "0.5.0"

fun publishedGroup(): String {
    if (System.getenv("JITPACK") == "true") {
        val group = System.getenv("GROUP")
        val artifact = System.getenv("ARTIFACT")
        if (!group.isNullOrBlank() && !artifact.isNullOrBlank()) {
            return "$group.$artifact"
        }
    }
    return "io.opengraph.syncfield"
}

fun publishedVersion(): String {
    return if (System.getenv("JITPACK") == "true") {
        System.getenv("VERSION") ?: syncFieldReleaseVersion
    } else {
        syncFieldReleaseVersion
    }
}

// Stable release coordinates for local Maven publishing and composite builds.
// JitPack injects GROUP/ARTIFACT/VERSION, so tagged builds are published under
// `com.github.OpenGraphLabs.syncfield-kotlin:<module>:<tag>`.
subprojects {
    group = publishedGroup()
    version = publishedVersion()

    pluginManager.withPlugin("com.android.library") {
        plugins.apply("maven-publish")

        extensions.configure<LibraryExtension>("android") {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    register<MavenPublication>("release") {
                        groupId = project.group.toString()
                        artifactId = project.name
                        version = project.version.toString()
                        from(components["release"])

                        pom {
                            name.set(project.name)
                            description.set("SyncField Android SDK module")
                            url.set("https://github.com/OpenGraphLabs/syncfield-kotlin")
                            licenses {
                                license {
                                    name.set("Apache License 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                }
                            }
                            developers {
                                developer {
                                    id.set("OpenGraphLabs")
                                    name.set("OpenGraph Labs")
                                }
                            }
                            scm {
                                connection.set("scm:git:https://github.com/OpenGraphLabs/syncfield-kotlin.git")
                                developerConnection.set("scm:git:ssh://git@github.com/OpenGraphLabs/syncfield-kotlin.git")
                                url.set("https://github.com/OpenGraphLabs/syncfield-kotlin")
                            }
                        }
                    }
                }
            }
        }
    }
}
