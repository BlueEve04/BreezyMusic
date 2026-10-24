import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// 签名信息从本地 keystore.properties 读取（该文件不入版本库，避免口令泄露）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists() && keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.breezy.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.breezy.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 2600
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // BreezyUI 本地库
    implementation(project(":breezy-ui"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.04.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation:1.6.7")

    // Activity
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // DocumentFile (SAF URI 解析)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Media (MediaSession + MediaStyle 通知)
    implementation("androidx.media:media:1.7.0")

    // Media3 ExoPlayer（提供 AudioProcessor 扩展点，用于挂载自研 DSP 音频链）
    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-extractor:$media3Version")

    // 歌词外发：Super Lyric API（Binder/AIDL 广播，供状态栏歌词等外部「歌词获取器」读取）
    implementation("com.github.HChenX:SuperLyricApi:3.7")

    // Room (持久化数据库)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Animation
    implementation("androidx.compose.animation:animation")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 单元测试（BPM 分析、排序、组合匹配等纯逻辑均在 JVM 上可测）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// ═══════════════════════════════════════════════════════════════
// 依赖版本固定
//
// 背景：SuperLyricApi（JitPack）以 Kotlin 2.x 构建，会把 kotlin-stdlib 2.2.x 与
// androidx.annotation 1.10.0 传递进本项目。本项目使用 AGP 8.2.2 + Kotlin 1.9.22，
// kapt 无法读取 2.x 的 .kotlin_module 元数据（报 "binary version of its metadata
// is 2.2.0, expected version is 1.9.0"）。SuperLyricApi 只用到 Binder/AIDL，
// 不依赖 Kotlin 2.x 新特性，因此把传递依赖钉回兼容版本，无需升级整条工具链。
// ═══════════════════════════════════════════════════════════════
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
        force("androidx.annotation:annotation:1.7.1")
        force("androidx.annotation:annotation-jvm:1.7.1")
    }
}
