plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // The CSR index lives in graph-core, which knows nothing about SQLite; the
    // builder here is the one place the edge table and the index meet.
    api(project(":graph-core"))
    api(libs.sqlite.jdbc)
}
