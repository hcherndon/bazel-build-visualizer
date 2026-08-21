# Bazel compatibility

The tool targets Bazel 6 through 9. Message shapes, flag names, and
auxiliary-output formats drift across that range, and forks/release
candidates make version strings unreliable.

Policy: **observed capability over version comparison.** The tool never
gates behavior on parsing `bazel version` output. Instead it probes: does
this binary accept this flag (`bazel help`/dry probing), does the stream
contain this message field, did the execution log arrive in the expected
format? Each probe result becomes a recorded capability on the session, and
the instrumentation planner (docs/instrumentation-planner.md) and decoders
branch on capabilities only. A Bazel we have never seen gets a degraded but
honest experience — unknown messages are journaled and counted (ADR-004),
never dropped — rather than a wrong guess based on a version threshold.

Version strings are still *recorded* (they are useful diagnostics and
display data); they are just never used as a behavior switch.

The capability catalog and probing implementation arrive with capture in
Phase 2 and harden through Phase 10; this page will grow the concrete
capability list as probes are implemented.
