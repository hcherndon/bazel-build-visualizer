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
                                 # original + effective command (ADR-007)
    session.sqlite               # per-session database (ADR-005)
    raw/                         # every source byte, verbatim (ADR-004)
      bes-000001.journal         # segmented raw journal, rotated at frame
      bes-000002.journal         # boundaries; see JournalFormat
      stdout.log / stderr.log    # console output (Phase 2)
      execution-log.bin          # execution log as received (Phase 4)
      profile.json               # timing profile as received (Phase 4)
      aquery.pb / cquery.pb      # query outputs as received (Phase 5)
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

The catalog database is an application-level concern and lives under the
application-support root (`catalog/`), not inside any session directory.

The manifest is small, human-readable JSON and is the only file read to list
sessions cheaply besides the catalog; catalog and manifest must agree, with
the manifest winning on conflict (the directory is the artifact).

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
