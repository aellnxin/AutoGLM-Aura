plugins {
    id("com.android.library")
}

android {
    namespace = "com.autoglm.hiddenapi"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }
    
    // 关键：不生成 BuildConfig，减少干扰
    buildFeatures {
        buildConfig = false
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.7.0")
}
