pluginManagement {
    includeBuild("build-logic")
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
    // PREFER_PROJECT rather than FAIL_ON_PROJECT_REPOS: the companion needs the IntelliJ Platform repositories,
    // whose local Ivy repository has no URL. Declared here they would reach :app:android too, where AGP's release
    // dependency report (collectReleaseDependencies) fails on a repository without a URL.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
            content {
                includeGroupByRegex("org\\.jetbrains\\.compose.*")
            }
        }
    }
}

rootProject.name = "Tennyo"
include(":shared:contract")
include(":shared:crypto")
include(":host:engine")
include(":host:intellij")
include(":host:vscode")
include(":app:shared")
include(":app:design")
include(":app:link")
include(":app:android")
include(":app:feature:chat")
include(":app:feature:connect")
include(":app:feature:files")
include(":app:feature:terminal")
include(":app:feature:launch")
include(":app:feature:git")
include(":app:feature:settings")
include(":app:feature:local")
