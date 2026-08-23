# Privacy

Build data is sensitive: command lines, environment variables, file paths,
and hostnames routinely contain credentials, internal project names, and
user identity. The tool is local-first and treats captured data accordingly
(plan section 22).

## Network posture

- The built-in BES endpoint (`capture-bes`) binds **loopback only**
  (`127.0.0.1`), never `0.0.0.0`. There is no option to widen this.
- The application makes **no outbound network connections**: no telemetry,
  no update pings, no crash upload. Anything of that kind would be a new
  ADR, opt-in, and off by default.
- All captured data stays in the local session directory
  (docs/session-format.md).

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

**The redaction layer landed in Phase 9.** `core.redact` holds the patterns,
the policy and the engine; `storage.redact.SessionRedaction` names every column
it rewrites; and `SessionRedactionTest` asserts that every `TEXT` column the
schema declares appears either in that list or in the list of columns
deliberately left alone. An inventory in a document is checked by somebody
remembering to read it; that one is checked by running it.

A session directory is still a sensitive artifact at rest. What changed is that
there is now a way to produce something that is not.

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
