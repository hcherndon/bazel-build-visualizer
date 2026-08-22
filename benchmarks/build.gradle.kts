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

    // BesThroughputSpike drives the real embedded server over a real socket,
    // so it needs the capture path and a gRPC client to push events at it.
    implementation(project(":capture-bes"))
    implementation(project(":proto"))
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)

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
    // SqliteInsertBench loads sqlite-jdbc's native library, so the benchmark
    // JVMs JMH forks need the same grant every other forked JVM gets. Without
    // this the JMH path is the one surface still printing restricted-method
    // warnings, and the one that would break outright once the JDK enforces.
    jvmArgs = listOf(extra["bbvNativeAccessArg"] as String)
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
    "runBesThroughputSpike" to "com.holtherndon.bazelviz.benchmarks.spike.BesThroughputSpike",
    "runEntityScaleSpike" to "com.holtherndon.bazelviz.benchmarks.spike.EntityScaleSpike",
)

// Gradle's own JVM properties do not reach a forked JavaExec, so the spike
// switches a caller sets with -D are forwarded explicitly. Without this,
// `-Dbbv.smoke=true` silently does nothing and a windowed spike never closes.
// io.netty.tryUnsafe is forwarded so the BES throughput spike can measure the
// counterfactual for ADR-008's open question: grpc-netty disables
// sun.misc.Unsafe on Java 25, and asking it to try anyway is the only way to
// find out whether that is what costs the capture path its headroom.
val forwardedSpikeProperties =
    listOf("bbv.smoke", "bbv.theme", "bbv.appdir", "io.netty.tryUnsafe", "io.netty.noUnsafe")

// The spikes load native code through both sqlite-jdbc (SqlPagingSpike) and
// FlatLaf (the Swing spikes), so their forked JVMs need the same native-access
// grant the Test tasks get in bbv.java-common.gradle.kts. See that file for
// why the target has to be ALL-UNNAMED.
val nativeAccessArg = extra["bbvNativeAccessArg"] as String

spikeMains.forEach { (taskName, mainName) ->
    tasks.register<JavaExec>(taskName) {
        group = "spikes"
        description = "Runs $mainName"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = mainName
        maxHeapSize = "4g"
        jvmArgs(nativeAccessArg)
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
