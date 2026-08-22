plugins {
    id("bbv.java-application")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":ui-swing"))
    runtimeOnly(libs.logback.classic)
}

// See bbv.java-common.gradle.kts for why ALL-UNNAMED is the only available
// target: everything, FlatLaf included, is on the classpath.
val nativeAccessArg = extra["bbvNativeAccessArg"] as String

application {
    mainClass = "com.holtherndon.bazelviz.app.Main"
    // FlatLaf's NativeLibrary calls System::load for the macOS window
    // decorations, so the launcher scripts and any jpackage image need the
    // native-access grant on every platform, not just macOS.
    val macArgs = if (System.getProperty("os.name").contains("Mac")) {
        listOf(
            "-Xdock:name=Bazel Build Visualizer",
            "-Dapple.awt.application.name=Bazel Build Visualizer",
            "-Dapple.laf.useScreenMenuBar=true",
            "-Dapple.awt.application.appearance=system",
        )
    } else {
        emptyList()
    }
    applicationDefaultJvmArgs = listOf(nativeAccessArg) + macArgs
}

// Gradle's own JVM properties do not reach the forked `run` JVM. Without this
// forwarding, `./gradlew :app:run -Dbbv.smoke=true` silently ignores smoke
// mode and the window never closes.
tasks.named<JavaExec>("run") {
    // `run` inherits applicationDefaultJvmArgs, which already carries the
    // native-access grant. Add it only if that inheritance is ever broken, so
    // the flag is stated on the forked JVM exactly once.
    if (jvmArgs.orEmpty().none { it.startsWith("--enable-native-access") }) {
        jvmArgs(nativeAccessArg)
    }
    listOf("bbv.smoke", "bbv.theme", "bbv.appdir").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
