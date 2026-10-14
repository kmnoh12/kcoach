plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
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
        versionCode = 35
        versionName = "0.7.5"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/kmnoh12/kcoach/releases/latest/download/update-phone.json\"",
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources { noCompress += "task" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildTypes {
        // Shrunk build for in-app updates: same debug key, so it installs over debug builds.
        create("ota") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
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
}

dependencies {
    implementation(project(":updates"))
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    constraints {
        implementation("androidx.fragment:fragment:1.3.6") {
            because("CameraX brings Fragment 1.1.0; ActivityResult camera permissions require Fragment 1.3.0 or newer")
        }
    }
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
