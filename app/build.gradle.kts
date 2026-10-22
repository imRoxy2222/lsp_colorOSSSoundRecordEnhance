import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 已内置 Kotlin 编译支持（built-in Kotlin），无需再应用 kotlin-android 插件，
    // 否则会报 "Cannot add extension with name 'kotlin'"。
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// ===== 自动版本号：无需每次手动修改 =====
// versionCode = 当前分支提交总数（每次提交自动 +1，保证单调递增）
// versionName = 最近的 v* tag（如 v1.2 -> "1.2"）；没有 tag 时回退为 "1.0.提交数"
fun gitCommitCount(): Int = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
} catch (e: Exception) {
    1
}

fun gitVersionName(): String = try {
    val tags = providers.exec {
        commandLine("git", "tag", "--list", "v*")
    }.standardOutput.asText.get().trim().lines()
        .map { it.removePrefix("v").trim() }
        .filter { it.matches(Regex("""\d+(\.\d+)*""")) }
    if (tags.isEmpty()) {
        "1.0.${gitCommitCount()}"
    } else {
        // 按语义化版本取最大：v1.10 > v1.9 > v1.2
        tags.maxByOrNull { t ->
            val p = t.split(".").map { it.toIntOrNull() ?: 0 }
            p.getOrElse(0) { 0 } * 1_000_000 + p.getOrElse(1) { 0 } * 1_000 + p.getOrElse(2) { 0 }
        } ?: "1.0.${gitCommitCount()}"
    }
} catch (e: Exception) {
    "1.0.${gitCommitCount()}"
}

// ===== 正式签名：密钥信息在 keystore.properties（已 gitignore，不进仓库） =====
// keystore.properties 不存在时（如 clone 后首次构建）自动回退 debug 签名
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasReleaseKeystore = keystoreProps["storeFile"] != null

android {
    namespace = "io.github.imroxy2222.lsp_colorOSRecord"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.imroxy2222.lsp_colorOSRecord"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCount()
        versionName = gitVersionName()
    }

    buildTypes {
        debug {
            // debug 构建：输出完整调试日志（由 BuildConfig.DEBUG 控制）
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                    storePassword = keystoreProps["storePassword"] as String
                    keyAlias = keystoreProps["keyAlias"] as String
                    keyPassword = keystoreProps["keyPassword"] as String
                }
            } else {
                // 便于直接安装 release 包进行验证；正式分发请配置 keystore.properties
                signingConfig = signingConfigs["debug"]
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 现代 Xposed API（libxposed，API 102）：仅编译期提供，运行时由 LSPosed 框架注入
    compileOnly(libs.libxposed.api)
    // 模块与框架通信（XposedProvider 等），需要打进 APK
    implementation(libs.libxposed.service)
    // DexKit：类名失效时按特征兜底定位
    implementation(libs.dexkit)
}
