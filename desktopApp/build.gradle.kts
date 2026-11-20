import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.mp)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "dev.dsh.client.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "DSH"
            packageVersion = "1.1.1"
            description = "Native client for DeepSeek Harness"
            vendor = "DSH Native"
            modules("java.net.http", "jdk.crypto.ec", "java.naming", "jdk.unsupported")
            windows {
                menuGroup = "DSH"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "4f1c2a6e-9a5d-4d0b-8a7e-2b6c1d9e5f31"
            }
        }
    }
}

/** Headless screenshots of the real UI against a live server (see Screenshots.kt). */
tasks.register<JavaExec>("screenshots") {
    group = "dsh"
    mainClass.set("dev.dsh.client.desktop.ScreenshotsKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Djava.awt.headless=true", "-Dskiko.renderApi=SOFTWARE")
    environment("SHOT_DIR", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
