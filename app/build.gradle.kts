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

    // `bbv run` drives the Phase 2 capture path: the coordinator, the embedded
    // BES server and the launcher. capture-bes exposes bazel-runner as `api`,
    // but the runner is named here too because this module's own source imports
    // CapturePreset, PlanConflict and CancellationMode.
    implementation(project(":capture-bes"))
    implementation(project(":bazel-runner"))
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

// ---------------------------------------------------------------- packaging
//
// Plan 24, Phase 9: macOS file associations, an app menu, packages for both
// architectures, and signing hooks that do not embed credentials.
//
// jpackage runs against the `installDist` layout rather than a fat jar: the
// application plugin already produces exactly the directory jpackage's
// `--input` wants, and a shadow jar would flatten module metadata for no gain
// in a desktop app that is never on anybody else's classpath.

val packagingDir = layout.projectDirectory.dir("src/main/packaging")
val jpackageOut = layout.buildDirectory.dir("jpackage")

/**
 * The signing identity, from the environment only.
 *
 * Plan 24: "add signing/notarization hooks without embedding credentials".
 * Read through the provider API so the configuration cache tracks it, and never
 * from gradle.properties -- that file is committed, and a Developer ID in it
 * would be a credential in the repository.
 */
val macSigningIdentity = providers.environmentVariable("BBV_MAC_SIGNING_IDENTITY")
val macNotaryProfile = providers.environmentVariable("BBV_MAC_NOTARY_PROFILE")

val jpackageType = providers.gradleProperty("bbv.packageType").orElse("app-image")

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a macOS application image or installer with jpackage."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/bbv/lib")
    val outputDir = jpackageOut
    // macOS refuses a CFBundleVersion whose first component is zero, and this
    // project is 0.1.0. CFBundleVersion is a build-ordering number rather than
    // the product's identity -- the version a user sees is AppInfo.VERSION, in
    // the About dialog -- so a placeholder here is a platform requirement and
    // not a claim about the release. It is announced when it happens, and
    // -Pbbv.packageVersion overrides it.
    val projectVersion = project.version.toString().removeSuffix("-SNAPSHOT")
    val requestedVersion = providers.gradleProperty("bbv.packageVersion")
    val version = requestedVersion.orNull
        ?: if (projectVersion.startsWith("0.")) "1.0.0" else projectVersion
    // Captured at configuration time: reading project.version inside doFirst is
    // what the configuration cache forbids, and the message it gives is about
    // Task.project rather than about the string being built.
    val mainJar = "app-${project.version}.jar"
    // The project's value, captured outside the task: `extra` inside a task
    // block is the task's own extension, not the project's.
    val nativeAccess = nativeAccessArg
    val association = packagingDir.file("bviz.properties")
    val identity = macSigningIdentity
    val type = jpackageType

    inputs.dir(installDir)
    inputs.file(association)
    outputs.dir(outputDir)

    doFirst {
        if (version != projectVersion) {
            logger.lifecycle(
                "jpackage: macOS will not accept an app-version starting with zero, so this" +
                    " package is stamped $version while the application reports $projectVersion." +
                    " Pass -Pbbv.packageVersion to choose another."
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val command = mutableListOf(
            "jpackage",
            "--type", type.get(),
            "--name", "Bazel Build Visualizer",
            "--app-version", version,
            "--vendor", "holtherndon",
            "--input", installDir.get().asFile.absolutePath,
            "--main-jar", mainJar,
            "--main-class", "com.holtherndon.bazelviz.app.Main",
            "--dest", out.absolutePath,
            "--java-options", nativeAccess,
            "--java-options", "-Dapple.laf.useScreenMenuBar=true",
            "--java-options", "-Dapple.awt.application.appearance=system",
            "--file-associations", association.asFile.absolutePath,
        )
        if (System.getProperty("os.name").contains("Mac")) {
            command += listOf("--mac-package-identifier", "com.holtherndon.bazelviz")
            // Signing is opt-in through the environment. An unsigned build is a
            // perfectly good local build; it is only distribution that needs a
            // Developer ID, and that is where the identity lives.
            if (identity.isPresent) {
                command += listOf("--mac-sign", "--mac-signing-key-user-name", identity.get())
                logger.lifecycle("jpackage: signing with the identity in BBV_MAC_SIGNING_IDENTITY")
            } else {
                logger.lifecycle(
                    "jpackage: BBV_MAC_SIGNING_IDENTITY is not set, so the package is unsigned." +
                        " Gatekeeper will refuse it on another machine."
                )
            }
        }
        commandLine(command)
    }
}

tasks.register("notarize") {
    group = "distribution"
    description = "Submits the built package to Apple's notary service."
    dependsOn(tasks.named("jpackage"))

    val outputDir = jpackageOut
    val profile = macNotaryProfile

    doLast {
        if (!profile.isPresent) {
            throw GradleException(
                "BBV_MAC_NOTARY_PROFILE is not set. Store credentials once with\n" +
                    "  xcrun notarytool store-credentials <profile> --apple-id … --team-id …\n" +
                    "and export the profile name. This build never reads an Apple ID, a\n" +
                    "password or an app-specific password, and never writes one anywhere."
            )
        }
        val artifacts = outputDir.get().asFile.walkTopDown()
            .filter { it.extension == "dmg" || it.extension == "pkg" }
            .toList()
        if (artifacts.isEmpty()) {
            throw GradleException(
                "no .dmg or .pkg under ${outputDir.get().asFile}. Run with" +
                    " -Pbbv.packageType=dmg; an app-image cannot be notarized on its own."
            )
        }
        // ProcessBuilder rather than a Gradle exec service: this task
        // discovers its arguments at execution time, and a plain process is
        // both configuration-cache-safe and the same shape as the command a
        // person would type from the documentation.
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).inheritIO().start()
            val status = process.waitFor()
            if (status != 0) {
                throw GradleException("${command.first()} exited with $status")
            }
        }
        artifacts.forEach { artifact ->
            logger.lifecycle("Notarizing ${artifact.name}")
            run(
                "xcrun", "notarytool", "submit", artifact.absolutePath,
                "--keychain-profile", profile.get(), "--wait",
            )
            run("xcrun", "stapler", "staple", artifact.absolutePath)
        }
    }
}
