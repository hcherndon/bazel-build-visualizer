plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))
    implementation(project(":bep-codec"))
}
