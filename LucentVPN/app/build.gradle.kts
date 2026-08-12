plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lucentvpn.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lucentvpn.android"
        // API 26 lets us ship a single adaptive (vector) launcher icon and gives
        // us the modern foreground-service + notification-channel APIs.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // ics-openvpn's :main module declares a flavour dimension called
        // "implementation" with the flavours "ovpn23" (full openvpn3 core) and
        // "skeleton" (no native code). We always want the real engine.
        missingDimensionStrategy("implementation", "ovpn23")

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    lint {
        // The VPN service intentionally uses APIs the linter flags as
        // privileged; we assert them explicitly in code instead.
        abortOnError = false
    }
}

dependencies {
    // ---- The VPN tunnel engine -------------------------------------------
    // Compiled from source into this APK. Provides de.blinkt.openvpn.*
    implementation(project(":openvpn"))

    // ---- AndroidX / Compose ---------------------------------------------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Coroutines ------------------------------------------------------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- Tests -----------------------------------------------------------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
