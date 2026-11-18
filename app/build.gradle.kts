import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Play upload key (signing/, kept out of version control). Without it, release builds fall back to the debug key.
val uploadKey = rootProject.file("signing/keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.oneuiclone.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.launcheryou"
        minSdk = 33
        targetSdk = 36
        versionCode = 180
        versionName = "0.180"
    }

    signingConfigs {
        if (uploadKey != null) create("upload") {
            storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // widget picker: a recycled list stays smooth with hundreds of widgets
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // installs src/main/baseline-prof.txt for sideloaded APKs (no Play cloud profiles)
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    // Jetpack Compose (compatible with compileSdk 36)
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    // BitChord's frosted progressive blur engine (Haze)
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")
}
