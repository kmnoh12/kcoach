plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.kyungmin.kcoach.phone"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "dev.kyungmin.kcoach"
        minSdk = 29
        targetSdk = 35
        versionCode = 25
        versionName = "0.5.11"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"http://192.168.200.105:8765/update.json\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
