plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.kai.reader"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "io.github.kai.reader"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.1"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86"))
        }
        externalNativeBuild {
            cmake {
                arguments.add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                arguments.add("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { compose = true }
    // 回环 CA 与推理诊断只用于验收，普通 APK 使用 Android 默认的 TLS 信任配置。
    if (providers.gradleProperty("kaiStage4Fixture").orNull == "true") {
        sourceSets.getByName("debug") {
            res.directories.add("src/stage4Fixture/res")
        }
    } else if (providers.gradleProperty("kaiFixtureTls").orNull == "true") {
        sourceSets.getByName("debug") {
            res.directories.add("src/fixture/res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Lint 32.2.1 在 API 37.0 上未解析 SDK 基类。升级后复验 Activity 继承检查即可移除。
    compileOnly(files(androidComponents.sdkComponents.bootClasspath))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    testImplementation("junit:junit:4.13.2")
}
