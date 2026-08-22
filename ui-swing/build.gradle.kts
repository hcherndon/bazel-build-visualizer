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

    // Decoding the selected event's raw payload for the inspector (plan
    // 17.11: render full protobuf text only for the selected event).
    implementation(project(":bep-codec"))

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
