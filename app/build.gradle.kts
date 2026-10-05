plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 【固定签名配置】
 *
 * 问题背景：GitHub Actions 每次构建都会重新生成一个随机 debug.keystore，
 * 导致每个 APK 的签名都不同 —— 用户安装新版时系统报「签名冲突」，
 * 必须先卸载旧版（丢数据）才能装。
 *
 * 解决方案：仓库内固定一个 keystore，debug 与 release 一律用它签名，
 * 从此每次打出来的 APK 签名完全一致，可以直接覆盖安装。
 *
 * keystore 位置：<repo>/keystore/accessrdp-release.jks
 * 证书指纹(SHA-256)：95:97:18:E4:82:EB:32:41:B7:7D:72:17:87:32:73:DB:12:D6:AC:58:F2:D0:0D:6F:B0:58:D3:F8:52:9E:1B:37
 * 有效期至：2056 年
 *
 * 说明：这是**自签名开发者证书**，只用于保证"签名稳定/可覆盖安装"，不用于应用商店发布，
 * 因此把它随仓库一起提交是刻意的选择（CI 无需任何 Secrets 即可复现同一签名）。
 * 若日后要上架应用商店，请换成你自己的正式发布证书并通过 Secrets 注入。
 */
val fixedKeystoreFile = rootProject.file("keystore/accessrdp-release.jks")
val fixedKeystoreExists = fixedKeystoreFile.exists()

android {
    namespace = "com.accessrdp.client"
    compileSdk = 34

    signingConfigs {
        if (fixedKeystoreExists) {
            create("fixed") {
                storeFile = fixedKeystoreFile
                storePassword = "accessrdp2024"
                keyAlias = "accessrdp"
                keyPassword = "accessrdp2024"
                // 同时开启 v1/v2/v3 签名，兼容 Android 7 以下到最新系统
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.accessrdp.client"
        minSdk = 21                      // 向下兼容老机型，惠及更多视障用户
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"

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
        debug {
            // debug 包也固定签名，保证 debug/release 互相覆盖安装都不冲突
            if (fixedKeystoreExists) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        release {
            isMinifyEnabled = false
            if (fixedKeystoreExists) {
                signingConfig = signingConfigs.getByName("fixed")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 固定 APK 输出文件名，便于用户区分与覆盖安装
    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "AccessRDP-${versionName}-${name}.apk"
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
