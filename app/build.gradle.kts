plugins {
    id("com.android.application")
}

android {
    namespace = "com.eman.clinic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eman.clinic"
        minSdk = 24
        targetSdk = 35
        versionCode = 21
        versionName = "1.10.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
