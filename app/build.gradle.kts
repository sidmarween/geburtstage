plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Jeder GitHub-Build bekommt automatisch eine höhere Versionsnummer
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "de.marween.geburtstage"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.marween.geburtstage"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fester Schlüssel, damit neue Versionen als Update installiert werden können
    signingConfigs {
        create("release") {
            storeFile = file("geburtstage.keystore")
            storePassword = "geburtstage-marween"
            keyAlias = "geburtstage"
            keyPassword = "geburtstage-marween"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}
