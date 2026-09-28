import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 包名一旦提交 APP 备案不可更改;正式包名请在此处与 applicationId 一并定死后再生成签名证书
    namespace = "com.luxofilms.vision"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luxofilms.vision"
        minSdk = 26            // 跟门店APP保持一致的机型下限
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        buildConfigField("String", "DEFAULT_SERVER", "\"\"")   // 留空:由扫码二维码给出服务器地址

        // ML Kit本地OCR的原生库默认打包x86/x86_64/arm64-v8a/armeabi-v7a四份(单份约11MB),
        // 但这是装在门店实体安卓手机上的App,只会用到arm64-v8a/armeabi-v7a,x86系列是给
        // 电脑模拟器用的——只打包真机会用到的架构,APK能小一半还多。
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // 正式签名:证书放在仓库外,路径与口令写在 keystore.properties(已 gitignore);缺文件时退回 debug 签名
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("keystore.properties").exists()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true; viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")   // 设备 token 加密存储
    implementation("com.squareup.okhttp3:okhttp:4.12.0")                 // HTTP
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")       // 扫码绑定(仅相机取景)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")         // CaptureService 继承 LifecycleService

    // CameraX:声明式相机用例,ImageAnalysis 自带丢帧策略,省去自管 Camera2 会话的样板代码
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 本地OCR兜底(2026-09-23):云端Ark视觉大模型账号权限问题没排查出来前,先在设备本地识别文字,
    // 不经过Ark。用独立包(不是 Play服务版)的中文识别模型,离线可用,不依赖首次联网下载模型。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
