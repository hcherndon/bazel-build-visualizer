# ADR-013: Java formatting, Error Prone and import policy

Status: **accepted** (2026-09-03).

Amends [ADR-009](009-bazel-build.md). It supersedes that migration's temporary
choice to disable Error Prone after build parity was established. It does not
change the package-local target structure in
[ADR-010](010-package-local-bazel-targets.md).

## Context

ADR-009 disabled Bazel's bundled Error Prone checks so the Gradle-to-Bazel
migration compared build systems rather than introducing new lint failures.
That reason ended when the migration reached parity. The repository also had
no formatter gate, so layout drifted, and Java code often used fully qualified
symbols in method bodies despite having an import section.

The codebase now has package-local Bazel targets. Quality checks should use
those same boundaries so unchanged packages remain cached and independent
packages can run in parallel.

## Decision

- Use Google Java Format 1.36.1 for all repository Java sources. The standalone
  all-dependencies jar is fetched only as a build tool and pinned by SHA-256 in
  `MODULE.bazel`.
- Apply a format-checking aspect to selected Bazel Java targets. Each target's
  direct source files produce their own cacheable marker. Downloaded and
  generated sources are not rewritten or checked as repository source.
- Provide `bazel run //tools:format_java` to rewrite the current repository and
  `bazel run //tools:format_java -- --check` for a repository-wide dry run.
  Discovery does not enter nested Git repositories or worktrees.
- Enable the standard Error Prone checks supplied by Bazel's Java toolchain.
  Promote `WildcardImport` and `UnnecessarilyFullyQualified` to errors so code
  uses explicit, non-wildcard imports.
- Disable only `SelfAssignment`. The Error Prone version bundled with Bazel
  9.2 diagnoses valid compact record-constructor normalization as assignment
  to the same variable. Remove this exception when the bundled checker handles
  that Java construct correctly.
- Exclude external and generated output paths from repository-specific Error
  Prone policy. Third-party and generated source remain their owners' code.
- CI runs both `bazel build //...` and `bazel test //...`. The build step is
  required because a test expansion alone need not select every production
  target for the formatting aspect.

## Tool review

Google Java Format is maintained by Google, licensed under Apache-2.0, and the
selected release was current on 2026-09-03. The standalone jar is isolated to
`tools/java_quality`; it is not linked into the application, packaged, or used
at runtime. A checksum pin makes the download reproducible. Package-local
format actions add bounded process overhead on a cold build and are cacheable;
they do not read the entire repository. This is preferable to adding a runtime
dependency or a custom formatting implementation.

## Consequences

- The first adoption mechanically reformats the Java tree and replaces
  compiler-proven unnecessary qualifications with imports. Later changes fail
  at build time if they drift.
- Formatting, compilation and Error Prone share Bazel's package-level cache
  boundaries. The explicit rewrite command remains repository-wide by design.
- Error Prone can expose real defects when Bazel updates its bundled version.
  New suppressions or disabled checks require a narrow reason in code or an
  amendment here; the full checker set must not be disabled again.
- Wildcard imports are forbidden even when a formatter could expand them. An
  import names each dependency clearly and avoids name changes when another
  symbol is added to a package.

## Revisit when

- Bazel's bundled Error Prone no longer produces the compact-record
  `SelfAssignment` false positive. Re-enable the check and remove the exception.
- Format action startup becomes a measured clean-build bottleneck. Preserve
  package-level caching while considering a persistent worker; do not replace
  the formatter with a different style silently.
- Google Java Format requires a runtime or platform dependency beyond the JDK.
  Repeat the license, maintenance, footprint and reproducibility review before
  upgrading.
