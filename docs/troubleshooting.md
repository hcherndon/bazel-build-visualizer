# Troubleshooting

Grows as the tool grows; Phase 0 covers build/dev issues only. App-runtime
troubleshooting (capture failures, session recovery) arrives with Phase 2+.

## Build

- **First build is slow / downloads a JDK.** Expected: the Java 25 toolchain
  is auto-provisioned by the foojay resolver (ADR-008). Later builds reuse
  it from `~/.gradle/jdks/`. If you already have a JDK 25 installed, Gradle
  detects and uses it instead of downloading one.
- **"Timeout waiting to lock" / daemon lock errors.** Another Gradle
  invocation (IDE sync, second terminal) holds the lock. Wait and retry;
  do not delete lock files while a daemon runs. `./gradlew --status` lists
  daemons, `./gradlew --stop` stops them.
- **Dependency locking failures** after changing a dependency: regenerate
  lockfiles with `./gradlew resolveAndLockAll --write-locks
  --no-configuration-cache` and commit the lockfile diff with the change.
- **Configuration-cache problems** after editing build scripts: retry once
  (the cache invalidates itself); a persistent report lands under
  `build/reports/configuration-cache/`.
- **Stale/odd behavior**: `./gradlew --stop` then rebuild before deeper
  debugging — a long-lived daemon with old classpaths explains most
  mysteries.

## Running

- **`WARNING: java.lang.System::load has been called by ... in an unnamed
  module`.** Under Java 25 the JVM warns whenever code outside a named module
  calls a restricted native method. It looks like this:

  ```
  WARNING: java.lang.System::load has been called by com.formdev.flatlaf.util.NativeLibrary in an unnamed module
  WARNING: java.lang.System::load has been called by org.sqlite.SQLiteJDBCLoader in an unnamed module
  WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
  WARNING: Restricted methods will be blocked in a future release unless native access is enabled
  ```

  Both callers are expected and load-bearing: FlatLaf extracts and loads a
  native library for macOS window decorations (ADR-001), and sqlite-jdbc
  loads the native SQLite engine for the session database (ADR-005). Neither
  is optional, so the warning is not something to fix by removing a
  dependency.

  The Gradle build already passes `--enable-native-access=ALL-UNNAMED` to the
  JVMs it forks, so `./gradlew check`, `:app:run`, the spikes and
  `:benchmarks:jmh` are all quiet.
  The generated start scripts (`installDist` / `distZip`) carry it too, via
  `applicationDefaultJvmArgs`. You will see the warning only if you launch a
  JVM yourself — `java -jar` on a bare jar, a profiler's own launcher, or an
  IDE run configuration that does not inherit the Gradle JVM args. Fix it by
  adding the same flag:

  ```
  java --enable-native-access=ALL-UNNAMED -jar <jar>
  ```

  In IntelliJ, put it in the run configuration's *VM options*. Prefer this
  over the blanket `--illegal-native-access=allow`: the blanket switch is a
  transitional escape hatch whose default is scheduled to tighten, while
  `--enable-native-access` is the declaration that keeps working once a
  future JDK blocks these calls outright instead of warning. At that point a
  missing flag stops being noise and becomes a hard startup failure. See
  ADR-008.

## Where things live

- Test reports: `<module>/build/reports/tests/test/index.html`.
- App logs: console via logback (`app/src/main/resources/logback.xml`);
  per-session capture logs will live in the session directory
  (`logs/capture.log`, docs/session-format.md) from Phase 1.
- Headless app smoke run: `./gradlew :app:run -Dbbv.smoke=true` opens the
  window and exits after two seconds.
- CI failures: the workflow uploads `**/build/reports/tests` as an artifact
  on failure — download it from the run page rather than re-deriving
  locally.
