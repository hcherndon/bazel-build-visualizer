import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

group = "com.holtherndon.bazelviz"
version = "0.1.0-SNAPSHOT"

// ADR-008: Java 25 LTS baseline (superseding ADR-002's Java 21); the toolchain
// is auto-provisioned via the foojay resolver declared in settings.gradle.kts.
// Preview features stay off — ADR-008 carries that constraint forward.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// JEP 472 direction: from Java 24 onward, calling a restricted method such as
// System::load from code that has not been granted native access prints a
// warning, and a future release will make it an error. Two runtime
// dependencies load native code this way — sqlite-jdbc (SQLiteJDBCLoader,
// exercised by :storage-sqlite tests) and FlatLaf (NativeLibrary, exercised by
// :ui-swing tests) — so every JVM this build forks needs the grant.
//
// ALL-UNNAMED is the only workable target here, not a shortcut: the project
// builds no module-info.java and every dependency, including sqlite-jdbc and
// FlatLaf, is placed on the classpath, so all of it lands in the unnamed
// module. A narrower `--enable-native-access=<module>` form has no module name
// to name until the application is modularized.
//
// Single source of truth for the flag. Test tasks get it below; the JavaExec
// tasks in `:benchmarks` and the `:app` run task and start scripts read it back
// out of `extra` so the string is written once. (`:benchmarks` produces no
// start scripts — it does not apply the application plugin.)
//
// This grant is load-bearing, not cosmetic: run without it under
// `--illegal-native-access=deny` and sqlite-jdbc does not merely warn, it
// throws `SQLException: Error opening connection` and the whole storage layer
// fails. That is the behavior a future JDK makes the default.
val enableNativeAccess = "--enable-native-access=ALL-UNNAMED"
extra["bbvNativeAccessArg"] = enableNativeAccess

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
    jvmArgs(enableNativeAccess)
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
