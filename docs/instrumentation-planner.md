# Instrumentation planner

The component that decides which flags to add when the tool launches Bazel
on the user's behalf, governed entirely by ADR-007: the user always sees the
original command and the effective command, every added flag is explained
individually, any flag can be vetoed, and explicit user flags are never
overridden — conflicts are surfaced.

Implemented in Phase 2: `bazel-runner`'s `InstrumentationPlanner` takes
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

The catalog currently covers the Phase 2 flags: the local BES backend, the
upload timeout, complete action publication, and the binary-BEP fallback for a
BES conflict. Phases 4 and 5 add entries for the profile, the execution log
and the auxiliary queries; the mechanism does not change.

The planner sees more than the argv. Options also come from `.bazelrc` files,
and one setting `--bes_backend` used to be invisible — so the mandatory
conflict never fired and the team's backend silently missed the invocation.
Bazel's own `--announce_rc` output is now read and fed into the same conflict
checks. It covers the `common` and `build` sections; a command-specific
section is not visible, and the plan says which part was inspected rather than
claiming to have checked everything.
