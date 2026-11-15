import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Keep AGP's built-in Kotlin aligned with the pure JVM network module.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmTarget.get().toInt()))
    // Nix and CI provide the JDK; do not generate toolchain provisioning URLs.
    toolchainDownloadUrls.set(emptyMap())
}
