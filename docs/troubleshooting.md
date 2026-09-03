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
- **`bazel build //...` fails loading a package under `.claude/`.** The
  root `.bazelignore` must list `.claude`: package traversal ignores
  .gitignore, and the git worktrees kept under `.claude/worktrees/` carry
  BUILD files and bazel-* symlinks that must never load as this
  workspace's packages.
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
  it forks (`tools/java_test_settings.bzl`, the single written occurrence), so
  `bazel test //...`, `bazel run //app:app`, the spikes and
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

### Timeline pinch does not zoom on macOS

First check Control/Command + wheel or the Timeline's **−** and **+** buttons.
If those zoom but a two-finger pinch does not, the application probably started
without access to macOS's JDK magnification event package. The jpackage image
and `bazel run //app:app` add the required export. A direct graphical jar launch
or an IDE run configuration must add it explicitly:

```
--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED
```

For `java -jar`, place it before `-jar`, alongside
`--enable-native-access=ALL-UNNAMED`. This export is only for native macOS
pinch. Ordinary wheel/two-finger movement scrolls lanes, modified wheel zooms,
and the buttons work without it. On Linux there is no macOS package to export.

### Saved Workspaces are missing or will not save

Workspace profiles live in `settings/workspaces.properties`, separately from
captured sessions. A missing file is normal on first launch. An unreadable,
malformed or larger-than-1-MiB file is refused whole with a startup diagnostic;
it is never partially loaded. A failed atomic replacement leaves the previous
file unchanged and the current in-memory list available until the window
closes. At most 100 profiles are saved. Removing one deletes only that
convenience record, never repository files or captured sessions.

Older `settings/launcher.properties` values have a one-time migration path for
the old local selection and up to 20 SSH connection records. Command history
and other launcher preferences remain in that legacy file; it is not the
source of truth for Workspaces after migration.

Discovered Workspace definitions are never saved, but their Bazel executable
override and bounded command history live separately under
`settings/discovered-workspace-history/`. Those launch preferences are
reachable only when discovery emits the same execution kind, SSH destination,
and working directory again. Changing only the displayed name keeps the same
preferences; changing the machine or directory creates a new identity. These
files contain no connection, repository, profile, or label metadata that could
recreate a missing discovered Workspace.

### Workspace Discovery is missing, fails, or shows stale rows

Open **Settings › Preferences…** and choose the **Discovery** tab. The saved
script runs locally once at graphical startup and when **Run Discovery Now**
is pressed; that button saves the current editor text first. A non-empty script
must begin with a valid shebang. The file is executed directly, so confirm that
the named interpreter exists on the desktop machine. The editor's detected
language and syntax highlighting also come from that shebang. An empty script
means discovery is not configured.

The script must print one non-comment row per Workspace, with no extra fields:

```
local|name|working-directory
ssh|name|OpenSSH-destination-or-Host-alias|working-directory
```

Each invocation replaces the previous discovered list, including when the new
run produces no accepted rows. Check the status on the Workspaces screen. A
manual **Run Discovery Now** also opens bounded, selectable diagnostics for a
timeout, oversized output, stderr, malformed field count, duplicate row,
invalid destination or accepted-row limit. Valid rows can still appear
alongside diagnostics for invalid rows.

Discovered rows are intentionally tagged **Discovered** and have no Edit or
Remove action. They exist only for the current process and are never copied to
`settings/workspaces.properties`; change the script and run it again. The
script itself is the saved preference, at `settings/workspace-discovery`.
Treat it as executable code with your desktop account's permissions.

An SSH discovery row has no port or arbitrary-option field. Put custom ports,
jump hosts, identities and related settings under the printed destination or
`Host` alias in `~/.ssh/config`, then test that alias with the system OpenSSH
commands below.

### An SSH Workspace cannot connect

Remote execution uses the system `/usr/bin/ssh` and `/usr/bin/sftp` clients in
batch mode. It does not show a password or host-key prompt. Establish the host
key and non-interactive authentication in a terminal first, then verify the
same destination or Host alias the Workspace uses:

```
/usr/bin/ssh -o BatchMode=yes <destination> true
/usr/bin/sftp -b /dev/null -o BatchMode=yes <destination>
```

If the profile has an explicit port, pass the same port (`ssh -p <port>` and
`sftp -P <port>`). Put jump hosts, identities and other options in
`~/.ssh/config`; the destination field accepts a host or `user@host`, not SSH
command-line options. Saved SSH Workspaces add only a user label, stable ID,
working directory, Bazel executable and last-opened time to those connection
coordinates. Credentials remain in the SSH agent or OpenSSH files.

Workspace selection also checks the SFTP subsystem, remote directory and fixed
Linux tools used for safe metadata, staging and process control. Its error names
the missing tool or subsystem. The first implementation supports headless Linux
hosts with the standard `/bin` and `/usr/bin` utilities; another Unix layout
does not silently fall back to running the command locally.

### The selected Bazel executable cannot be found

The Console's **Bazel Executable** belongs to that Workspace and defaults to
`bazel`. Type a command available on the selected machine's PATH, or an
executable path on that machine. The field has no file-selector button, so type
remote paths as they appear on the SSH host rather than as desktop paths.

A saved Workspace stores the corrected value in `workspaces.properties`. A
discovered Workspace stores only this override and its bounded command history
in its deterministic sidecar. If preflight still fails, verify the path and
execute permission on the selected machine. For a bare command, verify PATH in
the non-interactive environment shown by the launch review.

### The reverse SSH tunnel is refused

The desktop BES is still listening only on `127.0.0.1`. OpenSSH asks the server
for an allocated remote-loopback port and forwards that port back to the local
listener. A server that disables TCP forwarding rejects preflight before the
instrumentation plan can be approved. Check the server's SSH policy (commonly
`AllowTcpForwarding`) with its administrator. There is no option to expose the
BES on a LAN address as a workaround.

### The launch review says `--bes_backend` could not be applied

The app will not run an embedded-BES capture unless the selected Bazel was
observed accepting its required backend flag. If capability probing failed, the
review says that the result is unknown; if the flag is absent, it says that the
Bazel is unsupported. Resolve the named probe or executable problem and retry.
Running the unchanged command would produce a session with no live events, so
there is no bypass for this guard. Keeping an existing team BES and using the
explicit build-event-file fallback remains available when that file capability
was observed.

### Remote console output has no separate stderr stream

Expected. The primary remote Bazel command uses a forced TTY so Ctrl-C and the
stop ladder reach the foreground remote process group. A TTY merges stdout and
stderr. The review dialog says so, and the console preserves that merged text.
Capability probes and aquery/cquery protobuf streams use non-TTY channels, so
diagnostics stay separate where required and binary data is not changed.

### A remote file or repository is unavailable after reopening a session

SSH provenance in `manifest.json` is display-only. Opening an old session never
contacts its recorded host, starts its terminal or executes its recorded
command. Remote file links, editing, Browse Repository and Terminal use only
the explicit live connection created by choosing an SSH Workspace. Pick the
matching entry from **Workspaces › Available Workspaces**, discover one, or
create one when live access is needed. Capture preflight is separate and runs
only when starting a new build.

Browse Repository loads a directory only when it is expanded. A message that
the 5,000-entry display bound was reached means the visible tree is partial,
not that files were removed. Text files larger than 16 MiB and binary files are
refused whole rather than truncated. Saves are conflict-aware; Reload before
trying again if the remote file changed since the editor opened it.

### Terminal cannot start or resize correctly

Terminal starts automatically when its tab is selected. It uses JediTerm for
xterm-compatible rendering and Pty4J for a real local PTY. A local Workspace
starts inherited `$SHELL` as a login shell, falling back to `/bin/sh`, with its
working directory set to that Workspace. An SSH Workspace starts the system
OpenSSH client inside the PTY. If either cannot start, the visible error should
name the shell, OpenSSH or native PTY load; there is no silent fallback to the
old line transcript. For SSH, verify that the same saved destination works with
the system `ssh` command first. For a packaged macOS application, also verify
that its signed Pty4J `libpty.dylib` and spawn helper remained in the deploy jar
and can be extracted at runtime.

The terminal survives navigation to another tab. Disconnect closes only its
shell channel; closing or replacing the Workspace closes it and, for SSH, the
shared SSH session. Scrollback keeps the newest 20,000 lines, so older terminal
display text can disappear without changing any captured build console file.

### A remote capture file was not copied

Each planned remote execution log, profile, BEP fallback or cquery scope file
is bounded at 32 GiB. An oversized, missing or changing file is refused and the
session warning names it; BES events already received remain usable. A cleanup
warning names the unique `/tmp/bbv-capture.…` directory that could not be fully
removed. Inspect that exact directory on the named host after the build ends;
do not remove a broad `/tmp` path.

## Where things live

- Test logs and XML: `bazel-testlogs/<module>/<TestClass>/test.log` and
  `test.xml` (one directory per test class).
- Graphical app log: `logs/application.log` below the application-support
  directory. Open or reveal it from **Diagnostics**. The active file rolls at
  8 MiB; compressed history keeps at most seven days and 64 MiB. Use
  **Diagnostics › Log Detail › Debug**, then Trace only if Debug is not enough.
  The selected level is stored in `settings/logging.properties`. Only one app
  process owns this rolling destination; another concurrent instance reports
  logging as unavailable rather than writing the same files.
- Headless command logs: stderr only, with Warn as the default. Pass
  `-Dbbv.log.level=debug` or `trace` to the JVM for more detail; stdout remains
  command output.
- Saved Theme: `settings/appearance.properties`. Change it from the **Theme**
  tab in **Settings › Preferences…**. A `-Dbbv.theme=<id>` process override
  does not replace the saved choice.
- Saved local/SSH Workspaces: `settings/workspaces.properties` beside the
  managed `sessions/` directory. Deleting it forgets names and execution
  locations, including their Bazel executable, not a repository, captured
  session or OpenSSH credential.
- Per-saved-Workspace history and presentation state:
  `settings/workspace-windows/<sha256-profile-id>/`. The Bazel executable
  itself remains in `workspaces.properties`.
- Per-discovered-Workspace Bazel override and bounded command history:
  `settings/discovered-workspace-history/<sha256-profile-id>/`. These files do
  not persist the discovered profile or make it available without discovery.
- Workspace Discovery script: `settings/workspace-discovery`. The saved script
  persists and runs locally at graphical startup; its **Discovered** profiles
  do not persist. Deleting the script disables that saved configuration but
  does not delete a repository or captured session.
- Legacy launcher compatibility values: `settings/launcher.properties`.
  Older local and SSH locations and command history may be read once for
  Workspace migration, but this file is no longer the source of truth for
  current Workspace settings or history.
- Headless app smoke run: `bazel run //app:app -- --jvm_flag=-Dbbv.smoke=true`
  opens the window and exits after two seconds.
- CI failures: the workflow uploads `bazel-testlogs/**` as an artifact on
  failure — download it from the run page rather than re-deriving locally.

The application-support root is
`~/Library/Application Support/BazelBuildVisualizer` on macOS,
`$XDG_DATA_HOME/bazel-build-visualizer` (or
`~/.local/share/bazel-build-visualizer`) on Linux, and
`%APPDATA%\BazelBuildVisualizer` on Windows. `-Dbbv.appdir=<path>` overrides it.
Application logs can contain paths, labels, SSH destinations and failure
messages; review them before sharing.

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

For an SSH build, Cancel first writes the TTY interrupt character and later
rungs signal the reported remote process group. Losing the desktop SSH client
alone is not counted as a successful stop: a surviving remote Bazel client
would still hold the remote workspace lock.

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
