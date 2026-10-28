import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Ondertekening: op GitHub uit de geheime instellingen, lokaal uit
// ../agenda-release.properties (staat buiten de repo).
val localSigning = Properties().apply {
    val file = rootProject.file("../agenda-release.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signing(name: String): String? = System.getenv(name) ?: localSigning.getProperty(name)

// Elke build op GitHub krijgt een hoger versienummer, zodat Android hem als update ziet.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "nl.timvanbergen.agenda"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "nl.timvanbergen.agenda"
        minSdk = 26
        targetSdk = 37
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            signing("AGENDA_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = signing("AGENDA_KEYSTORE_PASSWORD")
            keyAlias = signing("AGENDA_KEY_ALIAS")
            keyPassword = signing("AGENDA_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
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
