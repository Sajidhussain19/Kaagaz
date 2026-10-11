plugins {
    id("com.android.application") version "9.0.1" apply false
}

// OneDrive "Files On-Demand" turns build outputs into cloud placeholders, which breaks Gradle's incremental
// snapshots ("not a regular file"). When the checkout lives under OneDrive, build into %LOCALAPPDATA% instead.
if (rootDir.absolutePath.contains("OneDrive", ignoreCase = true)) {
    val outside = System.getenv("LOCALAPPDATA")?.let { file("$it/kaagaz-build") }
    if (outside != null) allprojects { layout.buildDirectory.set(outside.resolve(project.name)) }
}
