pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://repo1.maven.org/maven2/") }
        // v2.9.0：FrostWire 官方 Maven（新版 jlibtorrent AAR/JAR 只在这里发布）
        maven { url = uri("https://dl.frostwire.com/maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.2" apply false
        id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // FrostWire 仓库的 AAR 实际托管在 GitHub Release，国内网络常不可达；
        // 镜像预取后放到 ~/.m2/repository，构建优先走本地缓存。
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://repo1.maven.org/maven2/") }
        // v2.9.0：新版 jlibtorrent AAR 的唯一发布源，必须放在依赖解析仓库里
        maven { url = uri("https://dl.frostwire.com/maven") }
        google()
        mavenCentral()
    }
}
rootProject.name = "SwiftDownload"
include(":app")
