# User guide

Bazel Build Visualizer captures what a Bazel build did and shows it. The app,
managed sessions and analysis stay on your desktop. A build can run there or on
an SSH host you explicitly select; captured data is not sent elsewhere unless
you start that connection or export a session. An optional Workspace Discovery
script is trusted local executable configuration and can perform whatever
local or network work its author wrote.

This guide is written for somebody who has a slow build and wants to know why.

---

## Choose an execution workspace

The app restores the Workspace windows that were open when it last closed. If
there is nothing to restore, it opens on **Workspaces**, with the most recently
opened entries first. A Workspace is one repository on one machine, not a
captured build. Give it a name and choose either:

- **This computer** — a local working directory and Bazel executable.
- **SSH host** — an OpenSSH destination or configured Host alias, optional
  port, absolute remote working directory and remote Bazel executable.

You can save several repositories on the same local or SSH machine. Choosing a
Workspace opens its own native window; choosing the same profile again focuses
the window that is already open. A local window validates its directory.
Opening an SSH window is the explicit action that creates its private OpenSSH
connection. The app uses your existing host keys, agent and configuration; it
does not ask for or store a password or private key.

Use the **Workspaces** menu later to recall the Workspace manager, create or
edit a saved one, or reconnect and close the current window. The manager hides
while Workspace windows are open and returns when the last one closes. Each
window's repository, editors, builds, captured-session selection and Terminal
use only that window's execution context.

Up to eight restorable Workspace-window slots can be tracked. An unavailable
previously discovered Workspace keeps its slot until you press **Forget** in
the manager, so the app never silently drops an older restore entry. Different
canonical repositories can capture at the same time. If two profiles resolve
to the same repository, the second capture is refused and identifies the
window that owns the active capture; Bazel's own repository lock remains the
final authority.

Window restoration reconnects saved SSH Workspaces because leaving the window
open is persisted application state. It restores the Workspace, position and
size and starts on Console. It does not reopen a historical session, start the
Terminal, repopulate the command draft, or rerun a command. A saved Workspace's
command history and table layout remain available. A discovered Workspace
persists only its Bazel executable override and bounded command history, keyed
by its deterministic identity; its profile, connection details, label, draft,
table layout, and other window settings remain ephemeral. Those two launch
preferences become reachable only when discovery emits that same profile again,
and never restore a connection or run a command by themselves.
When upgrading from the former single-window app, its command history is moved
once into the saved Workspace whose repository and execution host match; it is
never copied to an ambiguous SSH profile.

A Workspace and a captured session are independent. You do not choose a
session before opening a Workspace, and you may inspect or import historical
data without selecting a live Workspace. Opening a recorded SSH session never
contacts that host or selects a matching Workspace for you.

### Discover Workspaces with a script

Open **Settings › Preferences…**, then choose the **Discovery** tab, to save
one optional discovery script. It runs locally, not in a selected Workspace:
once during graphical startup and whenever you press **Run Discovery Now**.
That button saves the current editor text before starting the run. The work
happens away from the Swing event thread.

A non-empty script must begin with a shebang such as `#!/bin/sh` or
`#!/usr/bin/env python3`. The app invokes the script file directly, so the
operating system follows that shebang; it does not wrap the text in another
shell. The editor uses the same line to choose syntax highlighting. Treat the
script as executable code with your account's local permissions, and save only
code you trust.

Print one Workspace per non-comment stdout line in exactly one of these forms:

```
local|name|working-directory
ssh|name|OpenSSH-destination-or-Host-alias|working-directory
```

New discovered profiles use `bazel` as their executable. If discovery later
emits the same identity, the Console restores any Bazel override saved for it.
For SSH, put a custom port, jump host, identity file, and other connection
options in `~/.ssh/config`; they are not extra output fields.

Every invocation replaces all results from the previous invocation. Accepted
rows appear on the Workspaces screen and in **Workspaces › Available
Workspaces** with a **Discovered** tag. They are available for this app process
only: they are never written to `workspaces.properties` and have no Edit or
Remove action. Edit the script and run discovery again to change them. Invalid
rows and execution problems produce bounded diagnostics instead of silently
changing the protocol or keeping stale results.

---

## Getting a session

There are three ways in, and they all end at the same place: a **session** — a
directory holding everything captured, indexed for querying.

### Run a build through the tool

Choose a Workspace, open **Console**, enter the Bazel command you would have
typed, and press **Run**. The launcher shows the selected machine and directory.
The left-aligned launch row places editable **Bazel Executable** immediately
before **Capture detail**. Bazel Executable defaults to `bazel`; type another
command available on that machine's PATH or an executable path when the
Workspace needs one. There is no file-selector button. The typed value is saved
to a saved Workspace profile or to the limited launch-preference sidecar for a
discovered Workspace.

Capture detail has no separate summary text. Hover an option in its dropdown to
see the complete capture scope and cost explanation immediately. The selected
option keeps that explanation as the control's accessible description.

Clicking or focusing the command field opens its Workspace's recent commands
below it, newest first.
Five commands are visible before the list scrolls, and long entries keep their
full text in a tooltip. Up and Down select without replacing what you typed;
Tab fills the selection and Enter runs it immediately. Typing normally clears
the selection, while Ctrl+Space opens Bazel subcommand completion.

Before anything starts you are shown the **instrumentation plan**: your original
command, the effective command the tool will actually run, every flag it added,
and why. Nothing is added silently. If a flag you set conflicts with one the tool
needs, the conflict is shown and you choose.

The tool adds flags to make the build *observable* — an event stream, an
execution log, a timing profile. It does not change what the build does.

For an SSH Workspace, capture borrows the connection that Workspace already
owns. Preflight adds a capture-scoped reverse loopback tunnel from the remote
host to the desktop BES, validates the Linux Workspace and Bazel, and creates a
private remote capture directory. The review identifies both tunnel endpoints
and states that the forced-TTY Bazel command has merged stdout/stderr. Nothing
runs if the reverse forward, SFTP subsystem or required remote tool is
unavailable. Closing the capture does not close the selected Workspace.

### Import a file Bazel already wrote

**File ▸ Open BEP File…** takes a `--build_event_binary_file` or
`--build_event_json_file` output. The format is detected by reading the file,
not by its name.

### Open a session somebody sent you

**File ▸ Open Portable Archive…** takes a `.bviz` file. It is validated
completely before a byte is written to disk — see [Sharing](#sharing) for what
an archive does and does not contain.

**File ▸ Open Recent** lists sessions already on this machine. Each one can be
pinned, which protects it from cleanup, or removed from the list without
deleting it. **File ▸ Clean Up Sessions…** also keeps any session open in any
application window, even if that window opened it after the cleanup preview was
shown; the result names sessions kept for that reason.

---

## Appearance

Open **Settings › Preferences…** and choose the **Theme** tab to switch the
whole application between Light, Dark, IntelliJ Light, Darcula, macOS Light,
and macOS Dark. Open file editors, syntax views, graphs, timelines, and the
selected Workspace's live Terminal update with the rest of the window;
changing terminal colours does not restart its shell.

The choice is saved automatically and applied before the first window appears
on the next launch. A command-line `-Dbbv.theme=<id>` setting overrides it for
that process only. The accepted IDs are `light`, `dark`, `intellij-light`,
`darcula`, `macos-light`, and `macos-dark`. Missing or damaged appearance
settings fall back to Light.

---

## Diagnostics and logging

The graphical app records normal lifecycle and operation summaries in
`logs/application.log` below its application-support directory. Choose
**Diagnostics › Log Detail** at any time:

- **Error** records operations that could not complete.
- **Warn** adds recoverable problems that may need attention.
- **Info** adds normal lifecycle and operation summaries. This is the
  recommended default.
- **Debug** adds decisions and stage-level progress useful for troubleshooting.
- **Trace** adds the most detailed bounded progress. Use it briefly when Debug
  is not enough because it grows logs fastest.

The choice takes effect immediately and is saved for the next launch. A
`-Dbbv.log.level=<error|warn|info|debug|trace>` property overrides it for one
process without replacing the saved choice.

Choose **Open Application Log** for a modeless, read-only text viewer. Its
**Reload** action reads records written since it opened. **Reveal Application
Log** selects the file in the system file browser. The Diagnostics menu also
shows the exact number of records a saturated logging queue could not retain;
the log writes a warning when that happens. Logs can contain workspace paths,
labels, SSH destinations, session identifiers and failure messages, so treat
them as sensitive build data.

Headless commands do not create this file. They keep logs on stderr, default to
Warn, and leave stdout available for command output and JSON.

---

## Reading a build

The views on the left all describe the same session. Selecting an action in one
usually reveals it in the others.

Press Ctrl+Tab to move down one visible page in the current window's left
navigation, or Ctrl+Shift+Tab to move up. The selection wraps at the bottom or
top. These shortcuts stay with the current Workspace window and work while a
text field or Terminal has focus; the Terminal does not receive those chords.

### Overview

What the build did, as counts and cards. Every card navigates to the detailed
view behind it.

Two numbers on this screen are worth understanding before you use any other:

- **Actions executed** is not "actions in the build". Bazel publishes an event
  for a successful action only under `--build_event_publish_all_actions`; a
  cache hit publishes nothing. The card says which kind of capture this was.
- **Bazel's own counters** are shown separately from this session's counts, and
  never mixed. They legitimately disagree — `actionsExecuted` excludes cache
  hits, and on some versions `actionsCreated` is *smaller* than
  `actionsExecuted`. Any ratio built from them describes nothing, so none is
  shown.

### Timeline

Actions and attempts share one clock and are grouped into lanes. Use the wheel
or a two-finger vertical trackpad gesture to scroll through the lanes. A
two-finger horizontal gesture pans left or right in time; Shift + wheel offers
the same fallback. Hold Control or Command while scrolling to zoom around the
pointer. A native pinch also zooms on macOS when the application is started
through the Bazel launcher or packaged app; a manually launched jar needs the
package export documented in the README. The **−**, **+**, and **Fit build**
buttons offer the same controls without a gesture.

Primary-button drag pans in time. Shift + primary-button drag selects a range,
and the Actions table narrows to spans that overlap it. A short click still
selects the span under the pointer and shows its details in the right pane.
Right-click a segment for the same cross-view actions available elsewhere.
Panning or zooming stops **Follow live**; vertical lane scrolling does not.

At a wide zoom the timeline draws exact aggregate density rather than
individual spans. Its lane label changes to say that this is a whole-build
view, because the columns are not per-lane. Zoom in and individual spans
return. While a nearby exact window loads, the still-overlapping known spans
remain in their lanes and only the uncovered time edge is shaded as loading.
With no exact overlap, aggregate density remains visible and the status line
says it is updating. Rapid navigation keeps only the newest waiting viewport
request, and a late detail read cannot replace a newer selection.

Very short work is drawn as a narrow needle so it remains visible. The status
line states how many needles are present, the hover readout keeps the exact
duration, and zooming in restores proportional width. The hit target is wider
than the needle, so a tiny event remains selectable without changing what its
drawn width claims. Hovering a span shows its adaptive duration and identity;
the time grid and axis also change precision with zoom so close views do not
repeat rounded labels.

**Colour means the selected mode, and grey means unknown.** Outcome shows
success and failure. If an execution log is available, Cache result and Where
it ran distinguish hits from misses or local from remote work. Failures also
receive a second top edge, so they do not depend on colour alone. Grey is never
used to claim zero.

### Critical Path

This page keeps two different answers side by side. **Bazel-reported critical
path** is the ordered progress text Bazel wrote into the trace profile.
**Visualizer-computed dependency critical path** is the longest weighted chain
through the imported action graph. Bazel's answer includes the schedule it
observed; the dependency chain is only a lower bound on what dependencies
required. Their difference is a useful lead, not proof that a particular
scheduler or machine caused the wait.

The compact summary scrolls on small screens. Its cards show both totals, their
signed difference when the dependency graph is fully timed, and observed idle
time inside the timed-action window. The difference is withheld when node
durations are missing because that gap would mix unknown work with scheduler
delay. Unknown values remain unknown. **Show details** expands the timing
source, graph trust, coverage, and full enrichment failure output without
letting a multiline Bazel diagnostic stretch the page.

The first two tabs preserve each path's complete reported order. Both path
tables load 200 rows at a time and retain eight recently visited pages, so a
long path does not become an in-memory object list. Select a Bazel component to
inspect its description and duration. Select a dependency step to
see its target, mnemonic, output, path weight, earliest start and finish, slack,
and any matched execution-log signals such as queue, execution, network, and
cache state. The path weight and execution detail stay explicitly separate:
when several subprocess attempts raced or retried, the conservative path weight
uses the shortest attempt while the execution section reports total work across
all attempts. Right-click an identified step for the normal target/action
commands, or double-click it to reveal the executed action. **Open dependency
chain in Graph** opens the same derived chain in the graph view; if Graph is
still loading, the request waits for that reader instead of drawing against the
wrong source.

When the action graph is unavailable but the BEP has usable action timestamps,
an enabled **Observed timing fallback** tab shows the single longest observed
action span and its timing coverage. This is concrete lower-bound evidence, not
an estimated dependency chain: no predecessor, path total, slack, or comparison
with Bazel's path is inferred. Future captures avoid a common aquery failure by
replaying only top-level labels that reached a BUILT or FAILED completion;
configured-only incompatible wildcard matches are not made explicit targets.
A complete successful invocation makes that completed-label scope exact. A
failed or unknown invocation that omitted configured or aborted labels remains
unverified and cannot support a complete dependency-path claim.

The page always shows each path's exact length and never drops unmatched
declared actions. A failed page replaces the loading inspector with its error
instead of leaving details from a previous selection on screen. It preloads the richer execution breakdown only
for the largest matched contributors; use **Reveal action** for full details on
another executed step. Both limits and the exact reason for an unavailable path
are stated on screen.

### Starlark Profile

This page answers which Starlark functions, source files, and call contexts
used CPU during rule and macro execution. It is available when the build used
the Performance or Full capture preset and Bazel produced a valid
`--starlark_cpu_profile` file.

Summary explains the sampled CPU total, profile duration, sampling period, and
coverage in a responsive, vertically scrolling view. Hot Functions and Files
are searchable, sortable, and fully paged. Selecting a function lets Call Graph
load its aggregate callers and callees. Hovering a graph node immediately shows
its function and CPU totals; click or right-click it for source details. Flame
draws root-to-leaf call contexts and uses the same immediate, compact hover
details without repeating the source path and line. Double-click a context to
focus it and use **Reset to all roots** to return. If the drawing limit is
reached, the page states the exact omitted count while the complete data remains
on the Query page. Double-click or right-click a source-bearing row to open it
through the current local or SSH Workspace.

Read these as statistical CPU samples, not elapsed build time. Several
Starlark threads can make CPU exceed wall duration, blocked time is absent,
and source lines are navigation hints rather than a line heat map. See
`docs/starlark-profiling.md` for the exact data and query contract.

### Actions

Every executed action, paged and sortable. Filter by mnemonic, by target, or by
a time range dragged out on the timeline. Selecting a row fills the inspector:
timings, inputs, outputs, attempts, the command line, and where in the raw
capture the row came from.

The command line is shown one argument per numbered line, and secrets in it are
masked.

### Top Level Targets and All Targets

**Top Level Targets** shows the labels named by the build event stream, either
grouped under packages or as one flat list. **All Targets** uses the captured
cquery result to include the transitive configured-target closure and expands
configuration hashes only when requested.

Both pages have a live full-label filter. It searches the complete stored
population, not only loaded rows, while retaining bounded paging and exact
matching counts. Rapid edits replace an older waiting search, while a search
already reading SQLite may finish in the background and is prevented from
replacing newer results. Clearing the filter restores the ordinary view;
revealing a target from another page clears a filter that would hide it.

### Query

**Query** runs SQL against the open session when a built-in view does not
answer your question. The schema tree lists the session's tables, views, and
columns; double-click a table to start a query. Press Ctrl-Enter (Command-Enter
on macOS) or **Run**. **Format** changes only the editor text.

The session database is opened read-only. The editor accepts one `SELECT`,
`WITH`, or `VALUES` statement, read-only introspection `PRAGMA`s, and
`EXPLAIN` of those statements. Its one non-read form is
`CREATE TEMP VIEW <name> AS <read-only query>`: that view exists only on the
current tab's connection and never changes the session file. Other writes,
multiple statements, attachments, and unsafe pragmas are refused.

Each tab owns its connection and can run alongside the others; at most 16 tabs
may be open. Saved queries and saved views are `.sql` files below the
application settings directory. Saved views are replayed onto every tab and a
broken definition is reported and skipped.

Results are paged from SQLite rather than loaded at once. The default visible
row cap is 1,000,000 and the maximum selectable cap is 20,000,000. Reaching a
cap is stated with the exact matching count; raise it or narrow the SQL. That
exact `COUNT(*)` can itself be expensive, and every execution has a 60-second
deadline plus an immediate **Cancel** action. A result's order comes from its
SQL, so add `ORDER BY`; the grid does not sort only the pages already fetched.
`NULL` and BLOB values are labeled explicitly.

On a live capture the displayed exact count is a snapshot and therefore only a
lower bound on the eventual rows, but 0.1.0 does not label it as such. Re-run
the query after capture finishes when completeness matters.

### Graph and Tree

The dependency graph, derived from which action produced the file another action
consumed — two cards, one graph. **Tree** browses dependencies and reverse
dependencies one level at a time and finds paths between nodes; it works at any
graph size. **Graph** draws bounded pieces of it on a canvas.

A graph too large to draw in detail **groups itself** by package, target or
mnemonic rather than showing you a blank canvas or a hairball. The exact totals
are always on screen, and you can raise the limit, narrow the query, or export
the whole thing. Nothing is silently sampled.

The Graph card's **weight selector** decides what node size, edge thickness and
colour mean: duration, immediate or transitive dependency counts, output size,
or inputs. Transitive counts are exact for what is drawn; the whole-graph count
for a selected node is budgeted, and shows "≥N (budget reached)" when the
search gave up rather than a number pretending to be a total. A node with no
recorded value is grey at base size — unknown is never shown as zero.

### Findings

Evidence-backed candidates, worst first: a long dependency chain, a stretch of
low parallelism, a group of actions that keep missing the cache, an action
everything waits for.

**A finding is a candidate, not a diagnosis.** Every one names the threshold it
used, the numbers it rests on, the records it came from, and the reasons it might
be wrong. None of them says a change will make your build faster, because one
build on one machine cannot support that claim.

Above the findings is the **coverage** banner. Read it first. A cache-miss
finding over a build whose cache state was 12% covered is a different claim from
the same finding at 99%, and the banner is what tells them apart.

### Tests, Errors, Events, Console

- **Tests** — one row per test target, with attempts, and the distinction
  between a test that failed and a test that never built.
- **Errors** — failed actions, failed targets, targets never built, and what
  Bazel printed to stderr. The first three are three separate counts and are
  never summed: one broken target in a large workspace would otherwise look
  like a catastrophe. The stderr rows are why the card is called Errors rather
  than Failures — for most broken builds the compiler's own text is the only
  diagnostic there is, and not all of it describes a failure.
- **Events** — the raw event stream, with the original bytes of any event.
- **Console** — one pane for the running build: the capture's phase, counters and
  stop buttons across the top, and what Bazel printed below them.

### Browse Repository and Terminal

**Browse Repository** is available for a local workspace and for the currently
connected SSH workspace. It lists a directory only when you expand it. Double-
click a regular file to open the shared language-aware viewer/editor; Save is
explicit and refuses to replace a file that changed since it was opened. Binary
files and text files above 16 MiB are refused rather than shown partially.
Rows use bundled SVG icons for directories and common source, data and document
types. BUILD, WORKSPACE, MODULE, `.bzl`, `.bazel`, and `.bazelrc` names use the
green BZL document icon; an unknown file uses the text-document icon. The
mapping is local and fixed: browsing never downloads an icon or treats a
repository file as icon artwork.

At the repository root, Bazel's `bazel-out`, `bazel-bin`, `bazel-testlogs`,
legacy `bazel-genfiles`, and `bazel-<workspace-directory>` symbolic links use
the green Bazel-folder icon. Expand one to resolve and list its target lazily.
Ordinary links and lookalike names remain leaves; the browser never walks links
automatically. A Bazel output link can resolve outside the workspace root,
which is how Bazel normally exposes its output tree.

**Terminal** is available for every selected Workspace. Navigating to the tab
starts its shell automatically; repeated visits do not create another shell.
A local Workspace uses your inherited login shell (or `/bin/sh` when none is
configured) in its working directory. An SSH Workspace uses a login shell on
the selected host. Both support ordinary keyboard input, colours, selection
and copy/paste, scrolling, resize, and full-screen programs such as editors and
pagers. The Terminal stays alive while you inspect other tabs. Disconnect
closes only that shell; it does not close the Workspace.

These tools belong to the live execution connection, not the data session on
screen. Reopening a session recorded as remote never reconnects, opens a shell
or executes its saved command. Choose an SSH Workspace explicitly when live
file access is wanted; capture preflight is needed only when starting a build.

---

## When something is missing

The tool tries never to show you a number it does not have.

- **A value nothing reported reads "not reported"**, never 0 and never a blank
  cell.
- **A total that could not be completed says so** — "4.2 GiB known, 137 inputs
  of unknown size" rather than "4.2 GiB".
- **A source that did not run is named.** If the execution log was not captured,
  the views that depend on it say which enrichment is missing rather than
  showing an empty chart.

The clearest example: most actions run inside the Bazel server and never start a
subprocess, so most actions have no execution-log record and never will. Every
place that shows a rate over those records says what it was taken over.

---

## Sharing

**File ▸ Export** produces four kinds of thing.

### Redacted session archive

A `.bviz` archive of the whole session with pattern-matched likely secrets and
absolute paths replaced. You are shown **exactly what the selected rules
matched before anything is written** — counts by rule and by field — and can
cancel. Pattern matching is best effort, and 0.1.0 has no UI for adding custom
patterns even though the policy API accepts them. Review the result before
sharing it; the report is not proof that every secret was found.

A redacted archive **does not contain the raw capture**, because the raw capture
is the unredacted bytes. That has a consequence worth knowing: the person you
send it to cannot re-run the enrichments or rebuild the database from source
bytes. The archive says so when they open it.

Redaction replaces each distinct matched value with a stable pseudonym rather than a
mask, so the recipient can still see that ten thousand actions used *the same*
value without seeing it.

Two further options, both off by default because each costs something:

- **Omit every environment value**, keeping only the names. Stronger than the
  pattern rules and the right answer if your environment holds something the
  patterns will not recognise.
- **Replace target labels with pseudonyms.** Hides your project's internal
  structure and makes the export hard to read.

### Complete session archive

Everything, raw capture included. **This is as sensitive as the machine it was
taken on** — every command line, every environment value, every absolute path,
exactly as captured. The tool asks before writing one.

### Binary BEP file

The captured stream written back out in Bazel's own format, so any other tool
that reads `--build_event_binary_file` output can read it. For a live capture
these are Bazel's own bytes, unchanged.

### CSV and JSON tables

Actions, targets, tests, attempts or artifacts, redacted, streamed straight to
disk. Large exports do not load the session into memory.

---

## Housekeeping

**File ▸ Clean Up Sessions…** removes old sessions. You choose the limits, see
the exact list of what would go and how much space it frees, and only then
confirm. Pinned sessions are never removed.

Sessions live in your application-support directory. Moving that directory is
safe: the tool matches sessions by identity, not by path, and reconciles the
library on the next launch.

---

## Current boundaries

Stated so you do not go looking:

- **No comparison between builds.** One session at a time.
- **Preferences currently has Theme and Discovery tabs.** The limits in
  `docs/limits.md` are not editable there. Workspaces and launcher command
  history remain direct controls.
- **No implicit reconnect or historical remote storage.** SSH access exists
  only for a live connection the user started; no team server is provided.
- **The 0.1.0 packaged desktop target is Apple Silicon macOS; SSH execution
  hosts are Linux.** Intel macOS packaging is currently unavailable, and other
  desktop/remote combinations have not been tested.
- **Terminal is tied to the selected live Workspace.** It is not reopened from
  historical session data, and its scrollback keeps the newest 20,000 lines
  rather than growing without bound.
- **The timeline does not draw the critical path.** Use Critical Path to
  analyse both path definitions, then open the dependency chain in Graph.

---

## If something goes wrong

See [troubleshooting.md](troubleshooting.md). The short version: a session
directory is self-contained and the raw capture inside it is never modified, so
almost every problem is recoverable by re-importing the session's own bytes.
