plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.myhouse.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.myhouse.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "3.0.0-web"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("my-house.keystore")
            storePassword = "myhouse123"
            keyAlias = "myhouse"
            keyPassword = "myhouse123"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
