plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // The import pipeline (`importer/`) is the component that joins the Phase 1
    // parts together, so this module now reaches the session and storage
    // layers. Both are `api` rather than `implementation` because BepImporter's
    // signatures name their types: it takes a SessionManager and returns an
    // ImportResult carrying EventWriter.IngestSummary, so a caller cannot use
    // the importer without them on its own compile classpath.
    api(project(":session-format"))
    api(project(":storage-sqlite"))

    implementation(project(":bep-codec"))

    // JSON BEP files are protobuf-JSON, so decoding a record needs
    // JsonFormat/TypeRegistry from protobuf-java-util (plan 9.5). Kept
    // `implementation`: no public type in this module exposes JsonFormat.
    implementation(libs.protobuf.java.util)

    // Importer tests are the Phase 1 exit criteria in executable form, so they
    // run against the real generated fixtures rather than hand-built bytes.
    testImplementation(project(":test-support"))
    testImplementation(project(":bep-codec"))
}
