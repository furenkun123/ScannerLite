@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.scanner.lite"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.scanner.lite"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // 核心优化 1：只保留 arm64-v8a 架构（大幅瘦身 .so 动态库）
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "@string/app_name_dev"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }

        release {
            manifestPlaceholders["appName"] = "@string/app_name"

            // 核心优化 2：开启 R8 代码混淆裁剪与无用资源剔除
            isMinifyEnabled = true
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

    buildFeatures {
        compose = true
    }
}

// APK 重命名逻辑
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("ScannerLite_v${android.defaultConfig.versionName}_${variant.name}.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX 相机
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit：条码二维码 + 中文OCR
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition.chinese)

    // 图标库
    implementation(libs.androidx.compose.material.icons.extended)

    // ML Kit：翻译 + 语言检测
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
}