plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // Phase 1 UI deliverable: the Events view drives the import pipeline and
    // reads back what it wrote. :capture-file exposes :session-format and
    // :storage-sqlite as `api`, so BepImporter, SessionManager, EventQueries
    // and JournalPayloadReader all arrive with this one dependency.
    //
    // Plan rule 19 ("UI components depend on service interfaces, not SQLite
    // implementation classes") is honoured by the ui.session service
    // interfaces: SessionSource / SessionReader are what ui.events talks to,
    // and SqliteSessionSource is the only class in this module that names
    // EventQueries or SessionDatabase.
    implementation(project(":capture-file"))

    // Phase 2 UI deliverables: the launcher, the instrumentation-plan dialog,
    // live capture status and the console all drive CaptureCoordinator, and
    // the stop controls name CancellationMode. capture-bes exposes
    // bazel-runner as `api`, but the runner is named here too because this
    // module's own source imports its types directly.
    implementation(project(":capture-bes"))
    implementation(project(":bazel-runner"))

    // Decoding the selected event's raw payload for the inspector (plan
    // 17.11: render full protobuf text only for the selected event).
    implementation(project(":bep-codec"))

    // The critical path and the layouts. analysis-core knows nothing about
    // Swing or SQLite; ui-swing is where a graph, its durations and a layout
    // meet, which is the only place that has all three.
    implementation(project(":analysis-core"))

    implementation(libs.flatlaf)

    // Real BEP fixtures, so the Events view is tested against a session an
    // actual import produced rather than against hand-built rows.
    testImplementation(project(":test-support"))
}

// The Events view's models and controllers are tested without a display, which
// is also how CI runs. Set for the whole task rather than inside one test:
// java.awt.GraphicsEnvironment caches its answer the first time it is asked, so
// a sibling test that touched AWT first would decide the mode for everything
// after it and make the setting depend on test order.
tasks.named<Test>("test") {
    systemProperty("java.awt.headless", "true")
}
