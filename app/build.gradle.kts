import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("kotlin-kapt")
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.autoglm.autoagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoglm.autoagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 150
        versionName = "3.5.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // 只保留真实手机使用的 ARM 架构，移除模拟器用的 x86
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 读取 secrets.properties 作为默认配置
        val secretsFile = rootProject.file("secrets.properties")
        val secrets = Properties()
        if (secretsFile.exists()) {
            secretsFile.inputStream().use { secrets.load(it) }
        }

        // 安全地获取并转义属性值，防止反斜杠或引号破坏 Java 源码
        // 同时自动剥离值两端可能存在的多余双引号
        fun getSecret(key: String, default: String): String {
            val rawValue = secrets.getProperty(key, default).trim()
            val value = if (rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2) {
                rawValue.substring(1, rawValue.length - 1)
            } else {
                rawValue
            }
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        buildConfigField("String", "ZHIPU_API_KEY", getSecret("ZHIPU_API_KEY", ""))
        buildConfigField("String", "ZHIPU_BASE_URL", getSecret("ZHIPU_BASE_URL", "https://open.bigmodel.cn/api/paas/v4/"))
        buildConfigField("String", "ZHIPU_MODEL", getSecret("ZHIPU_MODEL", "glm-4-flash"))
        
        buildConfigField("String", "EDGE_API_KEY", getSecret("EDGE_API_KEY", ""))
        buildConfigField("String", "EDGE_BASE_URL", getSecret("EDGE_BASE_URL", ""))
        buildConfigField("String", "EDGE_MODEL", getSecret("EDGE_MODEL", ""))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true  // Enable AIDL for shell service communication
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

// 移除旧的 server.dex 构建逻辑，高级模式已迁移至 Shizuku Binder 模式

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    
    // 强制对齐 Animation 版本以修复 CircularProgressIndicator 崩溃
    implementation("androidx.compose.animation:animation:1.6.7")
    implementation("androidx.compose.animation:animation-core:1.6.7")
    implementation("androidx.compose.animation:animation-graphics:1.6.7")
    
    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // Sherpa-ONNX (local AAR)
    implementation(files("libs/sherpa-onnx.aar"))
    
    // Shizuku（高级模式）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    
    // Hidden API Bypass (允许反射系统私有API)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
