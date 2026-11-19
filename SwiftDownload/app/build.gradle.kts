plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swift.download"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swift.download"
        // v2.9.0：新版 jlibtorrent AAR（libtorrent 2.x）最低要求 Android 8.0；
        // 旧 1.2.0.18 的 NETLINK_ROUTE 在 Android 11+ 被 SELinux 拦截，BT 永远连不上节点。
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "2.9.0"
        ndk {
            // 只打包真机常用架构，避免 65MB 全架构 AAR 直接进 APK
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/swiftdl.jks")
            storePassword = "swiftdl2026"
            keyAlias = "swiftdl"
            keyPassword = "swiftdl2026"
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            // JVM 单测里 android.util.Log 等桩方法默认会抛 "not mocked"，
            // 这会让 catch 块在第一行 Log.w 就炸掉，错误回调永远发不出去。
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.11.0")
    // v2.9.0：FrostWire 官方 libtorrent 2.x AAR（含 arm64/arm .so + R8 keep rules）
    implementation("com.frostwire:jlibtorrent-android:2.1.2.1")

    testImplementation("junit:junit:4.13.2")
}
