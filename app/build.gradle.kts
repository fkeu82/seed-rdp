plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.accessrdp.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.accessrdp.client"
        minSdk = 21                      // 向下兼容老机型，惠及更多视障用户
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"

        // 【关键】只打包我们真正编译了 FreeRDP 原生库的 ABI。
        // 若不限制，AGP 会把依赖库（如 androidx.graphics.path）的 x86/x86_64 版本
        // 一并塞进 APK，导致 lib/x86_64/ 目录"看起来存在"但缺少 libfreerdp_client.so；
        // Android 在选 ABI 目录时可能优先挑中该目录，System.loadLibrary 直接抛
        // UnsatisfiedLinkError —— 表现为"安装正常、一打开就闪退"。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // 与 Kotlin 1.9.24 匹配的 Compose 编译器版本
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")

    // 如需离线单元测试粘滞键逻辑，可开启 testImplementation：
    // testImplementation("junit:junit:4.13.2")
}
