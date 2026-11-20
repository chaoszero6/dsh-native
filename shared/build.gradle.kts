plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.mp)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "dev.dsh.client.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
        withHostTest {}
    }
    jvm("desktop")

    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-opt-in=kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            api(libs.compose.icons)
            api(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.datetime)
            api(libs.lifecycle.viewmodel.compose)
            api(libs.lifecycle.runtime.compose)
            implementation(libs.markdown.m3)
            implementation(libs.markdown.coil3)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
            implementation(libs.security.crypto)
            implementation(libs.activity.compose)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jna.platform)
                implementation(libs.pdfbox)
            }
        }
    }
}
