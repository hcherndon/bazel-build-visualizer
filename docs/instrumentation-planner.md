# Instrumentation planner

The component that decides which flags to add when the tool launches Bazel
on the user's behalf, governed entirely by ADR-007: the user always sees the
original command and the effective command, every added flag is explained
individually, any flag can be vetoed, and explicit user flags are never
overridden — conflicts are surfaced.

Implemented in Phase 2: `runner`'s `InstrumentationPlanner` takes
(user command, probed capabilities, preset, user vetoes, conflict
resolutions) and produces an `InstrumentationPlan` — the original argv, the
effective argv, every added flag with its reason, cost and capability status,
every replaced flag with the approval that permitted it, the conflicts, and
what the resulting session will and will not contain. Planning is pure: the
same request always yields the same plan, and building one starts nothing, so
the dialog can plan, show, take an answer and plan again.

The plan is stored verbatim in the session as `instrumentation-plan.json`,
separately from the manifest. The manifest says which flags were injected;
this says what each was for and what it cost, which is what makes "why was my
build run with these extra flags" answerable six months later.

Three rules the code enforces rather than documents:

- Flags go in the command segment, before any target pattern and before any
  user `--`. After a `--` a flag is read as a negative target pattern, the
  build dies during target resolution, and no event stream is written at all.
- A flag is injected only when the binary was *observed* to support it. An
  unprobed Bazel gets nothing and is told apart from an incapable one.
- Replacing a user's option requires the id of the resolution they chose.
  `ReplacedFlag` cannot be constructed without it.
- A flag the plan marks disableable can be unticked in the dialog. That
  re-plans and reopens, so what the user finally approves is what runs; the
  approved `PlanRequest` is carried through to launch rather than rebuilt,
  which is what stops a veto being silently dropped between the two.

The catalog currently covers the Phase 2 flags: the embedded BES backend, the
upload timeout, complete action publication, and the binary-BEP fallback for a
BES conflict. For a local launch the backend URI is desktop loopback. For an
SSH launch it is an allocated remote-loopback port reverse-forwarded to that
same listener; preflight establishes the real forward before presenting the
plan, so the approved URI is the URI Bazel receives. Phases 4 and 5 add entries
for the JSON trace profile, the execution log and the auxiliary queries. The
Starlark analysis adds a separate `--starlark_cpu_profile` entry to Performance
and Full: it names a managed gzip pprof output, carries medium overhead, and is
independently disableable. A user-supplied value is retained rather than
replaced. The mechanism does not change.

The SSH review also names the transport separately from Bazel's effective argv:
destination, remote working directory, desktop listener, remote BES address
and private remote staging directory. It states that the primary command uses
a forced TTY, so remote stdout and stderr are one merged stream, and that
planned files return through SFTP. Remote output paths are deliberately not
checked with desktop filesystem APIs.

Format 3 of `instrumentation-plan.json` keeps that distinction as evidence:
`besEndpoint` is the address advertised to Bazel, `desktopBesListener` is the
listener behind the tunnel, and `executionHost` says `LOCAL` or `SSH`. SSH
plans also retain the non-secret host display and private staging path.

The auxiliary graph commands are now first-class plan entries rather than an
implicit finalization detail. The review dialog, CLI dry-run,
`instrumentation-plan.json`, and session manifest all record the same aquery
and cquery argv that finalization runs. Both name session-local `aquery.query`
or `cquery.query` files. After the build, capture streams the exact distinct
top-level labels from the normalized BEP target table into each file as a
quoted `deps(set(...))` expression. This matters because a build/test wildcard
can exclude a `manual` target that cquery's wildcard would analyse; merely
reusing the pattern can widen the graph or fail on a target the build never
selected. Exact scope additionally requires the BEP final marker. A nonempty
set from an incomplete stream is retained but unverified; when no target was
reported, the file uses the requested-pattern closure and records that its
scope may be wider. The queries run
after the primary invocation, not concurrently,
because two Bazel commands in one workspace contend for the server lock and a
concurrent analysis pass would perturb the timings being measured.

On SSH, those same auxiliary argv run on the execution host without a TTY.
Aquery and cquery stdout stream to their already-planned local managed files,
while both generated query expressions are uploaded to their remote staged paths
first. This is why binary query bytes are not exposed to the primary command's
TTY channel.

The planner sees more than the argv. Options also come from `.bazelrc` files,
and one setting `--bes_backend` used to be invisible — so the mandatory
conflict never fired and the team's backend silently missed the invocation.
Bazel's own `--announce_rc` output is now read and fed into the same conflict
checks. It covers the `common` and `build` sections; a command-specific
section is not visible, and the plan says which part was inspected rather than
claiming to have checked everything.
