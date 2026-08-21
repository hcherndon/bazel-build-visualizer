# ADR-001: Swing over SWT for the desktop UI

Status: accepted (2026-08)

## Context

The application is a local desktop tool that must render very large data sets
(50M+ rows in tables, millions of spans on a timeline, million-node graphs)
at interactive frame rates on macOS and Linux. The candidate Java UI toolkits
were Swing and SWT. JavaFX was excluded earlier for packaging weight and
uncertain long-term stewardship. The heavy views will not use stock widgets
either way — they are custom-painted surfaces over primitive-array data — so
the toolkit choice is mostly about windowing, text, accessibility, packaging,
and platform integration.

## Decision

Use Swing with FlatLaf for look-and-feel, custom Java2D rendering for every
heavy view (virtualized table, timeline, graph canvas), and `jpackage` for
native distribution.

- Swing ships with the JDK: no per-platform native artifacts, one classpath
  for macOS and Linux, `jpackage` works out of the box.
- Custom Java2D painting gives full control over the paint path, which is
  where all the performance risk lives; SWT's native widgets offer no
  advantage for fully custom canvases.
- FlatLaf provides a modern, HiDPI-correct look with native macOS titlebar
  and dark-mode integration without native code.
- SWT would add per-platform SWT jars, `-XstartOnFirstThread` complications
  on macOS, and a second threading model, for no gain on custom-drawn views.

## Consequences

- All rendering performance is on us: the Phase 0 spikes (table, timeline,
  graph) must prove Java2D frame budgets on macOS (where Java2D uses the
  Metal pipeline) and Linux before any real feature work.
- EDT discipline is a hard project rule: no I/O, parsing, SQL, or layout on
  the Swing EDT, ever (see docs/architecture.md, threading model).
- We accept Swing's dated stock widgets in exchange for zero native
  dependencies; FlatLaf covers the cosmetic gap.

## Revisit when

The Phase 0 macOS rendering benchmarks fail their targets (docs/performance.md)
and profiling shows the ceiling is the toolkit/pipeline rather than our paint
code. That is the only trigger; feature envy is not.
