plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))
    api(libs.sqlite.jdbc)
}
