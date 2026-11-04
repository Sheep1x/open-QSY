pluginManagement {
    repositories {
        // 国内镜像优先：本机访问 repo.maven.apache.org 会被解析到错误 IP，TLS 证书不匹配
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 注：官方 Sample 原本在这里加了 foojay-resolver-convention（自动下载 JDK toolchain）。
// 本机 JDK 21 已满足 AGP 9 要求，且该插件需从 plugin portal 拉取，故移除。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "GlassEyes"
include(":app")
