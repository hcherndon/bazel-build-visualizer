plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))
    implementation(project(":bep-codec"))

    // JSON BEP files are protobuf-JSON, so decoding a record needs
    // JsonFormat/TypeRegistry from protobuf-java-util (plan 9.5). Kept
    // `implementation`: no public type in this module exposes JsonFormat.
    implementation(libs.protobuf.java.util)
}
