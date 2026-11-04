plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.openqsy.glasses"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.openqsy.glasses"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 眼镜是 arm64（AR1），ML Kit 的 .so 占了大头：
        // 只保留 arm64-v8a 给真机，x86_64 留给模拟器调试；丢掉 armeabi-v7a / x86 可省一半体积。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            // 题库索引与 ML Kit 模型都很大，release 也不开混淆，避免模型/反射类被裁掉
            isMinifyEnabled = false
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
    // assets 里的 qbank.idx 不要压缩：读取时要一次读入内存，压缩会多一次解压开销
    androidResources {
        noCompress += "idx"
    }
    testOptions {
        unitTests {
            // IndexBuilderTest 要读项目根的 题库合并.json，路径由下面的 systemProperty 传入
            all {
                it.systemProperty(
                    "qbank.json",
                    rootProject.file("../../题库合并.json").absolutePath,
                )
            }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // 取景框：单绿屏上靠它让用户看清"相机正对着哪儿、认的是哪一块"
    implementation(libs.androidx.camera.view)

    // 离线中文 OCR（模型打进 APK，不需要网络 / Play 服务）
    implementation(libs.mlkit.text.recognition.chinese)
    // 离线二维码识别：换题库时用眼镜扫电脑上的链接二维码
    implementation(libs.mlkit.barcode.scanning)

    // 设备端编译题库索引：org.json 在真机上是系统自带，单元测试跑在 JVM 上要显式给实现
    testImplementation(libs.junit)
    testImplementation(libs.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
