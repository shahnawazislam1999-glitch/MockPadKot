plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raj.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raj.app.mockpad"
        minSdk = 29
        targetSdk = 34
        // Every GitHub build gets a higher number, so a new APK always installs over the old one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.0"
    }

    // Fixed personal signing key (kept in the repo on purpose) so updates install over older builds.
    signingConfigs {
        create("release") {
            storeFile = file("raj-hub.jks")
            storePassword = "rajhub123"
            keyAlias = "rajhub"
            keyPassword = "rajhub123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")

    // Native video player. 1.4.1 is the newest Media3 that still builds with compileSdk 34 / AGP 8.5.2.
    // (Media3 1.5.x needs compileSdk 35, which is what broke the GitHub build before.)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
