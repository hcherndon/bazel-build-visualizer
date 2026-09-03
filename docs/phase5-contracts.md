# Phase 5 shared contracts

What every Phase 5 component may assume about the others. Each rule exists
because a measurement in `docs/aquery-and-cquery.md` says the obvious
alternative is wrong; finding ids in parentheses point there.

Phases 3 and 4's contracts still apply. This document adds only what a graph
makes necessary.

---

## 1. Declared and executed are two populations, and neither contains the other

`actions` is what the build event stream said executed. `declared_actions` is
what `aquery` said analysis produced. Measured, in both directions:

- `aquery` declares the `TestRunner` action of every test; a `build` invocation
  runs none of them.
- The BEP reports `bazel-out/stable-status.txt`; `aquery` never declares it.

So **no component may present either count as "the build's actions"**, and no
component may treat an unmatched row on either side as a defect. The correlation
rate is a fact about the two sources, not a quality metric.

`declared_actions.action_id` is nullable for exactly this reason.

## 2. A graph may be called this build's only when the configurations match

Plan 8.6 ends with "never claim an exact graph match unless the configuration
equivalence has been verified", and `ConfigurationMatch` is where "verified" is
defined. The check is a set comparison of `aquery`'s
`Configuration.checksum` values against the configuration ids the BEP
published — equal on all four versions with none left over (Q6).

| State | Means | May the graph be called the build's? |
|---|---|:-:|
| `EXACT` | the query saw exactly the build's configurations | yes |
| `PARTIAL` | the query saw some of them | no |
| `MISMATCHED` | the query saw one the build never used | no |
| `UNKNOWN` | there was nothing to compare | no |

`MISMATCHED` is checked before `PARTIAL`: a graph carrying a configuration the
build never used is describing some other analysis, and that matters more than
whether it also covers everything.

**A `MISMATCHED` graph is still shown.** Its actions and edges are real; what is
not established is that they are this build's. Hiding it would lose data the
user asked for; presenting it unmarked is what plan 12.4 forbids.

## 3. The importer is two-pass, and this is not negotiable

`aquery` output references entities before it declares them — actions before
depsets, depsets before artifacts, path fragments before their parents — on
three of the four supported versions (Q4). The BEP guarantees the opposite for
`NamedSetOfFiles` and the compact execution log guarantees it by proto contract;
neither guarantee transfers here.

So: **read everything, then resolve.** A single-pass importer that resolves as
it reads produces a graph that is slightly smaller than the truth and says
nothing about it.

## 4. Never materialise the container

`aquery --output=proto` writes one `ActionGraphContainer` (Q2). Reading it with
`parseFrom` would hold the whole graph, and protobuf-java refuses messages above
2 GB regardless. Every reader uses `CodedInputStream` and consumes one top-level
sub-message at a time.

This is the reason the importer's two passes are two passes over the *file* and
not over an in-memory object.

## 5. Three depset tables, and they describe different things

| Table | Source | Describes |
|---|---|---|
| `depsets` | BEP | a target's output file sets |
| `input_sets` | execution log | what a spawn actually consumed |
| `graph_depsets` | aquery | what an action declared it could read |

They are not merged and not deduplicated against each other. An action that
declares a hundred inputs and reads three is telling the truth twice.

## 6. Edges carry their derivation

`action_edges` is keyed `(producer, consumer, derivation)`, so the same pair can
appear once as `DECLARED` and once as `OBSERVED`. Plan 13.1 step 7 requires it,
and the two answer different questions: what the action was allowed to read, and
what it did read.

**No component may sum or union the two derivations into one edge count.**

## 7. Zero bytes means failure, not an empty graph

A query naming a target that does not exist exits 1 and writes a zero-byte file
(Q8). The exit status is what distinguishes a failed query from an empty one;
the file size cannot.

## 8. A failed auxiliary query leaves the session usable

Plan 24. `graph_sources` records the failure with its exit code and error
excerpt, and no Phase 5 table gets rows. Nothing about it touches `actions`,
`action_attempts` or anything else an earlier phase wrote — the same structural
containment Phase 4 uses, for the same reason.

## 9. Structural completeness is explicit

Before an imported action graph may support a completeness-sensitive result,
its `graph_sources` row must record zero unresolved artifact ids and zero
unresolved depset references. Missing artifact declarations, broken fragment
chains, missing primary/output artifacts, missing action-input depsets, and
missing transitive-child depsets all
cause joins to omit dependencies, so configuration equality alone is not a
trust gate. Older sessions have `NULL`, not a fabricated zero, and remain
visible with completeness unverified.

## 10. What a CSR index may claim

An index file is only ever read together with its `graph_indexes` row. A file
whose header disagrees with the row is refused rather than used: a stale index is
worse than none, because it answers.

Indexes are built by atomic rename (plan 13.2), so a half-written file is never
visible under its final name.

A replacement aquery attempt invalidates both action-index registrations, and
a replacement cquery attempt invalidates the configured-target registration,
before new bytes are parsed. Process failures take the same path even though
there is no protobuf to import. A failed query, import, or rebuild may leave the
preceding CSR file on disk, but it leaves no registry row through which that
file can be loaded. New registrations carry the `graph_sources` row they were
derived from; a source that is no longer successful makes its registered index
unavailable.

Each aquery and cquery source also records its target-scope provenance. Only
`EXACT_BEP_TARGETS`, produced from a BEP with its final marker, can support an
exact graph claim. `REQUESTED_PATTERNS` discloses the no-target fallback;
`UNKNOWN` covers migrated sessions, preparation failures, and nonempty target
sets from incomplete BEP streams. Existing rows migrate to unknown, never exact.

## 11. What Phase 5 does not do

- **It does not compute a dependency critical path.** That is Phase 6, and it
  needs timing joined to this graph.
- **It does not compute transitive closure.** Plan 13.3: never compute or store
  a complete one. Neighbourhoods are depth- and node-budgeted.
- **It does not lay out or render a large graph.** Phase 7 owns the graph view;
  Phase 5 owns the data and the traversals it will call.
- **It does not resolve a configured-target edge's target configuration.**
  The proto can express it — `Rule.configured_rule_input` carries a
  dependency's label and its configuration checksum — and Bazel populates it
  zero times on all four supported versions, with and without
  `--proto:include_configurations` (Q12). So the edge is between labels, and
  there is no column because there is nothing to put in it rather than because
  the format cannot say it.
