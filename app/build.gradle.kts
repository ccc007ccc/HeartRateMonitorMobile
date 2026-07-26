import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// 签名解析顺序：keystore.properties（可选覆盖，gitignore）→ 环境变量 → 仓库内置 .key/key。
// 内置密钥为项目维护者决策：保证任何人克隆编译出的 APK 与官方签名一致（家庭局域网生态，
// 社区自编译可直接覆盖安装官方版本）。密钥文件缺失时 release 回退 debug 签名。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE") ?: ".key/key"
val releaseStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD") ?: "123456"
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS") ?: "key0"
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD") ?: "123456"
val hasReleaseSigning = rootProject.file(releaseStoreFile).exists()

android {
    namespace = "com.example.heart_rate_monitor_mobile"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.heart_rate_monitor_mobile"
        minSdk = 27
        targetSdk = 36
        versionCode = 8
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 仅编译 arm64-v8a，移除 32 位（armeabi-v7a）和 x86/x86_64 兼容
        // 现代设备（minSdk 27）几乎全部为 arm64-v8a，可显著减小 APK 体积
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties/环境变量未提供 release 签名，release 构建回退 debug 签名")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // App 为中英双语（默认英文 values/，中文 values-zh/），过滤依赖库自带的 85+ 种语言翻译；
        // 保留 zh-rCN 以复用 appcompat/material 等库自带的简体中文资源
        localeFilters += listOf("zh", "zh-rCN", "en")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kable)
    implementation(libs.mpandroidchart)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.colorpickerview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
