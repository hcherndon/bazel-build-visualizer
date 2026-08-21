plugins {
    id("bbv.java-library")
}

dependencies {
    api(project(":core-model"))
    implementation(project(":proto"))
    implementation(libs.grpc.api)
    implementation(libs.grpc.stub)
    runtimeOnly(libs.grpc.netty.shaded)
}
