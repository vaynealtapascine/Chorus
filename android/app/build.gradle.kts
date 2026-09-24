plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "garden.vayne.chorus"
    compileSdk = 36
    defaultConfig {
        applicationId = "garden.vayne.chorus"
        minSdk = 29
        targetSdk = 36
        // every commit is a newer build, so in-app updates (M12.3) always move forward
        val commits = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
            .standardOutput.asText.get().trim().toIntOrNull() ?: 1
        versionCode = commits
        versionName = "0.1.$commits"
    }
    // The published APK (GitHub releases, D-072) is signed with the release key, which CI gets from
    // Infisical (`.github/workflows/release-android.yml`); other builds use this PC's debug key so
    // they stay upgradable in place (D-060).
    val releaseStore = System.getenv("CHORUS_SIGNING_STORE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storeType = System.getenv("CHORUS_SIGNING_STORE_TYPE") ?: "pkcs12"
                storePassword = System.getenv("CHORUS_SIGNING_PASSWORD")
                keyAlias = System.getenv("CHORUS_SIGNING_ALIAS") ?: "chorus"
                keyPassword = System.getenv("CHORUS_SIGNING_KEY_PASSWORD") ?: System.getenv("CHORUS_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(project(":designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    kapt(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    // the real org.json for JVM tests (Android's is a stub there)
    testImplementation(libs.orgjson)
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}
