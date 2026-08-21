# ADR-007: Transparent instrumentation — no silent flag injection

Status: accepted (2026-08)

## Context

To capture rich data the tool runs Bazel with extra flags (BES endpoint,
execution log, timing profile, and so on). Build flags change build behavior:
they can alter caching, output paths, performance, and occasionally
correctness. A tool that silently rewrites the user's command line destroys
trust the first time a build behaves differently "under the viewer", and
makes bug reports impossible to reason about.

## Decision

Instrumentation is fully transparent to the user:

- The UI always shows **both** the original command the user asked for and
  the effective command actually executed.
- **Every added flag is individually listed and explained**: what it does,
  why the tool wants it, and what data is lost if it is removed.
- The user can veto any added flag before the run; the tool degrades
  gracefully (captures less) rather than insisting.
- The effective command line is recorded verbatim in the session (it is part
  of the raw record, ADR-004), so any session can answer "what exactly ran?"
- The planner never adds a flag it cannot explain, and never overrides a
  flag the user set explicitly — conflicts are surfaced, not resolved
  silently.

## Consequences

- The instrumentation planner (docs/instrumentation-planner.md) is a
  first-class component with a declarative flag model, not a string-concat
  helper.
- Capability probing (docs/bazel-compatibility.md) must precede planning, so
  the explanation shown matches what this Bazel version actually supports.
- Some captures will be voluntarily degraded by user veto; every downstream
  metric must therefore state its completeness (docs/metric-definitions.md)
  instead of pretending missing data is zero.

## Revisit when

Not expected to be revisited; transparency is a product identity decision.
Only the *mechanics* (how explanations are presented) should evolve.
