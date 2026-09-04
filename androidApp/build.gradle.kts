import java.util.zip.ZipFile
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

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

abstract class VerifyReleaseSecurity : DefaultTask() {
    @get:InputFile abstract val manifestFile: RegularFileProperty
    @get:InputFile abstract val apkFile: RegularFileProperty
    @get:InputFile abstract val mappingFile: RegularFileProperty
    @get:InputFiles abstract val sourceDirectories: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val manifest = manifestFile.get().asFile.readText()
        require("""android:minSdkVersion="28""" in manifest)
        require("""android:targetSdkVersion="36""" in manifest)
        require("""android:allowBackup="false""" in manifest)
        require("""android:fullBackupContent="false""" in manifest)
        require("""android:usesCleartextTraffic="false""" in manifest)
        require("""android:debuggable="true""" !in manifest)

        ZipFile(apkFile.get().asFile).use { archive ->
            require(archive.getEntry("assets/NOTICE") != null) { "release APK is missing third-party notices" }
            require(archive.getEntry("classes.dex") != null) { "release APK is missing compiled application code" }
        }
        require(mappingFile.get().asFile.length() > 0) { "release APK was not minified" }

        val logging = sourceDirectories.files.asSequence().flatMap { directory ->
            directory.walkTopDown().filter { it.extension == "kt" }
        }.filter {
            Regex("""\b(android\.util\.Log|Log\.[dviwe]\(|println\(|System\.out)""").containsMatchIn(it.readText())
        }.toList()
        require(logging.isEmpty()) { "production logging is prohibited: ${logging.joinToString()}" }
    }
}

tasks.register<VerifyReleaseSecurity>("verifyReleaseSecurity") {
    dependsOn("assembleRelease")
    manifestFile.set(layout.buildDirectory.file(
        "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    ))
    apkFile.set(layout.buildDirectory.file("outputs/apk/release/androidApp-release-unsigned.apk"))
    mappingFile.set(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
    sourceDirectories.from(
        layout.projectDirectory.dir("src/main"),
        project(":shared").layout.projectDirectory.dir("src/commonMain"),
        project(":shared").layout.projectDirectory.dir("src/androidMain"),
    )
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.ktor.client.android)
}
