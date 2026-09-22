# Chorus for Android

Native Kotlin + Jetpack Compose (D-032). Package `garden.vayne.chorus`, minSdk 29, targetSdk 36.

## Build

```powershell
# 1. Rust core → android/core-bridge/src/main/{jniLibs,kotlin/uniffi} (both git-ignored)
pwsh scripts/build-android-core.ps1          # -Debug for a faster, larger build

# 2. Design tokens → designsystem/.../Tokens.kt (checked in; rerun after editing design/tokens.json)
node scripts/gen-tokens.mjs

# 3. The app (JDK 17+: Android Studio's JBR; Gradle cache shared with Dun on F:)
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'F:\DunBuild\gradle'
cd android; .\gradlew.bat assembleDebug
```

## Modules

| Module | What |
| --- | --- |
| `app` | Activities, navigation, features (see docs/CLIENTS.md §2.2 for the planned split) |
| `core-bridge` | UniFFI bindings of `crates/chorus-ffi` + native libs; exposes `uniffi.chorus_ffi.*` |
| `designsystem` | Generated tokens and `ChorusTheme` |

Versions are pinned in `gradle/libs.versions.toml` and recorded in docs/DECISIONS.md.
