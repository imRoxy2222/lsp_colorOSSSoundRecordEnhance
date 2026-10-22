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

android {
    namespace = "io.github.imroxy2222.lsp_colorOSRecord"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.imroxy2222.lsp_colorOSRecord"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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
            // 便于直接安装 release 包进行验证（正式发布请换成自己的签名）
            signingConfig = signingConfigs["debug"]
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
