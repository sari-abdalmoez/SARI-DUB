plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Secrets are read from local.properties (git-ignored) or environment variables.
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    "\"" + (localProps.getProperty(name) ?: System.getenv(name) ?: "") + "\""

android {
    namespace = "com.saridub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.saridub.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "2.1.0"
        buildConfigField("String", "TMDB_KEY", secret("TMDB_KEY"))
        buildConfigField("String", "TRAKT_CLIENT_ID", secret("TRAKT_CLIENT_ID"))
        buildConfigField("String", "GEMINI_KEY", secret("GEMINI_KEY"))
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // WebSocket client for Edge-TTS (Android has no built-in WebSocket client)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
