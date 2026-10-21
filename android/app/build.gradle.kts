import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.homedesk.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.homedesk.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0"
        manifestPlaceholders["appLabel"] = "Desk"
    }

    // Release credentials live outside the repository; debug has a separate identity.
    val signingFile = file(System.getenv("DESK_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.homedesk/signing.properties")
    val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
    fun signingValue(name: String): String? = System.getenv("DESK_" + name)
        ?: signing.getProperty(name)
    val releaseStore = signingValue("STORE_FILE")
    val hasReleaseSigning = !releaseStore.isNullOrBlank() &&
        listOf("STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").all { !signingValue(it).isNullOrBlank() }
    signingConfigs {
        if (hasReleaseSigning) create("privateRelease") {
            storeFile = file(releaseStore!!)
            storePassword = signingValue("STORE_PASSWORD")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Desk Dev"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("privateRelease")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Refuse accidental unsigned release packaging. CI always builds Desk Dev,
    // and builds the installable release only when private secrets are present.
    tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
        doFirst { check(hasReleaseSigning) { "Run python scripts/setup_signing.py or configure DESK signing secrets first." } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

