# Build reproducibility checks

The **Hermeticity** page compares the recorded inputs, commands and outputs of
two builds. It helps find unstable outputs and changes that prevent cache reuse.
It does not award a “hermetic” pass: two matching builds can still depend on an
unrecorded clock, network service, host file or environment value. See
[Bazel's explanation of hermeticity](https://bazel.build/basics/hermeticity).

## Run a controlled check

1. Open a local or SSH Workspace. In **Console**, choose **Build mode →
   Hermeticity diagnostic**, enter a `build` command such as `build //my/package:app`,
   and press **Run diagnostic**.
2. Review the single confirmation screen. It explains the private output base,
   configuration and cost. Normal system, user and repository rc files are
   **enabled by default**, including named `--config` definitions. Select
   **Ignore rc files** only to compare builds without those settings. Changing
   the checkbox refreshes both plans and returns to review; it never starts a
   build. Exact commands and optional capture settings
   are under **Commands & capture**; inspecting each capture is not required.
   The protocol requires
   **Bazel 7.4.x or 9.2.0**, confirmed capabilities, and execution on the Workspace's
   machine. An SSH Workspace runs on that Linux host, not on the desktop.
3. Click **Run both builds** to approve the check; there is no separate
   acknowledgement checkbox. Leave the repository unchanged until it finishes. The
   app snapshots source files, cleans its private output base, runs A and
   preserves A's evidence. It snapshots the sources again, cleans the same
   private base, runs B, preserves B and takes a final source snapshot.
4. The app automatically links run A and run B and opens their comparison in
   **Hermeticity**. No file selection or second launch is needed. Start with
   **Summary**, then select an action in **Action differences**. **Coverage & runs**
   includes links to open each retained build; read its notes before interpreting
   a result.

The same Build mode dropdown retains the normal capture options. The diagnostic
selection is not restored after reopening a Workspace; existing normal capture
preferences are unchanged. A failed or cancelled diagnostic keeps available
partial runs visible without presenting them as a completed comparison.

If **Run both builds** is disabled, **Cannot start yet** at the top of Summary
explains the setup issue. If execution logs were turned off, **Enable execution
logs** restores them for both builds and checks the plans again; it does not
start either build. A failed flag probe, unsupported compact logs or conflicting
output option has its own explanation. Do not add an execution-log flag manually:
the app must choose a fresh private output file for each build.

This is a deliberately controlled experiment. It preserves normal rc settings
unless **Ignore rc files** is checked, but explicitly overrides rc defaults for
its private output base, disabled disk/remote action caches and remote execution,
protected convenience links, and resource limits. Review these differences in
**Protocol changes**. It limits the build to two jobs and
does not update `MODULE.bazel.lock`. It uses one private Bazel server with a
1 GiB Java heap cap. Repository download caches can still be reused, and an
ordinary `clean` does not promise a fully cold operating system or worker state.

Only `build` is supported initially. Shell mode, explicit startup options,
arguments after `--`, conflicting command-line isolation/cache flags and
remote or dynamic execution strategies are refused. `--config` is supported
when rc files are enabled; it is a setup blocker when they are ignored.
If rc option inspection fails, fix the configuration and retry or explicitly
choose **Ignore rc files**. Required evidence cannot
be vetoed while retaining the audit label. Builds still execute repository
code: run checks only in repositories you trust.

The execution log is checked after each build. Known cache-hit or remote-runner
observations violate this on-machine, uncached protocol and stop later steps;
their evidence is retained. Unknown runner names remain an explicit coverage
gap, not a claim that execution happened locally. The automatic protocol has
been exercised on macOS arm64; a complete Linux/SSH protocol test has not yet
been recorded. See [compatibility](bazel-compatibility.md).

## Source and cleanup safety

The three source snapshots include tracked, dirty and untracked files. They
compare names, directory entries and file bytes, not just Git status. Changes
stop the check; the app never reverts your files. Each snapshot is limited to
100,000 entries, 64 directory levels, 16 MiB per file and 1 GiB of file contents.
Root `.git` and the usual root-level Bazel convenience **symlinks** are excluded.
Other symlinks, special files, unreadable entries and exceeded limits stop the
audit. Ordinary directories with a `bazel-*` name are not automatically skipped.
File permissions, external inputs and changes made and reverted between
snapshots are not proven unchanged by this byte-level check.

With rc files enabled, the observed build options are rechecked before each
clean and changed or unavailable answers stop the diagnostic. This is not a
complete snapshot of configuration: rc files outside the repository, startup
settings and helper-specific rc sections are not fully covered by those option
checks. Bazel's textual rc announcements cannot preserve every argument boundary.
Keep all configuration unchanged throughout the operation. The saved audit
records whether rc files were read or ignored; older audit records are rc-free.

Both builds use the same newly owned private output base. Before every clean,
the app checks Bazel's resolved output-base path. `--symlink_prefix=/` protects
the Workspace's usual `bazel-bin`, `bazel-out`, `bazel-testlogs` and workspace
links. Clean explicitly uses `--noexpunge --noasync`, overriding rc clean modes.
The app does not clean your normal output base.

On success, the app shuts down its own server and removes only its verified
private base. Cancellation or failure stops subsequent builds. If an SSH
failure leaves process termination uncertain, the app does not replay the
build or reconnect to delete its staging files or base. **Coverage & runs**
reports cleanup that needs review. Reopening an audit never resumes commands,
reconnects SSH or performs cleanup.

## Open existing evidence

In **Hermeticity**, **Open audit…** opens a saved audit directory. Its operation
record links the two managed sessions and their preserved execution logs.
Recorded checksums are checked against the exact private snapshots being
compared; a changed file is refused. **Open run A** and **Open run B** inspect
the retained captures, including incomplete runs when available.

Alternatively, use **Choose A…**, **Choose B…**, then **Compare** to compare two
existing local execution-log files or managed session directories without running
Bazel. This accepts compact
zstd execution logs and length-delimited binary `SpawnExec` logs, not JSON logs.
Offline comparison does not establish that the sources, machine, configuration
or cache policy stayed unchanged. Imported commands are never executed.

The managed audit requires compact logs. With Bazel 9.2.0, each log's embedded
invocation ID must match the captured BES invocation. Bazel 7.4.x does not write
that ID, so the app instead binds each log to its controlled capture: separate
private output paths checked absent before execution, a successful completed
build and BES capture, successful preservation, and a recorded checksum. The
review and **Coverage & runs** disclose this weaker, capture-based association;
it is not an independently verified ID inside the log. Any embedded ID that is
present must still match. Binary logs remain available for offline comparison,
not the automated audit.

## Read the findings

Matching uses the target, mnemonic and semantic output set, not record IDs,
scheduling order or timing. A changed output set can be paired only through a
unique shared output in both directions. Repeated or ambiguous observations
are not guessed into pairs.

| Finding | Meaning |
|---|---|
| Output divergence | Output content changed despite equal, sufficiently recorded recipe and inputs, with successful independent executions. This identifies a difference, not its hidden cause. |
| Output changed · partial evidence | Output content changed, but missing or unsupported evidence prevents the equal-input conclusion above. |
| Recipe drift | The recorded command, environment, platform or execution policy changed. |
| Input drift | Recorded input content or tool membership changed. |
| Cache identity drift | Bazel's recorded action-cache digest changed despite equal recorded recipe, inputs and outputs. The reason is not established. |
| Downstream change | A changed generated input matches a unique changed producer output by path, kind and digest. Other differences may still be present. |
| Added in B / Absent in B | An observation has no unique counterpart in the other log. This does not by itself prove a source target was added or removed. |
| Inconclusive | Missing, ambiguous, unsupported, redacted, failed or cached evidence prevents verification. |
| No differences observed | The recorded evidence matched for this pair. It is not proof of hermeticity. |

The displayed finding is a summary; inspect its reason and field differences
for other changes. Use the shared filter builder to combine target, mnemonic,
output and finding filters, including string prefixes and regex. Action rows
and their field changes are paged separately with exact counts. Select a detail
cell to read and copy its displayed value. **Open BUILD file…** is available
when the selected target can be opened in the current Workspace.

Commands, environment values, platform values and action-cache identities are
compared privately but masked in the inspector. Secret fingerprints are not
exposed as a substitute for secret text. Raw captures still contain sensitive
data; treat them and the audit directory accordingly. Output digests describe
historic observations, not saved historic file contents: opening today's output
path does not recover A's bytes.

## Coverage and limits

Cache reuse can hide nondeterminism even after a clean. An absent digest,
platform or execution observation is unknown, not an empty value or evidence
of equality. Complex compact runfiles overlays are not reconstructed yet.
Binary tree-output flattening cannot establish empty-directory completeness.
Non-spawn work, unsupported fields and failed observations remain coverage gaps.
An empty pair is not a successful check.

The comparison uses bounded copies and a private, disposable SQLite index,
separate from `session.sqlite`, Query and exports. Default limits include 512 MiB
per raw log, 2 GiB decoded per log, 4 MiB per record and a 1 GiB main index.
Additional record, decompressor, query-work and result-size limits refuse work
explicitly. These are not an absolute quota for all SQLite internal temporary
files. See [limits](limits.md) and [performance](performance.md).

Audit captures defer the ordinary execution-log, profile, `aquery` and `cquery`
enrichment path. A separate bounded verifier validates the preserved execution
log before proceeding. This does **not** fix the documented auxiliary-import
hardening blocker for ordinary captures.

A and B's raw captures and operation record survive private-base cleanup and
are protected from ordinary session retention. Comparison indexes are rebuilt
when opened and removed on normal close. Crash-left comparison scratch is not
automatically reclaimed. There is not yet an audit deletion UI, compound audit
export, automatic resume or automated orphan cleanup. Keep adequate disk space
for two captures, source manifests and comparison scratch; see
[implementation status](implementation-status.md) for remaining work.
