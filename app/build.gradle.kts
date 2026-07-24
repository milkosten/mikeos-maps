plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mikeos.maps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mikeos.maps"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-maps"

        // MikeDaemon runs ON the phone (loopback). Auth token is pinned for dev.
        buildConfigField("String", "DAEMON_BASE_URL", "\"https://127.0.0.1:7743\"")
        buildConfigField(
            "String",
            "DAEMON_TOKEN",
            "\"7bdc23451b18b5801036f992b66a872670975d19\""
        )

        // mikeos-trips-cloud: per-user journeys + speed samples + congestion aggregation.
        // Valid public TLS (Railway) — standard client. X-API-KEY = this app's hive agent
        // key. /api/route is KEYLESS; /api/trips* need the key.
        buildConfigField(
            "String",
            "TRIPS_CLOUD_URL",
            "\"https://mikeos-trips-cloud-production.up.railway.app\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Background heartbeat
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // DNS-over-HTTPS: resolve cloud hostnames via Cloudflare even when the phone's
    // system DNS is broken (this GApps-less ROM / flaky cellular fails getaddrinfo).
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading for the per-sighting crop photos (photos-cloud). Coil downsamples
    // to the display size, so it never loads a full-res image into RAM (memory rule).
    implementation("io.coil-kt:coil-compose:2.7.0")

    // MikeAgent runtime (vendored source under com.mikeos.core.*) — Room-backed soul memory.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
