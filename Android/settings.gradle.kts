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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android and sun-security-android — the embedded ADB client (see docs/PRIVILEGE.md).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Glashutte"

include(":glasses")
include(":spatial")
include(":app")
