pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "syncfield-kotlin"

include(":syncfield-core")
include(":syncfield-streams")
include(":syncfield-tactile")
include(":syncfield-insta360")
include(":syncfield-ui")
