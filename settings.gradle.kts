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
        maven {
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            credentials {
                username = "insta360guest"
                password = "EXMSjSo8OeOrjU7d"
            }
        }
    }
}

rootProject.name = "syncfield-kotlin"

include(":syncfield-core")
include(":syncfield-streams")
include(":syncfield-tactile")
include(":syncfield-insta360")
include(":syncfield-ui")
