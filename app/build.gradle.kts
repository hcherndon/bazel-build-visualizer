plugins {
    id("bbv.java-application")
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":ui-swing"))

    // The headless subcommands in `app/cli` drive the Phase 1 import pipeline
    // directly: `bbv import` runs BepImporter over a managed session, and
    // `bbv inspect` reads the result back through EventQueries. capture-file
    // already exposes session-format and storage-sqlite as `api`, but both are
    // named here as well because this module's own source imports SessionManager
    // and SessionDatabase — a dependency a file compiles against belongs in its
    // own build file, not inherited by accident from a sibling's api surface.
    implementation(project(":capture-file"))
    implementation(project(":session-format"))
    implementation(project(":storage-sqlite"))

    // For BepPayloadType: `inspect` prints "targetCompleted (7)" rather than a
    // bare payload-case number, which is the difference between a page a
    // developer can read and a column of integers.
    implementation(project(":bep-codec"))

    runtimeOnly(libs.logback.classic)

    // The CLI tests import real synthetic BEP fixtures and the deliberate-damage
    // helpers, so the truncated-source exit code is proved against a genuinely
    // truncated protobuf stream rather than a hand-built one.
    testImplementation(project(":test-support"))
}

// Every test in this module runs headless, matching the environment CI gives
// the `bbv import` / `bbv inspect` commands. Set here rather than only inside
// one test because java.awt.GraphicsEnvironment caches its answer the first
// time it is asked: a sibling test that touched AWT first would leave the
// headless assertion trivially passing or failing depending on test order.
tasks.named<Test>("test") {
    systemProperty("java.awt.headless", "true")
}

// See bbv.java-common.gradle.kts for why ALL-UNNAMED is the only available
// target: everything, FlatLaf included, is on the classpath.
val nativeAccessArg = extra["bbvNativeAccessArg"] as String

application {
    mainClass = "com.holtherndon.bazelviz.app.Main"
    // Every usage line, error hint and resume instruction the CLI prints names
    // the program `bbv`; the launcher has to match or none of them can be
    // pasted into a shell.
    applicationName = "bbv"
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
