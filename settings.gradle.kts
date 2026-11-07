pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroup("com.google.android.material")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroup("com.google.android.material")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Pikapika"
include(":app", ":core:network", ":core:data", ":core:designsystem", ":core:domain", ":feature:reader")
