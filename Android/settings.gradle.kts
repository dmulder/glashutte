pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        val centralUrl = providers.environmentVariable("MAVEN_CENTRAL_URL").orNull
        if (centralUrl.isNullOrBlank()) {
            mavenCentral()
        } else {
            maven { url = uri(centralUrl) }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        val centralUrl = providers.environmentVariable("MAVEN_CENTRAL_URL").orNull
        if (centralUrl.isNullOrBlank()) {
            mavenCentral()
        } else {
            maven { url = uri(centralUrl) }
        }
        // libadb-android and sun-security-android — the embedded ADB client (see docs/PRIVILEGE.md).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Glashutte"

include(":glasses")
include(":spatial")
include(":app")
