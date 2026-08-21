# Instrumentation planner

The component that decides which flags to add when the tool launches Bazel
on the user's behalf, governed entirely by ADR-007: the user always sees the
original command and the effective command, every added flag is explained
individually, any flag can be vetoed, and explicit user flags are never
overridden — conflicts are surfaced.

Planned shape: a declarative catalog of candidate instrumentation flags,
each entry carrying the flag template, the capability it requires (resolved
against docs/bazel-compatibility.md probing, never against a version
number), a one-sentence user-facing rationale, and a statement of what data
is lost if vetoed. The planner takes (user command, probed capabilities,
user vetoes) and produces an effective command plus a human-readable plan
that is stored verbatim in the session.

Arrives in Phase 7 (planner + UI), with the minimal launched-build subset
(BES flags only) arriving with `bazel-runner` in Phase 2.
