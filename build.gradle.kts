// Top-level build file.
//
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the org.jetbrains.kotlin.android plugin is
// intentionally NOT applied anywhere. AGP 9.4 has a runtime dependency on KGP 2.2.10; putting newer
// KGP/KSP artifacts on the build classpath is the documented way to upgrade the Kotlin compiler.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
