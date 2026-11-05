pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "pilot-mobile"
include(":transport", ":bridge")
// 源码电脑只生成二维码时不需要 Android SDK，也不配置安卓应用模块。
if (providers.gradleProperty("pilot.desktopOnly").orNull != "true") include(":app")
