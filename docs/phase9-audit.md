# Phase 9 audit

> Historical audit record. Its Gradle commands and pre-release packaging
> metadata are not current instructions. ADR-009 makes the repository
> Bazel-only; use README.md and docs/packaging.md for current commands.

The four checks the earlier phases turned into greps, run again. Every finding
below is fixed.

1. **Call sites of every new public method.** Fourth phase running as the
   highest-yield check: 4 findings in Phase 6, 11 in Phase 7, 35 in Phase 8, 58
   listed here.
2. **Declared columns against every INSERT** — and this phase added a new
   direction: declared columns against the *redaction inventory*.
3. **Read the prose.**
4. **Run the thing, and measure it.**

---

## 1. Fifty-eight members with no production caller, and the five that mattered

Most of the 58 were record accessors reached through a `describe()` the UI
shows, and separating those from the real findings is a matter of reading each
hit rather than trusting the count. `GraphExport.primary()` is the clearest
false positive: it looks like an orphan because the sweep excludes the
declaring file, and it is used by `describe()` three lines below itself.

Five were features built and wired to nothing.

| Wired to nothing | Consequence |
|---|---|
| `SessionCatalog.rescan` | **Relocation is a Phase 9 exit criterion.** The test proved the mechanism and the application never called it, so a user who moved their sessions root would have had a stale catalog for ever. It now runs once per launch. |
| `SessionCatalog.setPinned` / `forget` | Pinning is the only way a user can say "this one matters" and it is exactly what retention refuses to override. No control existed, so the protection was unreachable. |
| `RetentionPolicy` and `plan` / `apply` | "Add retention and cleanup" was a policy object nothing could apply. There is a Clean Up Sessions dialog now: choose the limits, see the plan, confirm. |
| `BvizWriter.estimate` | Plan 10.4 asks for the space to be estimated before an export. It runs first now and refuses when the target cannot hold the upper bound — finding that out after twenty minutes of writing is the failure it avoids. |
| `RedactionPolicy.forDisplay` | **docs/privacy.md claimed the UI masks sensitive fields, and nothing did.** The action inspector renders a build's argv one line at a time and a `--remote_header` carrying a bearer token is an ordinary argument there. |

Four further capabilities the plan asks for by name were reachable only from a
test: plan 22.2's optional omission of environment values, label
pseudonymisation, and the age and total-size retention rules. All four are in
the two dialogs now, all four off by default, because each costs something real.

**Deleted:** `compressionRatio`, `declaredBytes`, `withMaxExpandedBytes`,
`Redactor.policy`, `CatalogEntry.openedAt`, `CatalogEntry.withPinned`.
**Demoted:** `SecretPatterns`' two default lists, and `SessionRedaction.redact`
— a caller that could redact an arbitrary open connection could redact the
session itself, and ADR-004 says the session stays as captured.

---

## 2. Columns, in both directions

The redaction inventory names 35 columns to rewrite and 83 to leave alone.
`SessionRedactionTest` asserts that every `TEXT` column the schema declares
appears in one list or the other, so a column added by a future migration fails
the build until somebody decides which it is.

**The reverse direction found a real bug**, and it is a bug in the audit method
rather than in the code. Schema v3 renamed two columns with
`ALTER TABLE RENAME COLUMN`, which is invisible to a check that reads only
`CREATE TABLE` statements — the way every previous phase's column audit worked.
The tests export named `tests.first_start_micros`, which has not existed since
v3, and it failed the first time it ran. `SessionRedactionTest` now also asserts
that every column named in the inventory still exists.

Note for the next audit: **grep `CREATE TABLE` and `ALTER TABLE`, or read the
migrated database.** The inventory-completeness check does the latter and was
right; the list it checks was built by the former and was stale.

---

## 3. Prose that had gone stale

- **`docs/privacy.md`** said "the redaction layer itself is Phase 9. Until it
  exists…". It exists. The page now describes what redaction does, what it
  refuses to claim, and the three things it deliberately does not redact.
- **`docs/architecture.md`** described four modules by what they held before
  this phase: `core-model` without the redaction engine, `session-format`
  without the portable archive, `capture-file` without the BEP export,
  `storage-sqlite` without the table exports or the redaction inventory.
- **`docs/session-format.md`** listed `exports/` in the directory layout and now
  also documents the archive — including that `exports/` is deliberately *not*
  carried into one.

---

## 4. What running it found

Two things, both from actually invoking `jpackage` rather than writing a task
and assuming.

- **macOS refuses an app-version whose first component is zero**, and this
  project is `0.1.0`. `CFBundleVersion` is a build-ordering number rather than
  an identity, so the task stamps `1.0.0` and announces the substitution on
  every run; `-Pbbv.packageVersion` overrides it.
- **jpackage does not cross-compile.** "Build Apple Silicon and Intel packages"
  means running it on two machines. `docs/packaging.md` says so plainly rather
  than a build producing one package and naming it after both.

Verified by running: the image builds, its `Info.plist` declares the `.bviz`
association as a `CFBundleDocumentTypes` entry and an exported UTI, its `.cfg`
carries all four JVM options including the native-access grant, and the packaged
launcher runs the CLI subcommands.

Measurement settled one open question: whether the exports stream. At 500,000
actions a redacted CSV is 1,037 ms and 40 MB, a redacted database copy 1,519 ms,
and the heap does not grow with the file. See `docs/performance.md`.

---

## Kept after checking

`RedactionReport`'s counters (`byRule`, `byField`, `redactions`,
`distinctSecrets`, `valuesInspected`) have no caller outside `lines()`, which is
what the UI shows. They are the value type's accessors and a caller wanting
counts rather than sentences would use them; they are named here so the next
audit does not rediscover the reason.

`SessionRedaction.sensitiveColumns()` and `deliberatelyNotSensitive()` are
public with only test callers, and that is their point: the test *is* the
mechanism that keeps the inventory true.
