import java.io.File

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
        // FCM (D-096): the operator's Firebase project, read from their google-services.json (kept
        // out of the public repo) into the resource names Firebase looks for at startup. Without
        // the file the app builds as before and phones use a push app or the 5-minute check.
        val fcmConfig = (providers.gradleProperty("chorus.fcmConfig").orNull ?: System.getenv("CHORUS_FCM_CONFIG"))
            ?.let(::File) ?: File(System.getProperty("user.home"), "selfhost/fcm/google-services.json")
        if (fcmConfig.isFile) {
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parse(fcmConfig) as Map<String, Any?>
            val project = json["project_info"] as Map<String, Any?>
            val client = (json["client"] as List<Map<String, Any?>>).first {
                ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "garden.vayne.chorus"
            }
            resValue("string", "google_app_id", (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"].toString())
            resValue("string", "google_api_key", ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"].toString())
            resValue("string", "gcm_defaultSenderId", project["project_number"].toString())
            resValue("string", "project_id", project["project_id"].toString())
        }
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
    buildFeatures { compose = true; resValues = true }
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
    implementation(libs.firebase.messaging)
    implementation(libs.code.scanner)
    testImplementation(libs.junit)
    testImplementation(libs.jna) // host JNA for tests that exercise the real shared composer
    // the real org.json for JVM tests (Android's is a stub there)
    testImplementation(libs.orgjson)
}

tasks.withType<Test>().configureEach {
    val cargoTarget = System.getenv("CARGO_TARGET_DIR")?.let { File(it) } ?: rootProject.file("../target")
    val profile = if (System.getenv("CHORUS_FFI_PROFILE") == "release") "release" else "debug"
    systemProperty("jna.library.path", cargoTarget.resolve(profile).absolutePath)
    val hostLibrary = when {
        System.getProperty("os.name").startsWith("Windows") -> "chorus_ffi.dll"
        System.getProperty("os.name").startsWith("Mac") -> "libchorus_ffi.dylib"
        else -> "libchorus_ffi.so"
    }
    inputs.file(cargoTarget.resolve("$profile/$hostLibrary"))
        .withPropertyName("chorusCoreHostLibrary").withPathSensitivity(PathSensitivity.NONE)
    val fixtures = rootProject.file("../fixtures")
    systemProperty("chorus.fixtures", fixtures.absolutePath)
    inputs.file(fixtures.resolve("front_runtime/channel_pools.json"))
        .withPropertyName("chorusFrontRuntimeFixture").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(fixtures.resolve("ui_chat_visible.json"))
        .withPropertyName("chorusChatVisibleFixture").withPathSensitivity(PathSensitivity.RELATIVE)
}

kapt {
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}
