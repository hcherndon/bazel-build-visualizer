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

// Gradle's own JVM properties do not reach the forked `run` JVM. Without this
// forwarding, `./gradlew :app:run -Dbbv.smoke=true` silently ignores smoke
// mode and the window never closes.
tasks.named<JavaExec>("run") {
    listOf("bbv.smoke", "bbv.theme", "bbv.appdir").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
