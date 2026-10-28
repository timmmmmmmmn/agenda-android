plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "nl.timvanbergen.agenda"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "nl.timvanbergen.agenda"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
}
