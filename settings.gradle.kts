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

rootProject.name = "MochiTs"

include(":app")
include(":core-canvas")
include(":core-imaging")
include(":core-inpaint-ml")
include(":core-text")
include(":core-project")
include(":core-common")
