# Kaagaz Android

Minimal native Android/Kotlin starter used to verify the complete build, install, launch, and logging pipeline.

## Toolchain

- Android Gradle Plugin 9.0.1
- Gradle 9.1.0
- Built-in Kotlin 2.2.10
- JDK 17
- compileSdk / targetSdk 36
- minSdk 26

## Build

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The starter uses only Android platform APIs. It intentionally has no UI framework, OCR, networking, analytics, or AI dependencies yet.
