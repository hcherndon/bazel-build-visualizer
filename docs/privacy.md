# Privacy

Build data is sensitive: command lines, environment variables, file paths,
and hostnames routinely contain credentials, internal project names, and
user identity. The tool is local-first and treats captured data accordingly
(plan section 22).

## Network posture

- The built-in BES endpoint (`capture-bes`) binds **loopback only**
  (`127.0.0.1`), never `0.0.0.0`. There is no option to widen this.
- The application has no telemetry, update ping or crash upload. Its built-in
  outbound transport starts only when the user chooses a saved, discovered or
  new **SSH host** Workspace: that explicit action runs the system
  `/usr/bin/ssh` and `/usr/bin/sftp` clients for the destination (ADR-011).
  Opening captured data never does so.
- The remote BES address is also loopback. OpenSSH maps an allocated remote
  `127.0.0.1` port back to the desktop listener; neither endpoint binds a LAN
  or public interface. The SSH server must permit the reverse forward.
- All captured data stays in the local session directory
  (docs/session-format.md). Bazel first writes auxiliary capture files into a
  private mode-0700 remote staging directory, then SFTP copies them into the
  local session's `raw/` directory before they are parsed. Cleanup is limited
  to the exact generated file names and staging directory.

SSH host keys, agent use, jump hosts, authentication and connection policy come
from the user's normal OpenSSH configuration. The application forces batch
mode, disables agent/X11 forwarding and remote local-command hooks, and neither
implements SSH cryptography nor stores a password or private key. A Workspace
saved for reuse after restart contains a stable ID, user label, local/SSH kind,
working directory, Bazel executable, last-opened time and, for SSH only, its
destination or Host alias and optional port. Profiles are bounded and written
through a sibling temporary file and atomic replacement; they contain no
captured command or authentication option.

The application also persists the stable IDs and screen geometry of open
Workspace windows in `workspace-window-state.properties`. On restart, a saved
SSH profile that was left open reconnects automatically. This restores an
execution choice the user already made; it is never inferred from a captured
session. The state file contains no credentials and cannot restore an
ephemeral discovered SSH profile unless startup discovery emits that same
deterministic ID again. Removing the saved Workspace or forgetting its
previous window prevents restoration.

Each saved profile can also have a private directory below
`settings/workspace-windows/`, named with the SHA-256 digest of its stable ID.
It contains launcher state (including command history and the most recently
saved draft) and table/query-result presentation settings. Restoration clears
the command draft before showing Console but deliberately retains history.
Commands and target labels can be sensitive; protect this directory like the
Workspace profile store. A discovered profile stores only its Bazel executable
override and bounded command history below
`settings/discovered-workspace-history/`, keyed by the SHA-256 digest of its
deterministic ID. That file contains no profile label, machine, working
directory, connection details, command draft, preset, or presentation state,
so it cannot recreate or connect to a Workspace that discovery did not emit.
Private saved-profile state is removed only after profile removal is saved, and
orphan cleanup is skipped when the profile store cannot be trusted.

Workspace Discovery is an explicit exception to treating settings as inert
data. The optional saved script is executable local configuration and runs
directly according to its shebang at graphical startup and on request. It has
the same filesystem, process and network permissions as the desktop user, so a
script can make its own outbound connections even though the application has
no telemetry client. Save only trusted code. Imported sessions cannot create,
replace or trigger the script.

Discovery stdout is bounded and parsed only into local or SSH profile fields;
it is not evaluated as code. Every invocation replaces the prior in-memory
discovered set. Those profiles are tagged **Discovered**, are never written to
`workspaces.properties`, and are not added to a session or export. A discovered
SSH destination still makes no connection until the user selects that row.
Invalid rows and process failures are exposed through bounded local
diagnostics.

The in-app file viewer follows only an explicit click. A selected local
Workspace resolves repository paths through its local `ExecutionFileSystem`.
An explicitly connected SSH Workspace resolves them through its own filesystem
and uses SFTP for content; arbitrary remote URI authorities are still refused.
Historical local `file:` URIs remain local links, while recorded remote paths
are provenance unless a user separately chooses a live Workspace. The
Console's **Bazel Executable** is a typed Workspace setting; it does not browse
or read file metadata when edited. BUILD-file edits are written only after the
user presses **Save**, and a
content-stamp check prevents the editor from silently replacing newer local or
remote work. Reads remain bounded and binary-refusing.

The Events inspector likewise delays file metadata until the user visits its
Files tab. Open File and Copy remain explicit. Reveal in Finder applies only to
a local file; a recorded SSH path is not reinterpreted on the desktop. Opening
historical remote session data never reconnects to its recorded host, starts a
terminal or executes its command. Remote file links work only while a user has
an explicit live connection.

Post-build aquery and cquery commands run on the same explicit execution host as
the primary Bazel command. They query the requested targets' transitive
dependency closure and may therefore record internal dependency labels that
were not typed on the original command line. Their exact argv and output paths
are shown before launch and retained in the session. Remote protobuf output is
streamed through a non-TTY SSH channel into local managed files; neither query
executes text found inside imported data.

Terminal starts automatically when a user navigates to its tab with a Workspace
selected. For a local Workspace its bytes stay in a local login-shell PTY. For
SSH, keystrokes, pasted text and terminal-protocol replies can be sent to the
chosen host, while that host's terminal control sequences are rendered locally.
The channel persists while other tabs are selected and closes with the selected
Workspace or the application. It is never created from manifest provenance or
another imported file.

## Application logs

Graphical application logs stay below the local application-support directory
and are never uploaded, added to a session archive or exported automatically.
They can contain absolute workspace and file paths, target labels, SSH display
destinations, session identifiers, timing summaries and exception messages.
That is useful diagnostic context and also sensitive build data; protect and
review a log before sharing it.

The application does not intentionally record environment values, full raw
command argument vectors, BEP payloads, file contents, terminal input or
output, SFTP scripts, authentication material or SSH control-socket paths.
Commands are logged as structural summaries such as execution kind, command
name and argument count. Workspace Discovery likewise logs only structural
outcomes, counts, byte totals and duration, not the saved script, stdout,
stderr or rejected row text. A manual diagnostics dialog can show bounded
stderr, so review that text before copying it. An exception supplied by an
operating-system tool can still carry sensitive failure text, so logging is
not a redaction boundary. Control characters in messages and stack traces are
rendered as printable escapes, keeping every event to one physical log line.

The active file and retained history are bounded and rotated locally. Logging
callers never wait for disk I/O: a bounded writer queue reports an exact dropped
record count in the Diagnostics menu and writes overflow warnings once the
writer catches up. This loss affects diagnostic records only, never raw capture
or normalized session data. One operating-system lease prevents concurrent app
instances from rotating the same destination; a second instance reports that
file logging is unavailable instead of sharing it unsafely.

## Redaction

Redaction applies to *displayed and exported* data; the raw journal remains
faithful (ADR-004) and is treated as sensitive at rest.

- Pattern-based redaction of likely secrets in command lines and
  environment values: `*_TOKEN`, `*_SECRET`, `*_KEY`, `*PASSWORD*`,
  `AUTHORIZATION`, bearer-token shapes, URLs with embedded credentials.
  The pattern list ships with the app and is user-extensible.
- Sensitive well-known fields are masked by default in the UI:
  environment variable values, `--remote_header` values, repository-rule
  credentials.
- **Export** (sharing a session or a report) runs redaction mandatorily and
  shows the user exactly what was redacted before anything is written.

## Sensitive-field inventory

Maintained as the schema lands (Phase 3+): every column/manifest field that
can carry user-identifying or secret data is tagged in
[docs/database-schema.md](database-schema.md) and handled by the redaction
layer. Schema v2's twelve such columns are listed there with the treatment
each gets. Beyond the database: effective and original command lines,
environment blocks, remote headers, and hostnames in profile metadata, which
arrive with Phase 4.

Schema v7's `unresolved_artifacts` and `unresolved_depset_references` are
non-sensitive numeric counts. Redaction leaves them intact so an exported
session cannot regain a false graph-completeness claim.

Schema v8's `graph_sources.target_scope` enum is non-sensitive and remains
intact. `target_scope_detail` can repeat labels or command context, so export
redacts it as text.

Schema v9's Starlark pprof string table is sensitive. It can contain repository
paths, rule and macro names, function names, and arbitrary labels, so export
redacts `starlark_profile_strings.value`. Structural ids, numeric samples,
counts, the format/validation enums, and label value-kind enum remain intact;
`starlark_profile_metadata.validation_detail` is redacted as free text. The raw
`starlark-cpu.pprof.gz` is faithful source data and is omitted from a redacted
archive under the same rule as every other raw capture.

Saved Workspaces are also sensitive local settings: labels, local or remote
directories, Bazel paths, and an SSH destination or Host alias can disclose
host, account and project names. They contain no authentication material, but
should be protected and shared with the same care as the user's SSH
configuration. They are execution settings, not part of a captured session or
its exports.

The persisted Workspace Discovery script is more sensitive than a profile: it
is executable code and may itself contain paths, hostnames or configuration.
Discovered profile rows are ephemeral, but their labels, directories and SSH
destinations remain visible in the current process and should be treated as
sensitive while present.

A live SSH session's manifest separately records its execution kind, display
destination and optional port as provenance. That can also disclose a host
name. It contains no authentication material, but it is part of the sensitive
session artifact and can be carried by a portable archive.

**The redaction layer landed in Phase 9.** `core.redact` holds the patterns,
the policy and the engine; `storage.redact.SessionRedaction` names every column
it rewrites; and `SessionRedactionTest` asserts that every `TEXT` column the
schema declares appears either in that list or in the list of columns
deliberately left alone. An inventory in a document is checked by somebody
remembering to read it; that one is checked by running it.

A session directory is still a sensitive artifact at rest. What changed is that
there is now a way to produce something that is not.

## Cquery configuration options (Phase 5)

Bazel 8.4.1 and newer can put every effective configuration option into cquery
output. Those values include user inputs such as defines, action environments,
credential paths and remote headers. The raw cquery protobuf remains faithful
and sensitive at rest. The normalized `queried_configuration_options` table
withholds values whose option names match the execution-log secret patterns,
plus Bazel's remote-header names, and records `redacted = 1`; withheld is not
presented as empty or equal during comparison. Export applies the regular
name-based redactor again, treating an option name as its `--flag` form.

## Execution-log environment variables (Phase 4)

An execution log records every spawn's full environment, so importing one
brings the build's environment into the session database. Values whose names
look like secrets are withheld on the way in, by `EnvironmentRedactor`, using
the default patterns of plan 22.2: `TOKEN`, `PASSWORD`, `PASSWD`, `SECRET`,
`CREDENTIAL`, `KEY`, `AUTH`, `SESSION`, `COOKIE`, `PRIVATE`. Matching is
case-insensitive and by substring, and the list is constructor-injectable.

Two properties matter more than the list:

**By name, never by value.** Detecting a secret by looking at it means
guessing, and guessing wrong leaks. A name rule is conservative in the safe
direction — it withholds some things that were not secret, and the user can see
that it did.

**Withheld is not the same as unset.** `attempt_env_vars.redacted` records that
a value was hidden rather than absent, and the record type refuses to carry a
value and the flag at once. `PATH=""` and `API_TOKEN=<withheld>` are different
facts about a build, and a user asking why two actions behaved differently
needs to tell them apart.

The spawn's argv is stored too, and carries absolute paths and command
arguments. The plan's added-flag metadata marks both the execution log and the
profile as writing files that may contain sensitive data, so the
instrumentation dialog says so before the build runs.


## What redaction does, and what it does not claim (Phase 9)

### Pseudonyms rather than a mask

Each distinct secret gets a stable name — `[redacted:9f2c1a7b04]` — and the
same value redacts to the same name everywhere in one export. A mask that
replaced everything with `****` would destroy the thing a reader most needs
from a shared session: whether the token in these ten thousand actions is *the
same* token. Measured on a synthetic 500,000-action session: 505,001 redactions
over 5,000 distinct values, which is exactly the shape that tells a reader they
have five thousand credentials rather than one.

The pseudonym is a truncated digest under a random key generated per export and
never written anywhere. A bare digest of a low-entropy secret is a password
hash, and a password hash in a file being sent to a colleague is the secret with
one extra step.

### Paths keep their shape

An absolute path has an identifying prefix and an informative remainder. The
workspace and output base are read from the session's own tables and mapped to
`[workspace]` and `[output-base]`; a path under no known prefix has its account
name masked, because `/Users/<name>` is who ran the build. So an exported path
reads `[workspace]/bazel-out/darwin/bin/a.o`, which is still a path a reader can
reason about.

### Name patterns are globs, not regular expressions

Plan 22.2 requires the list to be user-editable, and a user-supplied regular
expression run once per argument over five million actions is a
denial-of-service risk — catastrophic backtracking is a property of
ordinary-looking patterns. A user writes `*_TOKEN`; it compiles to an anchored,
alternation-free expression. The three value rules that need real expressions —
a bearer token, a URL with credentials, a PEM block — are built in and fixed.

Only three value shapes, deliberately. A rule broad enough to catch "any long
random-looking string" would redact digests, action keys and configuration
checksums — most of what makes an export useful — and would still miss a
password that is a dictionary word.

### A redacted archive cannot carry the raw capture

The raw journal is the original bytes, faithfully (ADR-004), secrets included.
Exporting it beside a redacted database would undo the redaction completely, so
a redacted `.bviz` has no `raw/` directory and the reader refuses an archive
that claims to be both redacted and complete. The consequence is stated when
one is opened: its enrichments cannot be re-run and its database cannot be
rebuilt from source bytes.

### The report is the promise, not the redaction

An export shows what was redacted before anything is written: counts by rule and
by field, the number of distinct values, and how many values were inspected. It
quotes nothing. It reports finding nothing as a result rather than as silence.
And it ends by saying that pattern matching finds what it was told to look for.

The application does not claim an export is safe to share. It says what it
removed, and the decision rests on something a person read.

### What the UI masks

`RedactionPolicy.forDisplay()` masks secrets and leaves paths alone: the person
at the keyboard already has the session on their disk, and a path they cannot
paste into a terminal is worse at the job the inspector exists for. It is
applied where a build's own credentials would otherwise be rendered a line at a
time — the action inspector's argv.

### The two exports that are not redacted

- A **complete** `.bviz` archive contains the raw capture and is as sensitive as
  the machine it was taken on. The application asks before writing one, in those
  words.
- A **table export** can be written unredacted for a user dumping their own
  session; the result says `NOT redacted` and names what it carries.
