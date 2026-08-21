plugins {
    id("bbv.java-common")
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":test-support"))
    implementation(project(":ui-swing"))
    implementation(project(":graph-core"))
    implementation(project(":storage-sqlite"))
    runtimeOnly(libs.logback.classic)

    // The jmh source set does not inherit main's project dependencies; wire
    // the ones the benchmarks actually use.
    jmhImplementation(project(":test-support"))
    jmhImplementation(project(":storage-sqlite"))
}

// JMH (plan section 20). The plugin attaches nothing to `check` or `build`
// (verified against Gradle 9.7.1 dry-run graphs): benchmarks compile via
// `:benchmarks:jmhClasses` / `:benchmarks:jmhCompileGeneratedClasses` and run
// only via an explicit `./gradlew :benchmarks:jmh` — a full run is far too
// slow for CI's inner loop.
jmh {
    jmhVersion = libs.versions.jmh.get()
}

// Phase 0 architectural spikes (plan section "Phase 0"). Each spike supports
// --offscreen (paints to a BufferedImage and prints frame statistics, no window)
// and defaults to an interactive window otherwise. Extra program args:
//   ./gradlew :benchmarks:runTableSpike --args="--offscreen"
val spikeMains = mapOf(
    "runTableSpike" to "com.holtherndon.bazelviz.benchmarks.spike.TableSpike",
    "runTimelineSpike" to "com.holtherndon.bazelviz.benchmarks.spike.TimelineSpike",
    "runGraphSpike" to "com.holtherndon.bazelviz.benchmarks.spike.GraphSpike",
    "runSqlPagingSpike" to "com.holtherndon.bazelviz.benchmarks.spike.SqlPagingSpike",
)

// Gradle's own JVM properties do not reach a forked JavaExec, so the spike
// switches a caller sets with -D are forwarded explicitly. Without this,
// `-Dbbv.smoke=true` silently does nothing and a windowed spike never closes.
val forwardedSpikeProperties = listOf("bbv.smoke", "bbv.theme", "bbv.appdir")

spikeMains.forEach { (taskName, mainName) ->
    tasks.register<JavaExec>(taskName) {
        group = "spikes"
        description = "Runs $mainName"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = mainName
        maxHeapSize = "4g"
        forwardedSpikeProperties.forEach { key ->
            providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
        }
    }
}

// Reports which JVM the spike tasks run on, so docs/performance.md can state
// the measurement environment rather than assume it.
tasks.register("printSpikeJvm") {
    group = "spikes"
    description = "Prints the JVM the spike tasks launch"
    val launcher = javaToolchains.launcherFor(java.toolchain)
    doLast {
        val metadata = launcher.get().metadata
        println("spike JVM: ${metadata.javaRuntimeVersion} (${metadata.vendor}) at ${metadata.installationPath}")
    }
}
