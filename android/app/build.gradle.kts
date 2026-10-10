plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.lrceditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lrceditor"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Optional local dev key so installs from different machines can update each other. Set in
    // ~/.gradle/gradle.properties (never in the repo): lrc.devKeystore, lrc.devKeystorePassword,
    // lrc.devKeyAlias, lrc.devKeyPassword. Without it the default Android debug key is used.
    val devKeystore = providers.gradleProperty("lrc.devKeystore").orNull?.let { file(it) }
    if (devKeystore != null && devKeystore.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = devKeystore
            storePassword = providers.gradleProperty("lrc.devKeystorePassword").get()
            keyAlias = providers.gradleProperty("lrc.devKeyAlias").get()
            keyPassword = providers.gradleProperty("lrc.devKeyPassword").get()
        }
    }

    val releaseKeystore = System.getenv("LRC_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LRC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LRC_KEY_ALIAS")
                keyPassword = System.getenv("LRC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")

    testImplementation("junit:junit:4.13.2")
}
