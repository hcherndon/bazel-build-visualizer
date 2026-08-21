# Session format

The on-disk shape of a managed session, owned exclusively by the
`session-format` module — no other module may construct paths inside a
session directory. Deleting a session is deleting its directory (ADR-005);
everything below `derived/` is rebuildable from the journal (ADR-004).

## Managed session directory layout (plan 10.2)

```
<sessions-root>/
  catalog.db                     # app-level catalog database (ADR-005)
  <session-id>/                  # SessionId UUID, one directory per session
    manifest.json                # format version, SessionState, sources used,
                                 # original + effective command (ADR-007),
                                 # probed capabilities, timestamps
    raw/
      bep.journal                # append-only raw BEP journal — source of truth
      execlog.bin                # execution log as received (if captured)
      profile.json.gz            # timing profile as received (if captured)
    derived/
      session.db                 # per-session SQLite database
      index/
        actions.fwd.csr          # forward action-dependency CSR (graph-model.md)
        actions.rev.csr          # reverse CSR
        temporal.idx             # time-ordered span index for the timeline
    logs/
      capture.log                # tool-side capture/pipeline logs for this session
```

The manifest is small, human-readable JSON and is the only file read to list
sessions cheaply besides the catalog; catalog and manifest must agree, with
the manifest winning on conflict (the directory is the artifact).

Implementation arrives in Phase 1; journal record framing and integrity
details will be specified here when that lands.
