pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
        }
        ivy("https://nodejs.org/dist") {
            patternLayout { artifact("v[revision]/node-v[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            patternLayout { artifact("v[revision]/yarn-v[revision].[ext]") }
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "rutongo-mmems"
include(":server", ":shared", ":androidApp", ":webApp")
