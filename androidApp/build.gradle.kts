import java.util.Properties

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
        versionCode = 3
        versionName = "1.1.1"
    }
    // Release signing: a keystore.properties (storeFile, storePassword, keyAlias, keyPassword) kept outside the
    // repo, located via -PdshSigning=<file>, env DSH_SIGNING, or ~/.dsh-signing/keystore.properties.
    // Without one, release builds fall back to the debug key (fine for local testing, not for distribution).
    val signingProps = (providers.gradleProperty("dshSigning").orNull ?: System.getenv("DSH_SIGNING")
        ?: "${System.getProperty("user.home")}/.dsh-signing/keystore.properties").let(::file)
    val releaseSigning = if (signingProps.isFile) {
        val p = Properties().apply { signingProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    } else null
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
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
