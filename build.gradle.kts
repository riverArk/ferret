plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    dependencyLocking { lockAllConfigurations() }
}

tasks.register("androidCheck") {
    dependsOn(":shared:allTests", ":androidApp:testDebugUnitTest", ":androidApp:lintDebug", ":androidApp:assembleDebug")
}

tasks.register("androidReleaseCheck") {
    dependsOn(":shared:allTests", ":androidApp:test", ":androidApp:lintRelease", ":androidApp:verifyReleaseSecurity")
}
