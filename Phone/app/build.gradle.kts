plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.bi2qfa.sonyconnect"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // ★ 发布包名（GitHub 组织命名）：与代码包 namespace 分离是 AGP 的标准做法，
        //   全部源码的 package 声明无需跟着动。安装器身份以它为准。
        applicationId = "io.github.bi2qfa.sonyconnect"
        minSdk = 31          // Android 12（需求指定）
        targetSdk = 36
        // 2.5 线按全新安装发布：两端统一 versionCode=1。
        versionCode = 1
        versionName = "2.5"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // EXIF 方向与拍摄参数读取（竖屏照片的方向还原、预览器"照片信息"弹窗）
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
