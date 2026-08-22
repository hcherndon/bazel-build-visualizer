plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))
    api(project(":session-format"))
    api(project(":storage-sqlite"))
    implementation(project(":proto"))

    // Pure-Java zstd. The compact execution log is a zstd frame (finding S4),
    // so a decoder is not optional. Chosen over zstd-jni because it needs no
    // platform-native binaries: decompression is nowhere near the bottleneck
    // ahead of protobuf parsing and SQLite writes, so the speed the native
    // library would buy is not worth shipping a per-platform .dylib matrix.
    implementation(libs.aircompressor)
    implementation(libs.protobuf.java)

    // Streaming JSON, for the trace profile. Gson's JsonReader is a pull
    // parser, which is the requirement: a profile is not a protobuf and can be
    // hundreds of megabytes, so it is read a token at a time and never held.
    // Declared rather than inherited -- it already arrives transitively through
    // protobuf-java-util at 2.8.9, and depending on that by accident is how a
    // version nobody chose ends up in the build.
    implementation(libs.gson)

    testImplementation(project(":test-support"))
}
