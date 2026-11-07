pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "rutongo-mmems"
include(":server", ":shared", ":androidApp")
