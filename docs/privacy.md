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

The redaction layer itself is Phase 9. Until it exists, a session directory is
a sensitive artifact and the tool says so rather than implying otherwise —
nothing in Phases 1 to 3 exports, uploads or shares one.

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
