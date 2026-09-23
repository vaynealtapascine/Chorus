plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Build outputs can be redirected off the small system drive without moving source or caches.
System.getenv("CHORUS_GRADLE_BUILD_DIR")?.let { root ->
    allprojects {
        val directory = if (path == ":") "root" else path.trimStart(':').replace(':', '/')
        layout.buildDirectory.set(file("$root/$directory"))
    }
}
