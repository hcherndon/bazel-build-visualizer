pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the Java 21 toolchain (ADR-002) on machines that lack it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
}

rootProject.name = "bazel-build-visualizer"

include(
    "app",
    "ui-swing",
    "core-model",
    "bazel-runner",
    "capture-bes",
    "capture-file",
    "bep-codec",
    "storage-sqlite",
    "enrichment",
    "analysis-core",
    "graph-core",
    "session-format",
    "test-support",
    "benchmarks",
    "proto",
)
