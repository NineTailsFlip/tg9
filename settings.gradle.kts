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

rootProject.name = "tg9"

// The Nine suite's core and the gallery kit shared with G9, from the nine-core submodule
// (git clone --recursive, or git submodule update --init).
include(":core")
project(":core").projectDir = file("nine-core/core")
include(":kit")
project(":kit").projectDir = file("nine-core/kit")

include(":tdlib")
include(":app")
