plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // The capability detector's primary probe is `bazel help flags-as-proto`,
    // whose output is a base64-encoded bazel_flags.FlagCollection. Parsing it is
    // the difference between knowing a flag exists and scraping help text for
    // it. Kept `implementation`: no type in this module's API exposes a
    // generated protobuf class — FlagSpec restates what we need, so a caller
    // never needs protobuf on its compile classpath.
    implementation(project(":proto"))

    // Real Bazel binaries are not available to unit tests, so the tests drive
    // the detector and the launcher against scripted fake executables and
    // against recorded help output from Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0
    // (plan rule 18: create a real fixture before encoding an assumption).
    testImplementation(project(":test-support"))
}
