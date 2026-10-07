# Android client

Primary implementation target: native Android with Kotlin.

Create the app here with Android Studio after its SDK setup wizard completes. Use one current stable compile SDK, the matching build-tools and platform-tools, and Android Studio's bundled JDK unless the generated project requires another version.

Suggested package boundaries: `ingest`, `ocr`, `classify`, `extract`, `verify`, `evidence`, `timeline`, `qa`, and `storage`.

Avoid adding API keys to Gradle files or resources. Prefer on-device OCR and synthetic sample documents for the demo.
