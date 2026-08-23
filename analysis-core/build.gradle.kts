plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // The critical path walks a CSR graph. analysis-core knows nothing about
    // where the graph came from or where it is stored.
    api(project(":graph-core"))
}
