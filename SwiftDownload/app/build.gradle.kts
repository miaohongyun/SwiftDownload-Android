plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.swift.download"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.swift.download"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "2.7.0"
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
    implementation(files("libs/jlibtorrent-1.2.0.18.jar"))

    testImplementation("junit:junit:4.13.2")
}
