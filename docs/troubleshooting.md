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
- Headless app smoke run: `./gradlew :app:run -Dbbv.smoke=true` opens the
  window and exits after two seconds.
- CI failures: the workflow uploads `**/build/reports/tests` as an artifact
  on failure — download it from the run page rather than re-deriving
  locally.

## Sessions and capture (Phase 10)

### "session … has no session.sqlite"

The import stopped before the database was created. The raw capture is still
there. Re-import the source, or resume the import from the session's own
checkpoint — the tool offers to resume when you open a session whose state is
not terminal.

### "session … was indexed by an older build" / "written by a newer build"

The session's schema does not match this build's. Opening is read-only and does
not migrate, because a view is not a licence to rewrite the file you opened.

For an older session: import its source again. The raw events are preserved, so
nothing is lost by rebuilding. For a newer one: open it with the build that
wrote it, or upgrade.

### An archive will not open

`.bviz` archives are validated completely before anything is extracted, and the
message names what failed: a checksum, an entry that is not part of a session,
an entry name that escapes the archive, an expansion limit. All of these mean
the file is not what it claims to be — a truncated download is the common
innocent cause.

"This session is already in the library" means exactly that; open the copy the
message names rather than importing a second one.

### The graph says it will not draw

Above 50,000 nodes or 200,000 edges the canvas refuses rather than freezing, and
offers four things: draw it anyway, narrow the query, group it, or export the
whole graph. The exact totals are on screen. Nothing has been sampled or
dropped.

Grouping by mnemonic always works — there are only ever a few dozen — and is the
right answer when grouping by package is itself too large.

### A view says a source is missing

Enrichments are separate commands and any of them can fail without failing the
build capture. The Coverage view lists every source, what it covers, and for a
failed enrichment: the exit status, an error excerpt, whether it can be retried,
and **which metrics are unavailable as a result**. That last line is the one to
read — knowing the profile import failed does not tell you that you have
therefore lost the critical path.

### Numbers that look wrong

Three that are usually right and look wrong:

- **Far fewer attempts than actions.** Most actions run inside the Bazel server
  and never spawn a subprocess. Four attempts against thirteen actions is
  normal.
- **Actions with no duration.** Bazel 6.5.0 and 7.6.1 publish no action
  timestamps at all, and 8.4.1 publishes an identical start and end for every
  action — including a five-second sleep. The tool reports those as untimed
  rather than as zero, and uses the execution log's timings when it has them.
- **A cache-hit rate over a fraction of the build.** The rate is over the
  actions that reported a cache state, and the count it was taken over is
  always beside it. An action with no execution-log record is not a miss.

### Cleanup removed less than expected

Pinned sessions are never removed, and the plan shown before the sweep says how
many it spared. A session pinned between seeing the plan and confirming it is
also spared: your decision is newer than the plan.

### The packaged application will not open

An unsigned build is refused by Gatekeeper on a machine other than the one that
built it. See [packaging.md](packaging.md) for signing and notarization; both
read credentials from the environment and neither is in this repository.
