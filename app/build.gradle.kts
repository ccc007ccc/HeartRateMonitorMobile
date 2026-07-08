plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt") // 新增
}

android {
    signingConfigs {
        create("release") {
            storeFile =
                file("../.key/key")
            storePassword = "123456"
            keyAlias = "key0"
            keyPassword = "123456"
        }
    }
    namespace = "com.example.heart_rate_monitor_mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.heart_rate_monitor_mobile"
        minSdk = 27
        targetSdk = 35
        versionCode = 6
        versionName = "1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        signingConfig = signingConfigs.getByName("release")

        // 仅编译 arm64-v8a，移除 32 位（armeabi-v7a）和 x86/x86_64 兼容
        // 现代设备（minSdk 27）几乎全部为 arm64-v8a，可显著减小 APK 体积
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
             // 开启代码混淆和压缩
            isMinifyEnabled = true
            // 开启资源压缩
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
    kapt { // 新增
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
}

dependencies {
    // Room (新增)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // MPAndroidChart (新增)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ViewModel and LiveData for modern UI development
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Kable for Bluetooth LE
    implementation("com.juul.kable:core-android:0.32.0")

    // Permissions
    implementation("com.guolindev.permissionx:permissionx:1.8.1")

    implementation("com.github.skydoves:colorpickerview:2.3.0")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}