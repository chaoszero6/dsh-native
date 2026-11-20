plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.dsh.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dsh.client"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so the release APK can be sideloaded without a keystore; replace for store builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
}
