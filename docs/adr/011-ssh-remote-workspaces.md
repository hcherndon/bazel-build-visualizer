# ADR-011: SSH remote workspaces and execution-scoped I/O

Status: accepted (2026-08-28; amended 2026-08-30 for saved workspaces and local terminals; amended 2026-09-03 for per-workspace Bazel selection; amended 2026-09-04 for bounded remote I/O; amended 2026-09-09 for live-workspace SSH recovery)

## Context

The application currently assumes that Bazel, its workspace, and every file a
view opens are on the desktop machine. That assumption is spread across process
launch, capability probes, instrumentation output paths, graph queries, BUILD
file resolution, event-file metadata, and the text editor. It does not hold for
the headless Linux build hosts used for many real invocations.

The embedded Build Event Service (BES) is deliberately bound to loopback. A
remote Bazel process therefore cannot reach it directly, and widening the
listener would discard the local-only security boundary. Killing a local SSH
client is also not a correct implementation of build cancellation: the remote
Bazel client can survive and continue holding the workspace command lock.

## Decision

### A saved workspace selects one execution session

A user-level workspace is distinct from a captured build session. It has a
stable ID and a user-visible name, and records one repository/working
directory, its Bazel executable, and either this computer or one SSH
destination. Several workspace records may point at different repositories on
the same machine. They are stored as non-secret application settings, ordered
by the time the user last opened them, and shown on the initial workspace
screen. Choosing one opens the application shell with that execution context;
no captured build session has to be chosen first. The Workspaces menu can
create, edit, remove, reconnect, close or switch that selection later.

Choosing an SSH workspace is the explicit action that creates its OpenSSH
connection; choosing a local workspace establishes the equivalent direct local
execution context. Merely opening or importing a captured session never
chooses a workspace, reconnects to a recorded host, or executes a recorded
command. A captured session may be opened without a live workspace, and a
workspace may remain selected while the user opens different captured
sessions. Repository, editor, process and terminal operations belong to the
selected live workspace; analysis views belong to the opened captured session.

Workspace records live in the bounded, atomically replaced
`settings/workspaces.properties` file. Existing launcher-local and saved-SSH
values can be migrated once, but `settings/launcher.properties` is not the
workspace source of truth after migration. A profile contains no password,
private key, authentication option or captured-session command.

### Workspace discovery is ephemeral local configuration

Preferences may contain one optional workspace-discovery script. The script is
persisted as bounded, atomically replaced user configuration at
`settings/workspace-discovery`, but every workspace it prints is ephemeral: a
new invocation replaces the complete previous discovered set, and discovered
profiles are never written to `workspaces.properties`. The startup screen and
workspace menu mark those rows as **Discovered** and do not offer Edit or
Remove actions for them. Opening one selects an execution context for this
process only; it does not turn the row into a saved profile.

Discovery always executes on the desktop machine, on a blocking-I/O worker,
and never through a selected workspace or captured session. A non-empty script
must begin with a shebang and is invoked directly so the operating system, not
an application-selected shell, chooses its interpreter. Execution time,
stdout, stderr, and accepted rows are bounded. Invalid rows and partial results
remain visible as diagnostics rather than being silently discarded. The
script editor derives syntax highlighting from that same shebang.

Each non-comment stdout line is pipe-delimited. `local|name|working-directory`
creates a local profile and `ssh|name|destination|working-directory` creates an
SSH profile; newly discovered profiles use `bazel` as their executable. A user
may replace that command or path after opening the Workspace. Its deterministic
discovery ID may recover only that Bazel override and bounded command history
on a later discovery run; the sidecar contains no connection, repository,
label, draft, or complete profile and cannot make an absent discovery result
openable. The SSH destination is passed to OpenSSH as a destination or
configured `Host` alias.
Because discovery does not add another output field for SSH options, custom
ports, jump hosts, identities, and similar connection details belong in the
user's OpenSSH configuration. No discovered line may contain a password,
private key, or arbitrary SSH option.

The saved script is deliberately executable configuration and may exercise the
desktop user's permissions; imported sessions and discovered stdout cannot
install or modify it. Stdout rows are parsed only as profile data. A discovered
SSH row still opens a connection only when the user explicitly chooses it.

### One execution session owns the machine boundary

A selected workspace owns an explicit execution session. A local session uses
direct process and filesystem implementations. An SSH session uses OpenSSH for
both, and owns one control connection from workspace selection until that
workspace is closed or replaced. A capture borrows the selected session and
adds only capture-scoped BES, reverse-forward and staging resources; it neither
opens an independent SSH connection nor owns the selected one. The capture
coordinator, post-build queries, repository browser, file links, editor, and
terminal all use the same session rather than independently deciding whether a
path or command is local.

Process execution and file operations remain separate interfaces. File APIs use
logical workspace paths, not `java.nio.file.Path`, because a remote Linux path
must never be mistaken for a path on the desktop. Reads, directory listings,
metadata, and saves are blocking service calls; Swing callers run them on
workers and install immutable results on the EDT.

### Recovering a selected workspace connection

Operations on an explicitly selected live workspace may reestablish its failed
control connection once before reporting failure. Recovery is serialized per
workspace, uses the same OpenSSH destination and host-key policy, and preserves
filesystem identities. Closing the workspace disables recovery. This does not
authorize connecting to hosts from imported sessions.

Read-only metadata and immutable snapshot downloads may retry once after
successful recovery. Arbitrary commands, uploads, and atomic saves are not
replayed after dispatch: transport loss cannot prove they did not take effect.
Their failure remains visible even when the connection was restored. Existing
shells and builds are not restarted. Active BES forwards must be restored on
their original remote ports; inability to do so is a recovery failure.
Old process identifiers are not signalled through a replacement transport,
because a restarted host may have reused them. Multiplexed SSH and SFTP clients
use `ProxyCommand=false` to refuse independent connections if their control
socket disappears. Only the owning master connects using the user's configured
proxy/jump-host route. This addresses OpenSSH's documented
[fallback to a normal connection when multiplexing fails](https://man.openbsd.org/ssh_config#ControlMaster).

### The BES stays local; SSH carries it

The BES continues to listen only on `127.0.0.1`. An SSH reverse forward maps a
loopback port on the remote host to that local listener. Remote Bazel is given
`grpc://127.0.0.1:<remote-port>`. No application socket binds a LAN or public
interface, and the SSH server's normal reverse-forward policy remains in force.
Tunnel establishment must succeed before the instrumentation plan can be
approved.

The system OpenSSH client and its SFTP subsystem are used through direct argv.
Ordinary channels use `ProcessBuilder`; the interactive terminal uses Pty4J's
`PtyProcessBuilder` so the desktop OpenSSH client itself has a real local PTY.
Host
keys, agents, jump hosts, and authentication continue to use the user's SSH
configuration. The application does not implement SSH cryptography, store a
password, weaken host-key checking, or place credentials in a command string.
One private OpenSSH control connection supplies the command, terminal, reverse
forward, and SFTP channels. File contents move through non-interactive SFTP;
fixed non-TTY SSH helpers provide structured metadata and the content-stamp
check around an atomic rename. Human-formatted `sftp ls` output is not parsed.
Before a bounded download starts, a fixed helper copies at most the requested
bytes into a client-named, private, read-only remote snapshot. The source's
metadata and the snapshot's exact size are checked before SFTP reads that
stable file and checked again before an adjacent local temporary is published
with an atomic move. A detected source size or modification-time change is
refused. Timeout, interruption, mismatch, or any other failure leaves an
existing destination unchanged and attempts to remove both temporaries; an
unsuccessful remote removal, including after transport loss, is retained as a
cleanup failure rather than hidden. This makes the retained transfer bound
structural rather than dependent on periodic file-size polling.

The primary remote build and the remote interactive terminal use forced remote
TTYs. The terminal's local PTY lets OpenSSH forward window-size changes to the
remote PTY; JediTerm consumes and produces that PTY byte stream but does not
replace SSH. A local workspace instead starts the inherited login shell (or
`/bin/sh` when no shell is configured) directly inside a resizable Pty4J PTY,
with its working directory set to the selected repository and
`TERM=xterm-256color`.
Before the login shell starts, the fixed remote command applies `stty sane` so
desktop termios values cannot disable normal Enter-key handling on Linux.
Probes, file transfer, and graph-query protobuf streams do not use a TTY because
a TTY would merge channels and alter binary bytes. A graceful build stop sends
the TTY's interrupt character; later cancellation rungs target the isolated
remote command group. Closing only the local SSH process is not treated as
proof that the remote Bazel process stopped.

### Remote output is staged, then preserved locally

The reverse listener is bound explicitly to remote `127.0.0.1` on a port
allocated by OpenSSH, rather than guessing that the desktop listener's port is
free on a different machine.

Instrumentation files written by Bazel use a unique, private remote staging
directory. After the primary command, each planned execution log, trace
profile, or BEP fallback is copied into the managed session's local `raw/`
directory before an importer reads it. Aquery and cquery stdout stream directly
to local files; enforcing a byte ceiling on those binary outputs remains
required because the blocked t3 work has not landed. Cquery's generated query
file is uploaded before the query starts. A failed or missing transfer is
recorded as unavailable and does not invalidate event data already journaled.

The managed session remains local and raw-first. Remote files are never parsed
in place and are never silently omitted from a completeness claim.

### Repository tools are explicit and bounded

The repository browser lists one directory only when expanded. Opening a file
performs one bounded text read using the existing 16 MiB limit and binary-file
refusal. Saving is explicit and conflict-aware: it succeeds only if the remote
or local revision still matches the bytes that were opened, and replaces the
file atomically where the target filesystem supports it. BUILD files use Python
highlighting for Starlark; other files use the existing filename-based language
selection.

A terminal is available for every selected workspace. Navigating to its tab
opens the shell automatically and idempotently; visiting another tab does not
close it. JediTerm provides xterm-compatible parsing, colours,
alternate-screen programs, selection, copy/paste, mouse input and keyboard
handling. Pty4J supplies a local PTY around either the local login shell or the
existing system OpenSSH process; it does not add an SSH implementation or
credential store. Scrollback is bounded at 20,000 lines. Emulator I/O and PTY
lifecycle work runs on the window-owned virtual-thread blocking-I/O executor
and its named, single-virtual-thread timer, never the Swing event thread. The
application overrides JediTerm's executor manager so it cannot create its
default cached or scheduled platform-thread pools. A pass-through reader ahead
of JediTerm bounds one unterminated CSI sequence at 1,000
characters and one unterminated OSC or DCS string at 65,536 characters. It
counts while streaming rather than retaining another copy. Reaching either
bound closes the terminal and displays the error, because JediTerm 3.74
otherwise accumulates these sequences until a terminator arrives. The CSI limit
leaves headroom below JediTerm's fixed 1,024-character unsupported-sequence
rewrite buffer. Ordinary
output and properly terminated control sequences are unchanged. The process
persists while the user visits other tabs and closes when that workspace or the
application closes. Imported session data can never create an SSH connection,
start a terminal, or execute its recorded command. Reopening a recorded remote
build requires choosing a workspace explicitly. Closing gives an OpenSSH
terminal process one second to terminate, then forcibly kills and boundedly
reaps it. Local terminal teardown likewise waits up to one second after
graceful termination and one second after forcible termination, so a
TERM-ignoring process cannot retain its waiter.

### Terminal dependency review

JediTerm core and UI 3.74 come from JetBrains' official public
IntelliJ-dependencies Maven repository. The project grants a choice of
Apache-2.0 or LGPL-3.0, and this application selects Apache-2.0. Its generated
POM names only LGPL-3.0, so the release review compared the official 3.74 source
jars with the corresponding `core/src` and `ui/src` in the official GitHub
tree: `diff -qr` found no differences. That exact tree carries both licence
files. The artifacts were built 2026-07-31 and the source repository was active
through 2026-07-28, making maintenance current at adoption.

Pty4J 0.13.8 is EPL-1.0 and is the exact version JediTerm 3.74's standalone
build pins; matching that tested pair is preferred to independently selecting
the newer 0.13.10. That newer release also shows the official JetBrains project
is still maintained; this older pin is compatibility policy, not abandonment.
Its resolved JNA 5.14.0 dependency is used under Apache-2.0 from JNA's
Apache-2.0/LGPL-2.1 choice. Kotlin stdlib 2.4.0 and JetBrains annotations 24.0.1
are Apache-2.0; the application's existing SLF4J 2.0.18 wins over the lower
versions requested by both additions. The Maven lock records every artifact and
SHA-256. The UI package owns only JediTerm core/UI, while the runner packages
that create local and SSH PTYs own Pty4J; no unrelated target gains the terminal
stack.

The deploy jar retains a dedicated `META-INF/third-party/` payload instead of
depending on colliding generic `META-INF/LICENSE` entries from merged Maven
jars. It includes JediTerm's selected Apache-2.0 text, Pty4J's complete EPL-1.0
text and notice, and the required license choices and notices for JNA, Kotlin,
JetBrains Annotations and SLF4J. Because the Pty4J jar also redistributes
WinPTY and Windows Terminal native binaries, their MIT texts and Windows
Terminal's third-party notice are retained on every host. A packaging test
checks the reviewed legal-file hashes in the finished deploy jar.

The added resolved jars total about 8 MiB, dominated by Pty4J's cross-platform
native resources, JNA and Kotlin. That cost is accepted for correct terminal
semantics. The emulator retains at most 20,000 scrollback lines. Its injected
executor manager keeps all emulator I/O and PTY lifecycle work on window-owned
virtual executors and off the EDT; no terminal latency or frame-rate claim is
made without measurement. Packaging must retain and smoke-test the signed
native resources as described in `docs/packaging.md`.

## Consequences

- `docs/privacy.md`'s former "no outbound network code" statement is superseded
  for a user-selected SSH workspace. Telemetry, session-driven reconnects and
  other implicit networking remain absent.
- Product-plan section 22.1's loopback BES rule remains intact. This decision
  changes reachability through an authenticated user-created tunnel, not the
  listener binding.
- Remote command display distinguishes the Bazel argv from the SSH transport
  and explains the reverse forward, forced TTY, remote staging paths, and the
  fact that a TTY cannot preserve separate remote stdout/stderr channels.
- The first implementation targets Linux servers with OpenSSH and common POSIX
  userland tools. Directory pages fully consume GNU `find` through Bash
  `pipefail` and retain only a page-sized max-heap before emitting the next
  path-keyset page. Opaque continuations bind the last literal path to a
  directory revision, totals stay unknown, and no whole-directory scratch file
  or numeric offset is created. Unsupported hosts fail preflight with a
  concrete missing-tool message; there is no silent fallback to local
  execution.
- The terminal is now a transport-neutral, bounded emulator rather than a line
  transcript. JediTerm 3.74 supplies the Swing/xterm layer, and Pty4J 0.13.8 is
  deliberately the version its standalone application uses. The latter adds
  JNA and signed native host resources, so packaged-app smoke tests must
  exercise local and SSH PTY startup, input and resize as well as ordinary Java
  launch.

## Revisit when

- a second remote transport is required;
- OpenSSH's allocated-port reporting proves unreliable on a supported server;
  or
- a non-OpenSSH transport is required; JediTerm and Pty4J deliberately do not
  change the authentication or tunnelling boundary.
