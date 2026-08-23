# Security and privacy review

Plan section 22, clause by clause, with the evidence for each and the gaps
stated. Written for the Phase 10 release gate; every claim below names the code
or the test that makes it true, so a reviewer can check rather than believe.

The threat model this is written against: a local single-user desktop tool that
launches a build on the user's own machine, and that **opens files other people
sent** — a `.bviz` archive, a BEP file, a session directory. The second half is
where the interesting exposure is. Nothing here defends against an attacker who
already runs code as the user.

---

## 22.1 Local-only networking

| Requirement | Status | Evidence |
|---|---|---|
| Bind BES to loopback | Met | `BesEndpoint` refuses any host that is not loopback, in its constructor — before a socket exists. `BesEndpoint.LOOPBACK` is `127.0.0.1`. |
| Reject non-loopback configuration in v1 | Met, with no developer switch | The plan permits a developer-only override; none was written. There is no code path that constructs a non-loopback endpoint, so there is nothing to leave enabled by accident. |
| Do not expose an HTTP server | Met | No `HttpServer`, no servlet, no embedded web anything. The only listening socket in the application is the gRPC BES endpoint. |
| Do not send telemetry by default | Met, and not by default either | There is no outbound network code at all: no `HttpClient`, no `URL.openConnection`, no client `Socket`. The application cannot phone home because it has nothing to phone with. |

**Verified by grep during this review**, not by memory: `grep -rn 'HttpServer\|HttpClient\|openConnection\|Socket('` over every module's main sources returns nothing outside the BES server's own `ServerSocket`.

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

- **There is no settings screen**, so the user-editable pattern list of plan
  22.2 is editable by a caller and not by a user. `RedactionPolicy.withUserPatterns`
  exists and nothing in the UI calls it.
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
| **Do not execute commands embedded in imported sessions** | Met | Checked by grep during this review: `manifest.originalCommand()` is read in exactly one place outside `session-format`, by `CatalogEntries`, and only to build a display name for the library list. Nothing constructs a `BazelCommand` from a manifest. |
| Imported session commands are display-only | Met | As above. The launcher's command comes from the launcher bar, which the user typed. |

This is the clause most worth re-checking whenever the session library grows a
feature: an "open recent and re-run" button would violate it, and it is exactly
the kind of convenience that looks obviously good.

---

## 22.4 Archive and parser safety

Every clause here is enforced by `BvizReader` and `BvizPaths`, and each has a
test in `BvizArchiveTest` that builds a hostile archive by hand.

| Requirement | Status | How |
|---|---|---|
| Prevent zip-slip | Met, twice | An allow-list first — a session archive holds a known set of files, so anything else is refused before any path resolution — and a canonicalised containment check second. |
| Limit expanded archive size | Met | Counted from bytes the decompressor produced, never from the size the entry declares. |
| Limit entry count | Met | `BvizLimits.maxEntries`, 50,000. |
| Reject duplicate manifest entries | Met | A path listed twice in the index, or present twice in the Zip, is refused: nothing says which copy a reader would get. |
| Validate checksums | Met | SHA-256 per entry in `archive.json`, checked against the bytes actually decompressed. A Zip CRC-32 detects accidents and nothing else. |
| Treat imported SQLite as untrusted | **Partially met — see below** | The schema version is validated on open and a mismatch is refused with the remedy. |
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

### The one partial: "prefer rebuilding from raw files"

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
where the bad database arrives inside a well-formed archive.

### Parser limits

Every parser that reads somebody else's bytes is bounded, and the bounds are in
`docs/limits.md`: BEP message size, JSON record size, JSON nesting depth,
manifest size, journal payload size, and the archive limits above. Each refuses
rather than truncating, and each records the refusal where the user can see it.

---

## What this review did not cover

- **No fuzzing was run.** Plan 23.7 asks for it and it has not been done. The
  parsers have hand-built damage tests (`BepDamage`, `CliDamagedSourceTest`,
  `BvizArchiveTest`) which cover the failure modes somebody thought of, and that
  is a weaker guarantee than a fuzzer's.
- **No dependency vulnerability scan.** Dependencies are locked (ADR-003) and
  were license- and maintenance-reviewed when introduced, but nothing checks
  them against an advisory database on each build.
- **No review of SQLite's own parser.** An imported database is handed to
  sqlite-jdbc, which is C code reading an attacker-influenced file. The
  mitigation is the schema validation above plus the fact that the file arrives
  in an archive whose every other entry was checksum-verified; it is not a
  claim that SQLite is safe against a malicious database file.

The third of those is the largest residual risk in this application, and it is
inherent to opening somebody else's session at all.
