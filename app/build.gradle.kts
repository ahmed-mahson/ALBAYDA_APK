plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") apply false
}

android {
    namespace = "com.albayda.court"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.albayda.court"
        minSdk = 23
        targetSdk = 34
        versionCode = 404
        versionName = "4.4-ExactTheme"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Firebase (real-time sync, offline, auth, push notifications)
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
}

// Apply google-services only when a valid config for this app is present.
// This keeps CI/local builds working if google-services.json is a placeholder
// or not yet correctly configured for com.albayda.court.
val googleServicesFile = file("google-services.json")
if (googleServicesFile.exists()) {
    val gs = googleServicesFile.readText()
    val isValidForApp = gs.contains("\"package_name\": \"com.albayda.court\"") &&
        !gs.contains("YOUR_PROJECT_ID") &&
        !gs.contains("REPLACE_WITH_YOUR_API_KEY") &&
        !gs.contains("WEB_API_KEY") &&
        !gs.contains("com.example.app")
    if (isValidForApp) {
        apply(plugin = "com.google.gms.google-services")
    }
}