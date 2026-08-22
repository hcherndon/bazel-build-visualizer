# `aquery` and `cquery` Protobuf Output: What Is Actually In Them

Ground truth for Phase 5, measured against real Bazel 6.5.0, 7.6.1, 8.4.1 and
9.2.0 on 2026-08-22. Every claim is something a probe produced.

Companion to `docs/bep-content.md` (the event stream) and
`docs/exec-log-and-profile.md` (execution logs and profiles). The same rule
governs all three: where two sources describe the same thing, both are kept
under names that say whose they are (ADR-009).

The probe workspace is the Phase 4 one — four genrules, one slow and one large,
plus a passing and a failing `simple_test` — queried with
`aquery --output=proto '//pkg:all'` and `cquery --output=proto '//pkg:all'`.

---

## 1. Shape and scale

### Q1 — The flags are stable across all four versions

`--output`, `--[no]include_artifacts`, `--[no]include_commandline`,
`--[no]include_param_files`, `--[no]include_aspects`,
`--[no]include_file_write_contents` and `--[no]skyframe_state` are present on
6.5.0, 7.6.1, 8.4.1 and 9.2.0 alike. No version needs a different spelling, and
`--output=proto` is accepted by all four.

This is the one part of Phase 5 that is *less* version-sensitive than Phase 4.

### Q2 — The output is one protobuf message, not a stream of records ⚠

`aquery --output=proto` writes a single `ActionGraphContainer`. There is no
length-delimited framing and no record boundary: artifacts, actions, targets,
depsets, configurations and path fragments are all repeated fields of one
top-level message.

This is the single largest constraint on Phase 5. `ActionGraphContainer.parseFrom`
on a five-million-action graph would have to hold the whole thing, and
protobuf-java refuses messages above 2 GB regardless. The importer must read the
top-level fields one sub-message at a time with a `CodedInputStream` and never
materialise the container.

Compare the compact execution log, which is a sequence of independently parseable
entries by design (finding S5). This is not.

### Q3 — The top-level fields are interleaved, not grouped

Counting runs of consecutive same-kind entries in the byte stream:

| Version | Runs (for 6 targets) |
|---|---:|
| 6.5.0 | 71 |
| 9.2.0 | 57 |

An entity kind is never emitted contiguously. A reader cannot process "all
artifacts, then all actions"; it gets them mixed, and the mix is not stable
between versions.

### Q4 — Entities are referenced before they are declared ⚠

Forward references counted over the whole file:

| Reference | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|---:|---:|---:|---:|
| action → depset | 0 | **1** | 0 | 0 |
| action → artifact | 0 | **1** | 0 | 0 |
| action → target | 0 | 0 | 0 | 0 |
| depset → depset | 0 | 0 | 0 | 0 |
| depset → artifact | 0 | **3** | 0 | 0 |
| path fragment → parent | 0 | **2** | **2** | **4** |

So the ordering guarantee the BEP gives for `NamedSetOfFiles` (finding O1, zero
forward references in 1,829) and the compact execution log gives by proto
contract (S5) **does not hold here**, on three of the four versions.

The import must therefore be two-pass, or must defer every reference resolution
to the end. A single-pass importer resolving as it reads would silently drop the
inputs of one action on 7.6.1 and would mis-resolve two to four artifact paths
on 7.6.1, 8.4.1 and 9.2.0 — a small number, and invisible, because the result is
a graph that is merely a little smaller than the truth.

### Q5 — Paths are a linked list, resolvable only after the whole file is read

An `Artifact` carries `path_fragment_id`, and a `PathFragment` carries a `label`
and a `parent_id`. A path is the chain of labels from the fragment to a root.
There are 36–40 fragments for 19–25 artifacts in this tiny workspace, because
directory prefixes are shared — which is the point of the encoding and is why it
is worth keeping as a tree rather than expanding to strings.

Combined with Q4, path resolution cannot happen during the scan. Measured after
a full read: **100% of artifact paths resolve on all four versions.**

---

## 2. Correlating the declared graph with the executed build

### Q6 — `Configuration.checksum` equals the BEP's configuration id

| Version | aquery checksums | BEP configuration ids | Matched | aquery-only |
|---|---:|---:|---:|---:|
| 6.5.0 | 2 | 3 | 2 | 0 |
| 7.6.1 | 2 | 3 | 2 | 0 |
| 8.4.1 | 2 | 3 | 2 | 0 |
| 9.2.0 | 2 | 3 | 2 | 0 |

Every configuration `aquery` reports is one the build event stream also reported,
on every version, with none left over. The BEP's extra id is the undeclared
`system` placeholder (finding C4), which is not a real configuration.

This is the mechanism for plan 24's "configuration mismatches are visible": the
join key is the checksum, and a checksum in the graph that the session's build
never declared means the auxiliary query did not reproduce the build's
configuration.

Note the two id spaces are different. `aquery` uses small local ids (1, 2) valid
only within one query's output; the BEP uses the checksum itself as its id. Only
the checksum crosses the boundary.

### Q7 — Action correlation by primary output path works, and the two sets differ in both directions

| Version | aquery actions | Paths resolved | BEP actions | Matched |
|---|---:|---:|---:|---:|
| 6.5.0 | 14 | 14 | 11 | 10 |
| 7.6.1 | 16 | 16 | 13 | 12 |
| 8.4.1 | 16 | 16 | 13 | 12 |
| 9.2.0 | 16 | 16 | 15 | 14 |

The join key is the reconstructed path of the action's `primary_output_id`,
against the BEP's `id.actionCompleted.primaryOutput` — the same key the BEP uses
for action identity (finding A1).

Neither set contains the other:

- **Declared and never executed:** `testlogs/pkg/fail_test/test.log` and
  `testlogs/pkg/pass_test/test.log`. `aquery` reports the action graph analysis
  produced; a `build` invocation does not run the tests.
- **Executed and never declared:** `bazel-out/stable-status.txt`. The workspace
  status action is not part of the analysed action graph.

So "declared" and "observed" are two populations that overlap, and a UI that
presents either as the build's action count is wrong in a different direction
each time. Plan 2.1 says this in prose; this is the measurement.

### Q8 — A failed query writes a zero-byte file, not a partial one ⚠

Querying a target that does not exist exits 1 and writes **0 bytes** on all four
versions.

An empty file therefore means the query failed, and must never be read as "the
graph has no actions". Since `aquery` on a workspace with no analysable targets
would also produce a nearly-empty container, the exit status — not the file
size — is what distinguishes them.

---

## 3. Field-level version differences

### Q9 — Three fields differ across the supported range

| `Action` field | 6.5.0 | 7.6.1 | 8.4.1 | 9.2.0 |
|---|:-:|:-:|:-:|:-:|
| `is_executable` (19) | — | ✅ | ✅ | ✅ |
| `environment_variables` (7) | — | — | — | ✅ |
| everything else used here | ✅ | ✅ | ✅ | ✅ |

`target_id`, `action_key`, `mnemonic`, `configuration_id`, `arguments`,
`input_dep_set_ids`, `output_ids`, `primary_output_id` and `execution_platform`
are populated on every version.

`is_executable` is genuinely absent on 6.5.0 rather than merely defaulted away.
Checked rather than inferred: 6.5.0 emits the same two `FileWrite` actions that
carry the field on 7.6.1, and emits no field 19 on either of them. The first
draft of this document claimed the version difference from the field counts
alone, which the two extra actions on 7.6.1 could equally have explained.

Those two extra actions are `RepoMappingManifest`, which 6.5.0 does not produce
at all. Otherwise the mnemonic sets are identical: 4 `Genrule`, 2 each of
`FileWrite`, `SourceSymlinkManifest`, `SymlinkTree`, `Middleman` and
`TestRunner`.

`input_dep_set_ids` is present on 10 of the 14–16 actions on every version: an
action with no inputs omits the field rather than sending an empty list, so
absent means no declared inputs and not "unknown".

### Q10 — `ConfiguredTarget.configuration` is deprecated and still populated

The field is marked `[deprecated = true]` in `analysis_v2.proto` in favour of
`configuration_id`, and all four versions still write it — 396 bytes of inline
copy per result. Read `configuration_id` against the top-level `configurations`
list; the inline copy is redundant and will presumably stop appearing.

### Q11 — From 8.4.1, `cquery` carries every configuration's full option set

| Version | `cquery` bytes | of which configurations | fragments | fragment_options |
|---|---:|---:|---:|---:|
| 6.5.0 | 9,304 | 212 | 0 | 0 |
| 7.6.1 | 9,208 | 212 | 0 | 0 |
| 8.4.1 | 46,171 | **37,095** | 5,090 | 31,591 |
| 9.2.0 | 42,913 | **33,694** | 3,870 | 29,440 |

The five-fold growth is entirely in `Configuration.fragments` and
`fragment_options`: **719 options per configuration**, each an effective option
name and value, on a six-target workspace. `aquery`'s configurations stay at 212
bytes on every version and carry none of this.

Two consequences. The block is bounded by the number of configurations rather
than by build size, so it does not grow with the graph — but a build with many
transitions has many configurations, and each costs tens of kilobytes.

And it is a privacy surface. The options are Bazel's own, but several of them
carry user-supplied values — `--define`, `--action_env`, `--host_action_env`,
`--remote_header` among them. On this fixture **zero of the 719 option names
matched the default secret-name patterns**, which is a fact about this fixture
and not a general one. The same name-based redaction that Phase 4 applies to
execution-log environment variables applies here.

---

## 4. Requirements this imposes on Phase 5

1. Parse `aquery` output field-by-field from a `CodedInputStream`; never call
   `ActionGraphContainer.parseFrom` (Q2).
2. Resolve every id reference after the file has been read, not during (Q4).
3. Resolve artifact paths from the fragment tree after the read, and keep the
   tree rather than expanding every path to a string (Q5).
4. Join the graph to the session by `Configuration.checksum` against the BEP's
   configuration id; a checksum the session never declared is a mismatch (Q6).
5. Correlate actions by the reconstructed primary-output path, and expect
   unmatched rows on both sides — neither population contains the other (Q7).
6. Treat a zero-byte query output as a failure, using the exit status rather
   than the file size to tell it from an empty graph (Q8).
7. Read `absent` as "no declared inputs", not as "unknown", for
   `input_dep_set_ids` (Q9).
8. Prefer `configuration_id` over the deprecated inline `configuration` (Q10).
9. Redact `fragment_options` values by name, as Phase 4 does for the execution
   log's environment (Q11).
10. Never present the declared graph as the executed build, or the reverse
    (Q7, plan 2.1).

---

## 5. Not yet measured

Stated so that nothing below is mistaken for a finding.

- **Scale.** Every measurement here is a six-target workspace. Nothing has been
  measured at the plan's five-million-action target, and the streaming
  requirement in Q2 is an inference from the format rather than an observation
  of failure.
- `--skyframe_state`, which changes what `aquery` reports entirely.
- Aspects. `aspect_descriptors` was empty in every run, so nothing is known
  about how aspect-generated actions appear.
- `cquery --output=proto` under `--universe_scope`, or with transitions.
- Whether `action_key` is stable between two runs of the same build, and
  therefore whether it is usable as a join key at all. Only `primary_output` was
  tested.
- Multi-configuration builds: the fixture produced two configurations, both
  `darwin_arm64-fastbuild`. Nothing was measured about a build whose targets
  really do span host and target configurations.
- What a query rejected for an unsupported option looks like, as opposed to one
  naming a missing target.
