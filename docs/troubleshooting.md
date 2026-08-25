# Troubleshooting

Grows as the tool grows; Phase 0 covers build/dev issues only. App-runtime
troubleshooting (capture failures, session recovery) arrives with Phase 2+.

## Build

- **First build is slow / downloads things.** Expected: bazelisk downloads
  Bazel 9.2.0 (per `.bazelversion`), Bazel fetches the remote JDK 25
  toolchain (ADR-008 via ADR-009) and every dependency. All of it is cached;
  later builds are quiet. A locally installed JDK is deliberately ignored —
  the build is hermetic.
- **"Another command is running" / server lock.** Another Bazel invocation
  (IDE sync, second terminal) holds the workspace lock. Wait and retry;
  never run two invocations against this workspace at once. `bazel shutdown`
  stops the server (it also stops itself after the 300 s idle cap in
  `.bazelrc`).
- **Dependency changes fail with a lockfile error.** Expected friction: edit
  `MODULE.bazel`'s `maven.install`, run `REPIN=1 bazel run @maven//:pin`,
  and commit the `maven_install.json` diff with the change.
- **Stale/odd behavior**: `bazel shutdown` then rebuild before deeper
  debugging; `bazel clean` exists but is almost never the answer.
- **The machine groans under test runs.** Read `.bazelrc`'s startup section
  before changing anything: the outer server is capped at 4 GiB and test
  parallelism at 4 for a documented reason (child Bazel servers in the
  real-bazel tests; a machine has been crashed at over 120 GB). Never run
  the `bazel-sweep`-tagged target casually.

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

  The build already passes `--enable-native-access=ALL-UNNAMED` to the JVMs
  it forks (`tools/bbv.bzl`, the single written occurrence), so
  `bazel test //...`, `bazel run //app:bbv`, the spikes and
  `//benchmarks:jmh` are all quiet, and the jpackage image carries it in its
  `.cfg`. You will see the warning only if you launch a JVM yourself —
  `java -jar` on the deploy jar, a profiler's own launcher, or an IDE run
  configuration that does not add the flag. Fix it by adding the same flag:

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

- Test logs and XML: `bazel-testlogs/<module>/<TestClass>/test.log` and
  `test.xml` (one directory per test class).
- App logs: console via logback (`app/src/main/resources/logback.xml`);
  per-session capture logs will live in the session directory
- Headless app smoke run: `bazel run //app:bbv -- --jvm_flag=-Dbbv.smoke=true`
  opens the window and exits after two seconds.
- CI failures: the workflow uploads `bazel-testlogs/**` as an artifact on
  failure — download it from the run page rather than re-deriving locally.

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

### A build will not stop, or the next Bazel command hangs

These are one problem. A Bazel client holds its workspace's command lock for the
whole of its life, so a client that outlives its cancellation blocks every later
Bazel command in that workspace — the next build, and the `bazel clean` you
reach for when the next build will not start. "Cancel does nothing" and "clean
never finishes" are the same client, seen twice.

The Stop control has three rungs, and they are not three names for one thing:

- **Cancel** sends `SIGINT`, the same signal Ctrl-C sends. Bazel interrupts the
  build *and still flushes its event stream*, so the session is complete up to
  the moment you stopped it. This is the one to press.
- **Terminate** sends `SIGTERM`. Measurably the same outcome as Cancel for the
  Bazel client; kept so that "stop harder" sends a different signal rather than
  repeating one that has already failed to land.
- **Force kill** sends `SIGKILL`. It does not stop the build any faster — the
  Bazel *server* is a separate process that notices the client is gone after
  about two and a half seconds and cancels the build itself — and it costs the
  ability to know the event stream is complete.

Whichever you press, the stop escalates on its own if the client does not go:
thirty seconds, then ten, then five, and the last rung is `SIGKILL`, which
cannot be ignored. So a stop always ends the client, and the workspace lock is
always released. When escalation was needed, the session says so in its
warnings — press Cancel and read "escalated from CANCEL to FORCE_KILL" and you
know why your stream is short.

If you have a wedged workspace from an older build, or from a client this
application did not launch:

```
bazel info server_pid          # blocks if the lock is held; the message names the pid holding it
ps -p <pid> -o command=        # confirm it is the client you think it is
kill -INT <pid>                # then -TERM, then -KILL
```

Bazel prints `Another command (pid=…) is running. Waiting for it to complete on
the server (server_pid=…)` while it waits, so the pid is on screen. Do not kill
the *server* pid: it is shared with every other terminal using the same output
base, and killing it discards analysis state belonging to work you are not
looking at.

One cause worth knowing, because it produces a hang with no error at all:
Bazel's `--bes_timeout` defaults to `0s`, which means wait for ever. A build
whose Build Event Service backend stops acknowledging then waits indefinitely at
the end of an otherwise successful build — with the workspace lock held the
whole time. Every plan this application produces injects `--bes_timeout=60s`,
including the one that keeps your own `--bes_backend` and reads a local event
file instead. If you set your own `--bes_timeout`, yours is left alone and the
plan shows the flag as not applied; if you veto ours, the plan says what that
costs.

### The packaged application will not open

An unsigned build is refused by Gatekeeper on a machine other than the one that
built it. See [packaging.md](packaging.md) for signing and notarization; both
read credentials from the environment and neither is in this repository.
