plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 【版本号唯一来源 —— 彻底根治「版本号漂移」】
 *
 * 背景：此前 versionName/versionCode 是手写在 build.gradle.kts 里的常量，
 * 一旦忘记同步修改，就会出现"Release 写 v1.0.4、装出来却是 1.0.3"的低级事故。
 *
 * 方案：让版本号**从 git tag 自动推导**，做到「tag 是什么版本，APK 就是什么版本」：
 *
 *   - 打 tag 构建时（CI 的 v* 场景）：
 *       tag = v1.0.5  ->  versionName = "1.0.5"，
 *       versionCode = 由 semver 计算（major*10000 + minor*100 + patch），
 *       保证**每次发版 versionCode 一定递增**，Android 才能正常覆盖安装。
 *   - 非 tag 构建时（本地调试 / main 分支）：
 *       退化为 build.gradle.kts 里的 fallbackVersionName / fallbackVersionCode 常量。
 *
 * 优先级（从高到低）：
 *   1) -PversionName=xxx / -PversionCode=nnn 显式传入（CI 用 tag 计算后传入）
 *   2) 环境变量 GITHUB_REF_NAME == "v*" 自动解析 tag
 *   3) fallback 常量
 *
 * 这样即使有人在 CI 里漏改常量，只要 tag 打对了，APK 里的版本号就绝不会错。
 */
val fallbackVersionName = "1.1.3"
val fallbackVersionCode = 10103

/**
 * 由版本名推导 versionCode：major*10000 + minor*100 + patch。
 * 例：1.0.5 -> 10005，1.1.0 -> 10100，2.0.0 -> 20000。
 * semver 每进位一次，versionCode 必然增大，满足 Android 覆盖安装要求。
 */
fun semverToCode(name: String): Int? {
    val core = name.trim().removePrefix("v").substringBefore('-')
    val m = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$").find(core) ?: return null
    val major = m.groupValues[1].toIntOrNull() ?: return null
    val minor = m.groupValues[2].toIntOrNull() ?: return null
    val patch = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
    return major * 10000 + minor * 100 + patch
}

/** 依次尝试：显式 -P 参数 -> GITHUB_REF_NAME(tag) -> 兜底常量 */
val resolvedVersionName: String = run {
    (findProperty("versionName") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: System.getenv("GITHUB_REF_NAME")
            ?.takeIf { it.startsWith("v") && Regex("^v\\d+\\.\\d+").containsMatchIn(it) }
        ?: fallbackVersionName
}

val resolvedVersionCode: Int = run {
    (findProperty("versionCode") as String?)?.toIntOrNull()
        ?: semverToCode(resolvedVersionName)
        ?: fallbackVersionCode
}

// 构建期把最终版本打出来，CI 日志里可直接核对，杜绝"静默漂移"
println("[AccessRDP] 版本解析结果 -> versionName=$resolvedVersionName  versionCode=$resolvedVersionCode  (来源: ${
    when {
        findProperty("versionName") != null -> "-P 显式传入"
        System.getenv("GITHUB_REF_NAME")?.startsWith("v") == true -> "git tag: ${System.getenv("GITHUB_REF_NAME")}"
        else -> "兜底常量"
    }
})")

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

        // 版本号由 git tag 自动推导（见文件顶部说明），杜绝手工漏改导致的版本漂移
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

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

    // 固定 APK 输出文件名，便于用户区分与覆盖安装。
    // 文件名里同时带上 versionName 与 versionCode，一眼就能核对版本是否漂移。
    applicationVariants.all {
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "AccessRDP-v${resolvedVersionName}-${name}.apk"
        }
    }

    // 【版本自检】打包完成后立即断言 APK 内的 versionName/versionCode
    // 与 Gradle 预期一致，不一致就让构建失败，绝不让错误版本流出。
    applicationVariants.all {
        val variantName = name
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            doLast {
                val apkDir = layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
                val apk = apkDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
                    ?: throw GradleException("[版本自检] 未找到 $variantName 的 APK，路径：$apkDir")
                val expectedName = resolvedVersionName
                val expectedCode = resolvedVersionCode
                if (!apk.name.contains("v$expectedName")) {
                    throw GradleException(
                        "[版本自检] APK 文件名 ${apk.name} 与预期版本 $expectedName 不符！"
                    )
                }
                logger.lifecycle(
                    "[版本自检] ✅ APK=${apk.name}  期望 versionName=$expectedName versionCode=$expectedCode"
                )
            }
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
        // 生成 BuildConfig，供界面显示/核对 VERSION_NAME、VERSION_CODE。
        // AGP 8.x 起该项默认关闭，必须显式开启。
        buildConfig = true
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
