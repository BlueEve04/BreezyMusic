pluginManagement {
    repositories {
        // 国内镜像优先，显著加速依赖解析（含 AGP / Kotlin 插件）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // SuperLyricApi（GitHub 歌词发布 API）
        maven("https://jitpack.io")
    }
}

rootProject.name = "BreezyMusic"
include(":app")
include(":breezy-ui")
project(":breezy-ui").projectDir = File("../BreezyUI/breezy-ui")
