@file:Suppress("UnstableApiUsage")
plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdk = 36
    namespace = "com.xposed.miuiime"

    defaultConfig {
        applicationId = "com.xposed.miuiime"
        minSdk = 28
        targetSdk = 36
        versionCode = 14
        versionName = "1.16"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += arrayOf("META-INF/**", "kotlin/**", "google/**", "**.bin")
        }
    }
    applicationVariants.all {
        val outputFileName = "Unlock_MIUI_IME-${versionName}_${buildType.name}.apk"
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
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.github.kyuubiran:EzXHelper:1.0.3")
    implementation("androidx.compose.foundation:foundation-android:1.10.1")
    implementation("androidx.compose.ui:ui-android:1.10.1")
    implementation("androidx.compose.ui:ui-graphics-android:1.10.1")
    implementation("io.github.kyant0:capsule:2.1.3")
}
