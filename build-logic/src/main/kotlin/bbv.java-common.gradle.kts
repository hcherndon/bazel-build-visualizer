import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

group = "com.holtherndon.bazelviz"
version = "0.1.0-SNAPSHOT"

// ADR-002: Java 21 LTS baseline; the toolchain is auto-provisioned via the
// foojay resolver declared in settings.gradle.kts.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("slf4j-api").get())
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// ADR-003: reproducible archives.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ADR-003: dependency locking. Regenerate with:
//   ./gradlew resolveAndLockAll --write-locks --no-configuration-cache
dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("resolves configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}
