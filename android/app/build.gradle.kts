plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.lrceditor"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lrceditor"
        minSdk = 36
        targetSdk = 36
        versionCode = 6
        versionName = "0.0.6"
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
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    testImplementation("junit:junit:4.13.2")
}
