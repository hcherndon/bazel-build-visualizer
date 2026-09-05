# Security and privacy review

Plan section 22, clause by clause, with the evidence for each and the gaps
stated. Reconciled for the 0.1.0 release review; every claim below names the code
or the test that makes it true, so a reviewer can check rather than believe.

The threat model this is written against: a local single-user desktop tool that
launches a build on this computer or on a Linux SSH host the user explicitly
selects, and that **opens files other people sent** — a `.bviz` archive, a BEP
file, a session directory. Imported and historical data is the untrusted side:
it can never initiate that SSH connection or execute a recorded command.
Nothing here defends against an attacker who already runs code as the user or
controls a host the user chose to trust through OpenSSH. The optional Workspace
Discovery script is also user-authorized executable code, not imported data;
protecting a settings directory already writable by an attacker is outside
this threat model.

---

## 22.1 Loopback BES and explicit SSH networking

ADR-011 supersedes the historical plan's blanket local-only statement while
preserving its loopback-listener and no-telemetry requirements.

| Requirement | Status | Evidence |
|---|---|---|
| Bind BES to loopback | Met | `BesEndpoint` refuses any host that is not loopback, in its constructor — before a socket exists. `BesEndpoint.LOOPBACK` is `127.0.0.1`. |
| Reject non-loopback configuration in v1 | Met, with no developer switch | The plan permits a developer-only override; none was written. There is no code path that constructs a non-loopback endpoint, so there is nothing to leave enabled by accident. |
| Do not expose an HTTP server | Met | No `HttpServer`, no servlet, no embedded web anything. The only listening socket in the application is the gRPC BES endpoint. |
| Do not send telemetry by default | Met | There is no telemetry, update or crash-upload client. Built-in networking is the system OpenSSH client after the user explicitly selects or reconnects an SSH Workspace (ADR-011). An optional user-authored discovery script runs locally at boot and can use the user's network permissions by virtue of being executable code; imported session data cannot configure or trigger it. |

The BES listener remains loopback-only. SSH remote mode does not widen it:
OpenSSH allocates a remote `127.0.0.1` port and reverse-forwards that to the
desktop listener. The app passes direct argv to `/usr/bin/ssh` and
`/usr/bin/sftp`, preserves the user's host-key policy, forces batch mode, and
disables agent/X11 forwarding and SSH local-command hooks. Authentication stays
in OpenSSH configuration or an agent. Saved Workspaces carry a stable ID and
label, local/SSH kind, directory, Bazel executable and last-opened time, plus
only destination and optional port for SSH. They carry no credential,
authentication option or captured command.

The saved Workspace Discovery script is a separate, bounded settings file. A
non-empty value must have a shebang and is executed directly on a blocking-I/O
worker at graphical startup or on explicit request. No application-selected
shell wraps it. Its time, stdout, stderr, accepted row count and visible
diagnostics are bounded. Each invocation replaces the complete previous
discovered set; those profile objects stay in memory and are not written to the
saved Workspace store. Stdout is parsed as profile data, never as a command.
Line diagnostics identify the line and reason without echoing its raw text.
Selecting a discovered SSH row remains the separate action that starts OpenSSH.

Repository-browser icons do not add a networking path. A case-insensitive,
fixed filename map chooses among classpath resources bundled with the
application; untrusted repository names are never concatenated into a URL or
resource path. JSVG parses only the reviewed packaged SVG resources: the fixed
32-file Material Icon Theme subset and two project-owned SVGs. It does not parse
repository files, imported event data, or text supplied by a remote host, and
an unknown type falls back to the packaged text-document icon. The set contains
no script or external resource reference, and the application never downloads
icon artwork at runtime.

Repository browsing follows only exact Bazel convenience symlinks that are
direct children of the selected workspace root. Following happens only when
the user expands a link, on the existing bounded worker and under the exact
5,000-visible-entry cap. Ordinary and nested symlinks remain leaves, so a link
cycle is not traversed recursively. A recognized link may resolve outside the
workspace root because Bazel's output tree normally does; it has no authority
beyond the already-selected local or SSH execution filesystem.

---

## 22.2 Sensitive data

The full treatment is in `docs/privacy.md`. What matters for a release review is
what each of the plan's nine sensitive-field categories actually gets.

| Category | Treatment |
|---|---|
| Absolute paths | Prefix-mapped on export (`[workspace]`, `[output-base]`); the account name in an unmapped home path is masked. Shown unmasked in the UI, deliberately — see below. |
| Command arguments | Value rules and flag-name rules on export; masked in the action inspector on screen. |
| Environment values | Redacted by name rule on export, and optionally omitted entirely while keeping the names. |
| Repository names | Inside labels; pseudonymised only when the user asks, because it costs most of the export's usefulness. |
| Remote-cache endpoints | `--remote_header` and friends are name rules; a URL with credentials in its authority is a value rule. |
| User names | The `/Users/<name>` and `/home/<name>` masking. |
| Test logs | `test_logs.uri` is a path column in the redaction inventory. |
| Artifact names | `artifacts.path`, `name`, `path_prefix`, `uri` — all in the inventory. |
| Credentials passed to actions | The three value rules, applied to `actions.command_line` and to `strings.value`. |

ADR-011 adds two non-credential hostname surfaces outside that nine-row plan
inventory. Saved local/SSH Workspaces stay in local settings; session manifests
record SSH display/destination/port as execution provenance. Both may disclose
internal host or project names and are treated as sensitive local data, even
though neither contains a password or key.

Workspace Discovery adds a local executable-settings surface. The persisted
script may contain paths, hostnames or any other text its author puts there.
Its discovered labels, directories and SSH destinations remain ephemeral, but
are visible in the current process. Neither the script nor its results enter a
captured session or portable archive.

Application observability adds a third local hostname/path surface. The GUI
application log can contain absolute paths, labels, SSH destinations, session
identifiers, timings, and exception or failure text. Raw command vectors,
environment values, file and terminal content, authentication material, SFTP
scripts, and SSH control-socket paths are not intentionally logged, but failure
text is not a redaction boundary. The log remains in the local application
support directory and is never uploaded or exported automatically. Its active
file is limited to 8 MiB; compressed history is limited to seven days and
64 MiB. An operating-system lock permits only one process to own the rolling
destination at a time. Embedded control characters are escaped so a hostile
path or exception cannot forge a second physical log record. Treat the files
as sensitive local diagnostic data and review them before sharing.

**The inventory is a test.** `SessionRedaction` names 35 columns it rewrites and
83 it deliberately leaves alone, and `SessionRedactionTest` asserts that every
`TEXT` column in the schema appears in one list or the other, and that no listed
column has been renamed away. A migration that adds a column fails the build
until somebody decides which it is.

**Why the UI does not mask paths.** `RedactionPolicy.forDisplay()` masks secrets
and leaves paths alone. The person at the keyboard already has the session on
their disk; a path they cannot paste into a terminal is worse at the job the
inspector exists for. Export is the boundary, and export masks paths.

### Gaps, stated

- **Preferences currently exposes Theme and Discovery, but not redaction
  settings**, so the user-editable pattern list of plan 22.2 is still editable
  by a caller and not by a user. The Workspaces screen is a dedicated
  execution-profile manager, not that redaction-settings surface.
  `RedactionPolicy.withUserPatterns` exists and nothing in the UI calls it.
- **A session directory at rest is unencrypted and unredacted.** That is by
  design (ADR-004, raw-first) and is stated in `docs/privacy.md`; the protection
  is filesystem permissions, the same as any other file the user owns.
- **The redaction report is honest, not exhaustive.** It says what the patterns
  matched and ends by saying that pattern matching finds what it was told to
  look for. No claim is made that an export is safe to share.

---

## 22.3 Process safety

| Requirement | Status | Evidence |
|---|---|---|
| Use direct argv by default | Met | `BazelLauncher.launch` builds a `ProcessBuilder` from `command.toArgv()`. No shell is involved unless shell mode is on. |
| Do not interpolate command text into shell scripts | Met by default | The only string-joining path is `shellArgv`, reachable only when `command.shellMode()` is true. |
| Clearly label explicit shell mode | Met | Shell mode is opt-in, and the argv it produces — `<shell> -c <joined command>` — is displayed verbatim in the instrumentation plan before anything runs. |
| Keep discovery execution separate from imported data | Met | The persisted Preferences script is invoked directly according to its shebang. The app does not interpolate imported or discovered text into it; stdout becomes bounded profile data only, and opening a discovered profile is still explicit. |
| **Do not execute commands embedded in imported sessions** | Met | Checked by grep during this review: `manifest.originalCommand()` is read in exactly one place outside `session-format`, by `CatalogEntries`, and only to build a display name for the library list. Nothing constructs a `BazelCommand` from a manifest. |
| Imported session commands are display-only | Met | As above. The Console command comes from its editable launcher field, which the user typed; a selected Workspace supplies execution location, never a historical command. |

For SSH execution, local OpenSSH itself is still started through direct argv.
Remote argv crosses one audited `PosixShell` quoting boundary because OpenSSH's
remote-command protocol is shell text; arguments are quoted individually and
the SSH destination validator refuses option injection. The primary build uses
a forced TTY and the dialog discloses its merged stdout/stderr. Probes and
binary query streams are non-TTY. Cancellation targets the reported remote
process group rather than assuming that closing a local transport stopped it.

This is the clause most worth re-checking whenever the session library grows a
feature: an "open recent and re-run" button would violate it. The recent
Workspace menu is safe precisely because Workspace records contain execution
location but no captured command, and opening a recent captured session does
not select or reconnect a Workspace.

---

## 22.4 Archive and parser safety

The archive clauses below are enforced by `BvizIndex`, `BvizReader`,
`BvizPaths`, and `ArchiveImport`; hostile-format and adoption tests exercise
them after Java has opened the Zip container. That timing leaves one important
gap stated in the first row.

| Requirement | Status | How |
|---|---|---|
| Bound archive metadata before ZIP parsing | **Not met — release blocker** | The current path constructs `ZipFile` before applying the entry and expansion limits, so a hostile central directory reaches the JDK ZIP parser first. The reviewed replacement still had a snapshot-cleanup ownership defect and remains unmerged. Treat imported archives as trusted input for 0.1.0 source builds. |
| Prevent zip-slip | Met, twice | An allow-list first — a session archive holds a known set of files, so anything else is refused before any path resolution — and a canonicalised containment check second. |
| Limit expanded archive size | Met | Counted from bytes the decompressor produced, never from the size the entry declares. |
| Limit entry count | Met | `BvizLimits.maxEntries`, 50,000. |
| Reject duplicate manifest entries | Met | A path listed twice in the index, or present twice in the Zip, is refused: nothing says which copy a reader would get. |
| Validate checksums | Met | SHA-256 per entry in `archive.json`, checked against the bytes actually decompressed. A Zip CRC-32 detects accidents and nothing else. |
| Keep archive adoption beneath the sessions root | Met | `archive.json` accepts only the canonical UUID spelling. Adoption resolves the managed root once and uses that real path for staging, extraction, and the final move. It rejects an index changed after coordinator validation and a manifest/index identity mismatch. The process mutation coordinator applies the same UUID rule to leases and cleanup locks. |
| Treat imported SQLite as untrusted | **Partially met — see below** | The schema version is validated and a mismatch is refused with the remedy. Query uses a physical read-only connection; the general finished-session open first creates a normal writer-capable connection, so t2's physical read-only open remains unmerged. |
| Never load native code from a session archive | Met | Not by refusing to load it: by never writing it. A `.dylib` is not a session file, so the allow-list refuses the entry. |

Two further defences that the plan does not name and that this review adds:

- **Entry names are restricted to letters, digits, dot, dash and underscore.**
  Every file a session contains is named that way, so it costs nothing and
  closes what path checks do not — a control character that rewrites a terminal
  when the name is printed, a right-to-left override that makes `gpj.exe` look
  like `exe.jpg`, a Unicode form that normalises differently on macOS than the
  form that was checked.
- **Entry names are escaped in error messages.** An archive's entry names are
  attacker-chosen text and error messages get printed into terminals.

### Why a schema mismatch is refused rather than rebuilt

Plan 22.4 says to prefer rebuilding from raw files *unless the archive format
and database schema pass validation*. This application validates and then
**refuses with the remedy** rather than rebuilding silently. Three reasons, and
the deviation is deliberate:

1. **A redacted archive has no raw sources to rebuild from.** The raw journal is
   the unredacted bytes, so a redacted export cannot carry it. Rebuilding is not
   always possible, and an implementation that rebuilt when it could and failed
   when it could not would behave differently for reasons the user cannot see.
2. **Rebuilding discards the sender's derived data.** Enrichments, graph
   indexes, and correlations that took minutes on their machine would be thrown
   away without asking.
3. **The refusal already names the fix.** "Import its source again — the raw
   events are preserved, so nothing is lost by rebuilding" is the same action,
   taken by the person rather than by the program.

What is *not* deferred: the validation itself. A database that is not a
database, one from an older build, and one from a newer build are each refused
with a distinct message, and `DamagedSessionTest` proves all three plus the case
where the bad database arrives inside a well-formed archive. Writable
capture/import initialization runs migrations through schema v10, while opening
an already-finished managed session does not migrate it. A duplicate assigned
graph node index makes v9-to-v10 migration roll back and leave v9 intact.

### Parser limits

The core BEP, manifest, journal, Starlark pprof, and post-`ZipFile` archive
parsers have the bounds recorded in `docs/limits.md`. They refuse rather than
presenting a truncated prefix as complete. Numeric format versions are read
with exact-width conversions, so a value such as `2^32 + 1` cannot narrow to
supported version 1.

That statement does **not** cover every imported or displayed byte path. The
current execution-log, JSON trace-profile, `aquery`, and `cquery` importers lack
the proposed source, expansion, record, fan-out, and work bounds; local query
output is not staged through the proposed bounded file path. Query result cells,
decoded Events payloads, and ANSI-normalized Errors detail also lack the late
t6 inspection bounds. The focused t2, t3, and t6 branch tests are not evidence
for this tree: all three branches remain blocked and unmerged for the reasons in
`docs/implementation-status.md#remaining-release-blockers`.

File import's default `COPY_INTO_SESSION` mode hashes bytes during the copy and
parses that exact copy. The former `REFERENCE_ORIGINAL` mode is unavailable for
new imports and resume: hashing and then reopening a mutable file cannot prove
which bytes were parsed, and size plus modification time do not close that
race. Its checkpoint enum value remains readable only to provide this explicit
refusal and safe re-import remedy.

---

## What this review did not cover

- **No fuzzing was run.** Plan 23.7 asks for it and it has not been done. The
  parsers have hand-built damage tests (`BepDamage`, `CliDamagedSourceTest`,
  `BvizArchiveTest`) which cover the failure modes somebody thought of, and that
  is a weaker guarantee than a fuzzer's.
- **No dependency vulnerability scan.** Dependencies are locked (ADR-009) and
  were license- and maintenance-reviewed when introduced, but nothing checks
  them against an advisory database on each build.
- **No review of SQLite's own parser.** An imported database is handed to
  sqlite-jdbc, which is C code reading an attacker-influenced file. The
  mitigation is the schema validation above plus the fact that the file arrives
  in an archive whose every other entry was checksum-verified; it is not a
  claim that SQLite is safe against a malicious database file.

The SQLite native parser is a separate residual risk inherent to opening
somebody else's session at all; this review does not rank it above the unmerged
ZIP preflight and auxiliary/UI bounds.

Ordinary CI also omits signed/notarized/stapled packaging, Gatekeeper/Finder
association, local and SSH Terminal, packaged Query/cancellation, final
artifact hashes, parser fuzzing, dependency-advisory review, and the supervised
host-state real-Bazel sweep. Those are manual release gates, not evidence
produced by the safe test suite. See [SECURITY.md](../SECURITY.md) for private
reporting guidance and [packaging.md](packaging.md#release-candidate-checklist)
for the candidate checklist.
