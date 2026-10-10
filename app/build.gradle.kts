plugins {
    id("com.android.application")
}

android {
    // 包名三处之一（改包名时 namespace / applicationId / Java 包要一起改，详见 README）
    namespace = "com.jmcomic.hook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jmcomic.hook"
        minSdk = 27          // LSPosed 最低支持 Android 8.1 = API 27
        targetSdk = 35
        // 模块每次发布必须 versionCode+1：DexKit“模块更新”触发器靠它感知更新
        versionCode = 6
        versionName = "1.2.3"
    }

    buildTypes {
        release {
            // 初学阶段保持关闭；功能跑通要发布时再开，开则必须配 proguard-rules.pro
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // 强制 extractNativeLibs=true：模块被注入到目标 App 进程时，
            // libdexkit.so 必须被解压到 nativeLibraryDir，System.loadLibrary 才能找到
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        // 生成 BuildConfig.VERSION_CODE —— DexKit 触发器靠它感知“模块已更新”
        buildConfig = true
    }
}

dependencies {
    // libxposed 现代 API 102（Maven Central）。
    // compileOnly = 只参与编译、不打进 APK（Xposed API 的铁律，绝不能写 implementation）
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit 2.3.0（Maven Central，2026-09 最新 release）：运行时在 dex 里定位混淆方法。
    // 与 Xposed API 相反——它必须 implementation 真正打进 APK："永远 compileOnly" 只约束 Xposed API
    implementation("org.luckypray:dexkit:2.3.0")
}
