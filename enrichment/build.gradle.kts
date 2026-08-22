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

    testImplementation(project(":test-support"))
}
