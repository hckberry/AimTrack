plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aimtrack"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aimtrack"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-draft"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
}
