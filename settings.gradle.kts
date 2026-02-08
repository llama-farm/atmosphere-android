pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Local AAR files for llama.cpp native library
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "Atmosphere"
include(":app")
include(":atmosphere-sdk")
include(":atmosphere-client")
