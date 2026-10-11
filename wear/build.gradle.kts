plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.kyungmin.kcoach.wear"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        create("ota") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    defaultConfig {
        applicationId = "dev.kyungmin.kcoach"
        minSdk = 30
        targetSdk = 35
        versionCode = 27
        versionName = "0.1.26"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/kmnoh12/kcoach/releases/latest/download/update-watch.json\"",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
