plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // Real BEP fixtures are built from the generated protobuf classes, not from
    // hand-rolled byte arrays: a fixture that does not go through the real
    // schema cannot prove a parser handles the real schema.
    api(project(":proto"))

    // protobuf-JSON (JsonFormat) for the BEP JSON fixtures. Bazel's
    // --build_event_json_file output is protobuf-JSON, so emitting it any other
    // way would produce a fixture that is not the format under test.
    api(libs.protobuf.java.util)
}

// Prints the runtime classpath so a scripted end-to-end check can generate a
// BEP fixture with `java -cp "$(./gradlew -q :test-support:printRuntimeCp)"`
// instead of needing a bespoke JavaExec task per scenario.
tasks.register("printRuntimeCp") {
    val runtimeClasspath = sourceSets["main"].runtimeClasspath
    doLast { println(runtimeClasspath.asPath) }
}
