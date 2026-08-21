plugins {
    id("bbv.java-common")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":test-support"))
    implementation(project(":ui-swing"))
    implementation(project(":graph-core"))
    implementation(project(":storage-sqlite"))
    runtimeOnly(libs.logback.classic)
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

spikeMains.forEach { (taskName, mainName) ->
    tasks.register<JavaExec>(taskName) {
        group = "spikes"
        description = "Runs $mainName"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass = mainName
        maxHeapSize = "4g"
    }
}
