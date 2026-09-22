plugins {
    id("com.android.application")
}

val clinicKeystorePath = System.getenv("CLINIC_KEYSTORE_PATH")
val clinicKeystorePassword = System.getenv("CLINIC_KEYSTORE_PASSWORD")
val clinicKeyAlias = System.getenv("CLINIC_KEY_ALIAS")
val clinicKeyPassword = System.getenv("CLINIC_KEY_PASSWORD")
val hasReleaseSigning = !clinicKeystorePath.isNullOrBlank() &&
        !clinicKeystorePassword.isNullOrBlank() &&
        !clinicKeyAlias.isNullOrBlank() &&
        !clinicKeyPassword.isNullOrBlank()

android {
    namespace = "com.eman.clinic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eman.clinic"
        minSdk = 24
        targetSdk = 35
        versionCode = 25
        versionName = "1.14.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("clinicRelease") {
                storeFile = file(clinicKeystorePath!!)
                storePassword = clinicKeystorePassword
                keyAlias = clinicKeyAlias
                keyPassword = clinicKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("clinicRelease")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
