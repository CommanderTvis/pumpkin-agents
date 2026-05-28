plugins {
    // Provides a toolchain repository so Gradle 9+ can auto-provision JDKs without the
    // deprecated implicit fallback (removed in Gradle 10).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pumpkin-agents"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include("plugin")
include("e2e-test")
