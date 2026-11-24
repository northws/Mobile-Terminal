pluginManagement {
    repositories {
        System.getenv("WARP_GOOGLE_MAVEN")?.let { maven(it) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        System.getenv("WARP_GOOGLE_MAVEN")?.let { maven(it) }
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileTerminal"
include(":app")
