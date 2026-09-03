# ADR-012: Multiple workspace windows

Status: accepted (2026-09-01; amended 2026-09-03 for discovered Workspace launch preferences)

## Context

ADR-011 made a Workspace the owner of one local or SSH execution context, but
the first implementation kept that context inside the application's only main
window. Opening another repository therefore replaced the first repository's
terminal, browser and connection. It also made process-global settings and
desktop handlers depend on whichever workspace happened to be selected in that
window.

Several repositories must be usable at the same time without mixing their
processes, files, captured sessions or SSH resources. Bazel also serializes
commands against a repository/output-base lock, so two UI windows must not make
same-repository concurrency look safe when it is not.

## Decision

### One Workspace owns one native window

The graphical process has an application controller and an ordered registry of
open Workspace windows. Each open window owns exactly one `WorkspaceProfile`,
execution connection, repository browser, terminal, capture controller,
captured-session selection, file editors, workers and shutdown sequence.
Choosing another saved or discovered Workspace creates another ordinary native
window. Choosing a profile whose stable ID is already open focuses its existing
window instead of creating a duplicate.

The Workspace manager is process-global. It is the first window when there is
nothing to restore, hides while at least one Workspace window is open, can be
recalled from the Workspaces menu, and returns when the last Workspace window
closes. Saved profiles, ephemeral discovery results, theme, preferences and
logging have one process-level owner and one serialized persistence path.
Captured-session catalog and query-library operations are process-coordinated
across all windows. A Workspace window asks the manager to create, edit, remove
or open a profile; it does not write a stale private snapshot of the global
list.

Destructive captured-session operations have a second, directory-level
coordination rule. Opening a session acquires a process-level lease for its
session UUID before the database is opened, and releases it only when the
session source has closed. Applying a confirmed retention plan takes the same
per-session mutation lock and rechecks active leases, pin state and catalog
location immediately before deletion. A session opened in another window,
including one opened after the plan was shown, is kept and named in the sweep
result. Portable archive extraction and adoption take the same UUID lock;
staging directories are unique as defence in depth, so concurrent imports can
neither share nor delete each other's partial extraction.

The limit on restorable Workspace-window slots is explicit and documented in
`docs/limits.md`. An unavailable discovered restore still owns its slot until
the user forgets it; this keeps the saved list exact instead of silently
discarding an older entry. Reaching the limit refuses another window and tells
the user to close one or forget unavailable entries.

### Window restoration restores execution context, not build history

The process atomically replaces `settings/workspace-window-state.properties` with
the ordered stable Workspace IDs that were open, plus bounded screen bounds and
maximized state. On the next graphical launch, saved profiles are restored in
that order. An SSH profile reconnects because the user previously chose to
leave that Workspace open and the saved profile already authorizes that
destination under ADR-011.

A discovered profile is never persisted as a profile. Its deterministic ID may
appear in the window-state file, but it is restored only if startup discovery
produces that same ID again. An unmatched discovered ID remains visible in the
Workspace manager as an unavailable previous window and can be forgotten; it
does not synthesize connection details or fall back to a similarly named saved
profile.

Restoration applies the window's Workspace and placement, then starts on
Console. It does not reopen a historical captured session, repopulate or run a
command, start the terminal, or execute data from an imported session. Saved
Workspace command history remains available for deliberate reuse. `File >
Open` and desktop open-file events target the currently focused Workspace
window. If the Workspace manager owns focus, its workspace-less analysis shell
loads the session without selecting or connecting a repository.

Saved profiles keep launcher history and table/query-result presentation state
under a directory derived from their stable ID. A discovered profile persists
only its bounded Bazel command history and user-selected Bazel command/path in
a separate launch-preference store keyed by its deterministic ID. That store
contains no label, machine, working directory, command draft, or other data
that could recreate the profile; discovery must emit the profile again before
its preferences can be reached. All other discovered-Workspace state remains
process-local. Global query-library and captured-session catalog access is
process-coordinated so native windows cannot race their shared files.

### Captures use process-global repository leases

Different canonical repositories may capture concurrently, including multiple
repositories on the same SSH host. Before preflight starts, a Workspace window
acquires a process-global lease for its canonical repository identity. A local
identity is the real canonical repository path. An SSH identity is the selected
SSH destination/control-session identity plus the canonical remote repository
path. Display labels and uncanonicalized input paths are never lock keys.

A second capture for the same identity is refused with the owner Workspace's
name and window. The lease is idempotently released after every terminal
outcome: successful capture, preflight failure, cancellation, failed launch, or
window shutdown. This application lease complements rather than replaces
Bazel's own workspace and output-base locks.

Analysis, repository browsing, editing and terminals do not take the capture
lease. They remain isolated by their owning execution context and can run while
another Workspace captures.

### Closing is asynchronous and ordered

Closing an idle Workspace window begins a single idempotent asynchronous
shutdown. Closing one with a capture or launch in progress asks whether to
cancel that work and close or keep the window open. Unsaved editor content must
still receive its existing discard confirmation.

After close is accepted, the window stops accepting new work, cancels and
awaits capture finalization, releases its capture lease, closes repository and
session readers, closes and boundedly reaps the terminal, closes file editors,
then closes the local or SSH execution context and its workers. The registry
removes and persists the window only after that sequence completes. Process
quit requests close all Workspace windows, wait for their completion, close the
Workspace manager and global settings/discovery/logging resources once, and
then allow the desktop environment to quit.

No part of this sequence performs blocking I/O on the Swing event thread.
The shared import/archive/export/catalog lane gets a ten-second cooperative
grace and a two-second interrupt/reap grace. An underlying API that ignores
thread interruption can outlive that bound on its daemon thread; shutdown logs
that incomplete teardown and continues instead of freezing the UI or quit
request.

## Consequences

- A Workspace is no longer a mutable selection inside one main window. Code
  that needs a Workspace receives a window-scoped execution context.
- Native window switching supplies the simultaneous-workspace UX; there is no
  additional tab layer whose tabs could be confused with analysis views.
- Process-global desktop About, Preferences, Open File and Quit handlers route
  through the application controller rather than retaining one `MainWindow`.
- Automatic SSH restoration is documented as local application state. It uses
  the user's existing OpenSSH configuration and never stores credentials.
- Same-repository capture conflicts are deterministic inside the app, while
  different repositories can use separate workers and BES endpoints in
  parallel.
- Retention plans remain exact previews, but execution may safely keep a
  candidate whose newer active, pinned or relocated state now wins. Concurrent
  archive imports of one session identity serialize at adoption.
- Window placement is convenience state only. Invalid or off-screen bounds are
  ignored or clamped rather than preventing a Workspace from opening.
- Rediscovering the same machine and repository can recover its command history
  and Bazel command/path without turning discovery output into saved Workspace
  configuration. Changing the execution kind, SSH destination, or working
  directory produces a different deterministic ID and therefore different
  launch preferences.
