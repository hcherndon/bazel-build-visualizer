# Metric definitions

Every metric surfaced in the UI must have an entry here before it ships.
A number without a definition is a bug. Unavailable inputs make a metric
*unavailable or partial* — never silently zero (project rule). Entries
follow this template:

> **Name** — Definition · Units · Source · Formula · Completeness · Caveats

The full catalog grows with Phases 4-8; three worked examples fix the format
now.

---

**Logical wall duration**

- *Definition:* elapsed wall-clock time of one action from observed start to
  observed completion, as a single span.
- *Units:* microseconds (displayed adaptively).
- *Source:* BEP action events; refined by execution log spawn timings when
  that enrichment ran.
- *Formula:* `end_micros - start_micros` per action.
- *Completeness:* only actions with both timestamps observed; the metric
  reports the covered fraction (e.g. "timing for 92% of executed actions").
- *Caveats:* includes queuing/scheduling inside the span for some runner
  types; cache hits have near-zero durations that must not be averaged
  together with executed actions unless explicitly labeled.

**Parallelism factor**

- *Definition:* average number of actions executing concurrently over an
  interval.
- *Units:* dimensionless (actions).
- *Source:* temporal index over observed execution spans.
- *Formula:* `sum(overlap of each action span with interval) / interval
  length`.
- *Completeness:* undefined over intervals where timing coverage is partial;
  reported only for the covered subset, with coverage stated.
- *Caveats:* this is *observed tool-side* concurrency, not `--jobs`; remote
  execution can legitimately exceed local core count.

**Known input bytes**

- *Definition:* total size of an action's input artifacts whose sizes were
  observed.
- *Units:* bytes.
- *Source:* BEP file metadata and execution log entries.
- *Formula:* sum of observed input file sizes; the count of inputs with
  *unknown* size is carried alongside.
- *Completeness:* the "known" prefix is load-bearing — the metric always
  displays with its unknown-count (e.g. "1.2 GiB known, 14 inputs
  unknown"); it is never presented as "input bytes".
- *Caveats:* tree artifacts and unresolved symlinks may report expanded or
  unexpanded sizes depending on Bazel version capability; comparison across
  sessions requires matching completeness.
