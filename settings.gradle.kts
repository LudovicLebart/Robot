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

rootProject.name = "Robot"

include(":app")
include(":core-common")
include(":core-slam")
include(":core-depth")
include(":core-tsdf")
include(":core-render")
include(":core-net")
include(":core-nav")
