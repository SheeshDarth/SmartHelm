import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// Read MSG91 credentials from local.properties (never committed to git)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.smarthelm.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smarthelm.mobile"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "MSG91_AUTH_KEY",
            "\"${localProps.getProperty("MSG91_AUTH_KEY", "")}\"")
        buildConfigField("String", "MSG91_SENDER_ID",
            "\"${localProps.getProperty("MSG91_SENDER_ID", "SMHELM")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding   = true
        buildConfig   = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // MediaPipe Tasks Vision — same FaceLandmarker model as the Pi backend
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")        // PreviewView in CalibrationActivity

    // LifecycleService — Service that IS a LifecycleOwner (needed for CameraX)
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Unit tests (JVM) — FatigueScorer false-alarm logic
    testImplementation("junit:junit:4.13.2")
}
