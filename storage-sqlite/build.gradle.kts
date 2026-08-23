plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))

    // The CSR index lives in graph-core, which knows nothing about SQLite; the
    // builder here is the one place the edge table and the index meet.
    api(project(":graph-core"))

    // Plan 6.1 puts metrics, aggregation, percentiles and data-quality reports
    // in analysis-core, and "query implementations" and "aggregate persistence"
    // here. So the aggregate types are theirs and the streaming is ours, and
    // this edge is what joins the two. It points this way and only this way:
    // analysis-core still depends on core-model and graph-core alone, which is
    // what lets extraction, clustering, layout and every formula be tested
    // without a database.
    api(project(":analysis-core"))
    api(libs.sqlite.jdbc)
}
