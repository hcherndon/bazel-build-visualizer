# Session format

The on-disk shape of a managed session, owned exclusively by the
`session-format` module — no other module may construct paths inside a
session directory, with one exception noted below. Deleting a session is
deleting its directory (ADR-005); everything derived is rebuildable from the
raw journal (ADR-004).

The exception: the importer names `raw/imported-source.bep` and
`checkpoints/import-source.json` itself rather than going through
`ManagedSessionLayout`. Both are importer concerns rather than layout
concerns, but the claim above is "owned exclusively" and this is where it
does not hold today.

File import copies the source into `raw/imported-source.bep`, hashing the bytes
as they are written, and parses that immutable session-owned copy. The older
`REFERENCE_ORIGINAL` checkpoint value remains readable only so the application
can refuse it with a useful remedy. New and resumed imports do not use it: a
mutable file can change between hashing and parsing while retaining its size
and modification time, so it cannot meet the recorded-digest integrity claim.

## Managed session directory layout (plan 10.2)

This is the layout `ManagedSessionLayout` creates. It is the plan's section
10.2 layout verbatim. An earlier draft of this page described a different
shape (`raw/bep.journal`, `derived/session.db`, `derived/index/`,
`logs/capture.log`); that draft never existed in code and was corrected when
Phase 1 landed.

```
<sessions-root>/
  session-<uuid>/                # one directory per session
    manifest.json                # format version, SessionState, capture sources
                                 # and their completeness, counts, timestamps,
                                 # original + effective command (ADR-007), and
                                 # local/SSH execution provenance (ADR-011)
    session.sqlite               # per-session database (ADR-005)
    raw/                         # every source byte, verbatim (ADR-004)
      bes-000001.journal         # segmented raw journal, rotated at frame
      bes-000002.journal         # boundaries; see JournalFormat
      stdout.log / stderr.log    # console output (Phase 2)
      execution-log.bin          # execution log as received (Phase 4)
      profile.json               # timing profile as received (Phase 4)
      starlark-cpu.pprof.gz       # sampled Starlark CPU stacks as received
      aquery.proto / cquery.proto # query outputs as received (Phase 5)
      aquery.query / cquery.query # exact or explicitly fallback target expressions
      imported-source.bep        # the original file, for an imported session
    indexes/                     # rebuildable; see graph-model.md
      action-forward.csr         # forward action-dependency CSR (Phase 5)
      action-reverse.csr         # reverse CSR (Phase 5)
      timeline-lod.dat           # timeline level-of-detail index (Phase 6)
    exports/                     # user-requested exports (Phase 9)
    checkpoints/
      import.ckpt                # resumable journal position; atomic replace
      import-source.json         # resumable *source* byte offset. Separate
                                 # because a source offset is not derivable
                                 # from the journal for JSON, where the file
                                 # holds whitespace the records do not.
    locks/                       # in-use marker with stale-lock detection
```

Only directories relevant to a session are created — the plan says so
explicitly, and an imported BEP file has no need for `exports/`. Files marked
with a later phase are listed to show where they will live, not to imply they
are written today.

The current session schema is v10. Its partial unique index rejects duplicate
non-null `declared_actions.node_index` values; graph opening also streams the
ordered values and requires a dense `0..count-1` population before any
node-indexed allocation. Writable capture/import initialization migrates a valid
v9 database transactionally. Duplicate assigned indices reject that migration
and leave the database at v9; an already-finished v9 managed session is not
migrated merely because a user opens it and must be re-imported instead.

The catalog database is an application-level concern and lives under the
application-support root (`catalog/`), not inside any session directory.
Cleanup and archive adoption are coordinated above the catalog by session UUID.
An open session holds a process-level lease until its source closes, so cleanup
cannot remove files still being read by another native window. Archive imports
extract into unique sibling staging directories and serialize adoption of the
same UUID.

The manifest is small, human-readable JSON and is the only file read to list
sessions cheaply besides the catalog; catalog and manifest must agree, with
the manifest winning on conflict (the directory is the artifact). Writers use
the canonical lower-case UUID spelling. Ordinary local reads continue to
normalize aliases accepted by version 1 for compatibility; portable import and
export require canonical text because that identity becomes a mutation key and
directory name.

`executionLocation` is optional for compatibility with sessions written before
ADR-011. A new live capture records `kind` (`LOCAL` or `SSH`) and a display
name. SSH entries may also carry the OpenSSH destination and explicit port;
they never contain a password, key or authentication option. Remote
`workingDirectory` and `workspaceRoot` values remain Linux path text and are
never interpreted as desktop `Path` values.

Execution provenance is descriptive, not a reconnect recipe. Reading,
importing or opening a manifest cannot create an SSH session, run its recorded
command, open its terminal or fetch its files. A user must separately choose a
saved or new SSH Workspace for live remote access; capture preflight follows
only if they start another build. Reusable local/SSH Workspace profiles live in
`settings/workspaces.properties` and are not stored in the managed session or a
`.bviz` archive.

## Journal framing and integrity

The frame layout is defined once, in `core-model`
`com.holtherndon.bazelviz.core.journal.JournalFormat`, and shared by the
writer, the reader and crash recovery so it cannot drift between them.
`docs/phase1-contracts.md` explains the layout; `JournalFormatTest` pins it.

The properties that matter for recovery:

- Every frame carries its own CRC-32C over header **and** payload, so the last
  intact frame can be found without trusting any index.
- Payload bytes are stored exactly as received and are never re-serialized.
- A declared payload length above the configured maximum is treated as
  corruption rather than honoured, because honouring it means allocating
  whatever a damaged length field happens to say.
- Recovery truncates only the invalid trailing bytes of the **last** segment.
  Damage found in an earlier segment is reported and nothing is removed —
  bytes after it are not a trailing tail, and later segments may be full of
  valid frames.
- A fully present, CRC-valid frame whose source kind this build does not
  recognize is reported as unsupported, not corrupt, and is never truncated
  (plan 21.5 forward compatibility).


## Portable `.bviz` archives (plan 10.4, Phase 9)

A Zip64 archive with a `.bviz` extension, holding the same layout as the
directory it came from plus one file of its own.

```
archive.json                 # this archive's own table of contents
manifest.json                # the session manifest, at the root as the plan requires
session.sqlite               # the database, or a redacted copy of it
raw/…                        # the capture, absent from a redacted archive
indexes/…                    # rebuildable, carried so an opened archive works at once
checkpoints/…
```

A redacted export has an intentionally smaller shape: besides the generated
`archive.json`, its indexed payload is exactly a redacted `manifest.json` and
`session.sqlite`. The writer accepts exactly those two staged replacement names;
it does not copy `raw/`, indexes, checkpoints, or another source-tree file into
the redacted archive.

`locks/` is never exported: a lock is a statement about this machine's running
processes and means nothing anywhere else. `exports/` is not exported either —
those are derived artifacts the user already has, and carrying them would widen
the set of names an archive may contain for no benefit.

### What `archive.json` adds that the Zip does not

A Zip entry carries a CRC-32, which detects accidental corruption and nothing
else. The index carries a SHA-256 per entry, and the reader checks the bytes it
actually decompressed against it. It also records what no Zip structure can say:
whether the archive was redacted, and whether the raw capture is in it.

### Reading one is a refusal by default

An archive is untrusted input (plan 22.4). The reader validates end to end —
decompressing every entry and discarding the bytes — before extraction writes
anything, and runs the same checks again while writing, because between the two
calls the file is not under this application's control.

The current checks begin after Java has constructed `ZipFile`; they do not yet
preflight and bound the central-directory metadata before that parser opens the
archive. This is a release blocker for hostile archives, despite the enforced
entry, expanded-byte, ratio, checksum, and path checks below. Treat imported
archives as trusted until the retained preflight fix passes review and lands.

| Refused | Why |
|---|---|
| `../…`, `/etc/…`, a drive letter, a backslash | zip-slip, in every spelling |
| a name with anything but letters, digits, `.`, `-`, `_` | control characters that rewrite a terminal, right-to-left overrides, Unicode forms that normalise differently on macOS |
| a file that is not part of a session | including a `.dylib` — "never load native code from a session archive" enforced by never writing it |
| an entry `archive.json` does not list, or lists twice | nothing says which copy a reader would get |
| bytes that do not match their SHA-256 | and the partial file is deleted rather than left behind |
| more than the entry, size or expansion-ratio limits | counted from bytes the decompressor produced, never from the size an entry declares |
| an archive claiming to be both redacted and complete | the raw capture *is* the unredacted bytes |
| a session id that is not a canonical UUID | archive text cannot become a path component or acquire an alias spelling |
| an index that changes between validation and extraction | adoption remains under the UUID lock selected by the first validated index |
| a manifest whose session id differs from `archive.json` | two authoritative identities cannot safely name one adopted session |
| a format version this build does not know | refused rather than misread |

Extraction into a directory that already holds files is refused too, and an
archive whose session UUID is already in the library is refused with the path of
the copy already present.

### Writing one

Through a temporary file beside the target, then an atomic rename — and only
after the archive has been re-read and every checksum verified against what was
recorded while writing. The space needed is estimated first, from the exact
source size, which is an upper bound because compression can only help.

The writer snapshots the selected `manifest.json` (including a redacted
replacement), validates its canonical session id, and streams that same snapshot.
The archive identity therefore follows the manifest bytes actually included, not
the source directory name, so a valid renamed session remains exportable.

Entries are written in sorted order with a fixed timestamp, so exporting the
same session twice produces the same bytes.

**One deviation from plan 10.4, stated.** The plan says to store already
compressed files without recompressing. A literally `STORED` Zip entry needs its
size and CRC before the first byte is written, which means reading every large
file twice. Instead each file is sampled — 128 KB, deflated, measured — and one
that does not compress is written at `NO_COMPRESSION`: no compression CPU, one
pass, and about five bytes of framing per 64 KB block.
