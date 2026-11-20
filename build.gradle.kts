plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.mp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
}

/** Re-export the DSH contract and regenerate Kotlin API stubs: ./gradlew regenContract -PdshRoot=/path/to/deepseek-harness */
tasks.register<Exec>("regenContract") {
    group = "dsh"
    val dshRoot = providers.gradleProperty("dshRoot").getOrElse("/root/desktop/deepseek-harness")
    commandLine(
        "bash", "-c",
        "node tools/contract-export/export.mjs '$dshRoot' contract && " +
            "node tools/codegen/generate.mjs contract/dsh-contract.json shared/src/commonMain/kotlin/dsh/gen",
    )
}
