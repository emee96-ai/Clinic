plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
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
        versionCode = 36
        versionName = "1.25.0"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("net.zetetic:sqlcipher-android:4.17.0@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    testImplementation("junit:junit:4.13.2")
}
