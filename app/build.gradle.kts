plugins {
    id("bbv.java-application")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":ui-swing"))
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass = "com.holtherndon.bazelviz.app.Main"
    if (System.getProperty("os.name").contains("Mac")) {
        applicationDefaultJvmArgs = listOf(
            "-Xdock:name=Bazel Build Visualizer",
            "-Dapple.awt.application.name=Bazel Build Visualizer",
            "-Dapple.laf.useScreenMenuBar=true",
            "-Dapple.awt.application.appearance=system",
        )
    }
}
