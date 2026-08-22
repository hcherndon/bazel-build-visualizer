plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // The live capture pipeline is the component that joins the Phase 2 parts
    // together, so this module reaches the runner, the session layer and the
    // storage layer. capture-file comes in for two reasons that are not
    // incidental: the binary-file fallback for a BES conflict (plan 8.5) is its
    // growing-file tail reader, and normalization is shared with the importer so
    // that a captured session and an imported one produce identical rows.
    api(project(":bazel-runner"))
    api(project(":capture-file"))

    implementation(project(":proto"))
    implementation(project(":bep-codec"))
    implementation(libs.grpc.api)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)

    // grpc-netty-shaded is a compile dependency, not runtimeOnly: binding
    // loopback only (plan 22.1) needs NettyServerBuilder.forAddress, and the
    // transport-agnostic ServerBuilder.forPort can only bind every interface.
    // The shaded artifact keeps Netty's own classes off the application
    // classpath, which matters for a desktop application that may one day be
    // packaged alongside libraries carrying their own Netty.
    implementation(libs.grpc.netty.shaded)

    // Post-build enrichment. The coordinator runs it because it is the only
    // thing that knows when the build finished and still holds the session's
    // writer connection; the imports themselves know nothing about capture.
    implementation(project(":enrichment"))

    testImplementation(project(":test-support"))
    testImplementation(project(":bep-codec"))
    testImplementation(project(":proto"))
}
