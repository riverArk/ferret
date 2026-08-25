plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val googleServerClientId = providers.environmentVariable("FERRET_GOOGLE_SERVER_CLIENT_ID")
val buildCommit = providers.environmentVariable("FERRET_BUILD_COMMIT").getOrElse("development").also {
    require(it == "development" || Regex("[0-9a-f]{7,40}").matches(it)) { "FERRET_BUILD_COMMIT must be a lowercase git commit" }
}
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    require(googleServerClientId.getOrElse("").isNotBlank()) { "FERRET_GOOGLE_SERVER_CLIENT_ID is required for release builds" }
}

android {
    namespace = "io.riverark.ferret"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.riverark.ferret"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${googleServerClientId.getOrElse("")}\"")
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}


dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.ktor.client.android)
}
