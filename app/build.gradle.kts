@file:Suppress("UnstableApiUsage")
plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 37
    namespace = "com.xposed.wetypehook"

    defaultConfig {
        applicationId = "com.xposed.wetypehook"
        minSdk = 31
        targetSdk = 37
        versionCode = 43
        versionName = "1.28.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += arrayOf("kotlin/**", "google/**", "**.bin")
        }
    }
    applicationVariants.all {
        val outputFileName = "WeType_Enhance-${versionName}_${buildType.name}.apk"
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = outputFileName
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    sourceSets.all {
        languageSettings.languageVersion = "2.0"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation-android:1.11.4")
    implementation("androidx.compose.ui:ui-android:1.11.4")
    implementation("androidx.compose.ui:ui-graphics-android:1.11.4")
    implementation("androidx.compose.ui:ui-text-android:1.11.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-shapes-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.0") {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-android")
    }
    implementation("io.github.kyant0:capsule:2.1.3")
    implementation("org.luckypray:dexkit:2.2.0")
    // S2 剪贴板搜索：TinyPinyin 轻量拼音（不引 jieba；原坐标 com.github.promeg:tinypinyin:2.0.3 已不可用——见信箱说明，改用同源 MavenCentral 坐标）
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE")
    // 自定义图片 Logo：SVG 栅格化（Apache-2.0）
    implementation("com.caverock:androidsvg:1.4")
    testImplementation("junit:junit:4.13.2")
    // 上游 HostPreferencesFileTest 在 JVM 单测需要 XmlPullParser 实现
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // android.jar 里的 org.json 在 JVM 单测是空壳（not mocked），手势绑定解析测试需要真实实现
    testImplementation("org.json:json:20240303")
}
