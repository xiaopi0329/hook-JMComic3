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
        // Legacy Xposed API 官方仓库（已实测可用）
        maven(url = "https://api.xposed.info")
    }
}
rootProject.name = "hook-JMComic3"
include(":app")
