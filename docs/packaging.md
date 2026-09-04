# Packaging for macOS

What `bazel run //app:jpackage` produces, what it deliberately does not do,
and where the credentials live.

## 0.1.0 release status

The supported 0.1.0 package target is Apple Silicon macOS. An unsigned Apple
Silicon development image has been built and launched on macOS 26.6.2. No
signed and notarized 0.1.0 candidate has passed the release checklist below,
so the repository does not yet claim a generally installable release.

Intel macOS packaging is unavailable in 0.1.0: the repository pins arm64
protobuf and gRPC code generators for every macOS build. Linux can run the
remote SSH client from source but has no installer; Windows remains unverified.
Ordinary CI builds the deploy jar and runs tests, but deliberately omits native
packaging, signing, notarization, and host-state real-Bazel coverage.

## What is built

```
bazel run //app:jpackage                  # an .app image (the default)
bazel run //app:jpackage -- --type=dmg    # a disk image
bazel run //app:notarize                  # submit and staple what dist/ holds
```

Output lands in `dist/jpackage` at the workspace root (gitignored). jpackage
runs against the deploy jar (`//app:app_deploy.jar`) — a single fat jar is
exactly what a desktop application that is never on anybody else's classpath
wants, and it is the artifact Bazel already builds deterministically. (The
Gradle era packaged the `installDist` directory instead; that layout died
with the `application` plugin.) jpackage itself comes from the **local JDK 25**
(`JAVA_HOME` or `PATH`) — the hermetic remote JDK is a build-time toolchain,
and packaging for the host is the one honestly host-specific step. The
packaging script rejects another jpackage major version because the application
is compiled for Java 25 (class-file version 69); an older bundled runtime would
produce an image that cannot launch.

The image carries the JVM options the `bbv` launcher carries. This is not
cosmetic: `--enable-native-access=ALL-UNNAMED` is load-bearing, because
sqlite-jdbc and FlatLaf both call `System::load` and a future JDK makes that an
error rather than a warning (see `tools/java_test_settings.bzl`). On macOS it
also carries
`--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED`, which lets the
Timeline's optional adapter receive native trackpad magnification events. The
adapter is loaded reflectively and fails closed: without the export, its native
pinch path stays off while Control/Command + wheel and the zoom buttons keep
working.

An unsigned Apple Silicon development image has been verified: it builds, its
`Info.plist` declares the file
association, its `.cfg` carries all four JVM options, and the packaged launcher
runs the CLI subcommands. This is development evidence, not a signed 0.1.0
release-candidate result.

The same macOS package export belongs in an IDE VM-options field or a direct
graphical `java -jar` command. `bazel run //app:app` and `//app:jpackage` add it
themselves; [Troubleshooting](troubleshooting.md#timeline-pinch-does-not-zoom-on-macos)
shows the manual command. It is intentionally not added
on Linux, where that macOS-only package does not exist.

## Terminal native resources

Terminal starts either the selected local login shell or the system OpenSSH
client inside a Pty4J pseudo-terminal. Unlike JediTerm's pure Java emulator,
Pty4J is not only Java:
its jar contains `resources/com/pty4j/native/` libraries and spawn helpers for
its supported hosts and extracts the matching files at runtime. The reviewed
0.13.8 artifact's macOS `libpty.dylib` and `pty4j-unix-spawn-helper` are signed
JetBrains universal x86-64/arm64 binaries. JNA and JNA Platform 5.14.0 are
locked transitively.

`jpackage.sh` copies the Bazel deploy jar intact, so it must retain that native
resource tree; splitting or filtering the fat jar would break terminal startup.
The built `//app:app_deploy.jar` has been inspected and retains the macOS,
Linux, FreeBSD and Windows resource entries, including both macOS files. The
existing `--enable-native-access=ALL-UNNAMED` JVM option also covers the
JNA/Pty4J path. A release smoke test must launch the packaged application,
choose a local Workspace, navigate to Terminal, run a command that prints text,
resize it, and exercise an alternate-screen program. The SSH release fixture
must repeat those operations through a selected SSH Workspace. Building the
image and running CLI subcommands do not prove native PTY startup.

The 2026-08-28 macOS development image passed that terminal stack smoke against
an authorized Linux SSH fixture. The packaged launcher started successfully;
its copied deploy jar had the exact Bazel-built SHA-256; and a probe loaded the
terminal UI and Pty4J from that packaged jar, connected through OpenSSH, printed
text under `TERM=xterm-256color`, resized the remote PTY to 117 by 39, and
entered and left the alternate screen. Signed release candidates must repeat
the smoke on every packaged host; this record covers the unsigned macOS
development image only. The local login-shell path has focused PTY, resize and
bounded-close tests, but has not yet been repeated as a packaged interactive
smoke; that remains a release-candidate check.

## Third-party licenses and notices

The deploy jar carries a collision-safe legal bundle under
`META-INF/third-party/`. Its `THIRD-PARTY-NOTICES.txt` indexes the complete
Bazel-resolved runtime closure, not a feature-level subset. The
`//app:app_runtime_maven_deps` genquery proves the exact 40 Maven coordinates,
including the intentionally empty Guava `listenablefuture` and gRPC
`grpc-context` compatibility artifacts. The release test requires a matching
notice and named legal file for every coordinate. It also covers embedded
lineages and vendored runtime sources, including the Bazel, googleapis and
google/pprof schemas. The pprof schema uses its exact
`PPROF-APACHE-2.0.txt`, rather than a shared generic license entry.

The repository browser includes 32 SVGs copied from Material Icon Theme 5.38.1
at commit `448ab3977ef83b817c2c722ce7cd5034d195b39f` under MIT. Those files total
14,407 bytes. Its separate `bazel.svg` and `bazel-folder.svg` are original
project artwork, do not contain the official Bazel logo, and identify BUILD and
Starlark files or Bazel directory links. The two project SVGs total 9,080 bytes;
all 34 SVG files total 23,487 bytes before deploy-jar compression.
Project and language names and symbols remain the property of their respective
owners; their use identifies file types and does not imply endorsement or
affiliation. The exact upstream MIT text is retained as
`MATERIAL-ICON-THEME-MIT.txt`; that notice does not cover the project-owned
`bazel.svg` or `bazel-folder.svg`. Icons are packaged resources, not content
fetched when the application runs.

FlatLaf Extras 3.7.2 renders those resources through JSVG 2.1.0. The Extras
adapter and JSVG renderer are pure Java and add no runtime download. FlatLaf
core separately carries seven Windows, Linux and macOS native libraries. JSVG's
project license is MIT, but its shipped code also includes four modified OpenJDK
gradient classes under GPL-2.0-only with the Classpath Exception, a
BSD-selected blend implementation and a zlib-licensed DataUri implementation.
The legal bundle therefore includes those exact terms and the official
`JSVG-2.1.0-CORRESPONDING-SOURCE.tar.gz`, with the affected sources and build
inputs needed to rebuild them.

Pty4J embeds native code for every supported host in the same jar. The legal
bundle therefore also keeps the WinPTY MIT text and the Windows Terminal
1.22.11141.0 MIT text and third-party notice, even in a macOS package. The
files and icon-rendering legal texts come from version-pinned official source
revisions; they are not reconstructed from Maven metadata. The
`//app/src/test/java/com/holtherndon/bazelviz/app:ThirdPartyNoticesTest` target
opens the finished deploy jar and gates the exact legal payload and hashes; the
JSVG source archive, contents and hash; the complete gRPC, FlatLaf, JNA, Pty4J
and SQLite native resource sets; and the shaded Netty, JCTools and embedded
lineage evidence. This protects both the Bazel fat jar and the jpackage image,
because `jpackage.sh` copies that jar without filtering it.

## Product and bundle versions

The 0.1.0 identity is consistent across `AppInfo.VERSION`, the packaging
script, and the user-visible `CFBundleShortVersionString`. macOS requires a
positive `CFBundleVersion`, so the first 0.1.0 package uses the independent
bundle build number `1`. The script reports that distinction on every run,
including these values:

```
jpackage: macOS requires a positive CFBundleVersion, so this bundle uses
 build version 1; its product version remains 0.1.0.
```

The checked-in `Info.plist` template sets the short version to `0.1.0` and lets
jpackage substitute the bundle build number. A static packaging test and the
Java application metadata test guard those values. A release owner must still
inspect the finished plist before signing; source-template checks do not prove
the generated bundle.

## File associations

`app/src/main/packaging/bviz.properties` declares one association: `.bviz`,
the portable session archive. jpackage turns it into a `CFBundleDocumentTypes`
entry and an exported UTI, which is what puts this application in Finder's
"Open With" and makes a double-click deliver the path.

The path arrives through `Desktop.Action.APP_OPEN_FILE`, **not** through
`main`, which is why `DesktopIntegration` installs a handler for it. Without
one, the association works perfectly and the application opens an empty window
— indistinguishable from the association not working.

Only `.bviz` is claimed. A `.bep` or `.json` file belongs to whatever the user
already opens those with, and an application that took ownership of `.json` on
install would be a bad neighbour.

## Signing and notarization

Neither credential is in this repository, and neither is read from any
committed file.

| Variable | What it is | Read by |
|---|---|---|
| `BBV_MAC_SIGNING_IDENTITY` | the Developer ID name | `//app:jpackage` |
| `BBV_MAC_NOTARY_PROFILE` | a keychain profile **name** | `//app:notarize` |

The notary profile is stored once, by the person doing the release, with

```
xcrun notarytool store-credentials <profile> --apple-id … --team-id …
```

so the Apple ID and the app-specific password live in the keychain and this
build never sees either. `:app:notarize` refuses to run without the profile
name and says exactly this in its error rather than prompting.

An unsigned build is a perfectly good local build. `:app:jpackage` says so
when the identity is absent, rather than producing a package that will fail on
somebody else's machine without warning:

```
jpackage: BBV_MAC_SIGNING_IDENTITY is not set, so the package is unsigned.
Gatekeeper will refuse it on another machine.
```

## Apple Silicon and Intel

**jpackage does not cross-compile.** It packages for the architecture of the
JDK running it, and jlink cannot produce a runtime image for another
architecture without that architecture's `jmods`. More importantly, this
repository currently selects arm64 protobuf and gRPC generators for every
macOS CPU. A clean Intel build therefore cannot reach jpackage.

The 0.1.0 package is Apple-Silicon-only. Do not relabel it as universal or
Intel-compatible. Intel support remains planned and requires pinned x86-64
code-generation tools plus a clean build and the full release-candidate smoke
on an Intel host.

## Release-candidate checklist

These are manual release gates; they have not all passed for 0.1.0 yet.

1. On a clean Apple Silicon macOS host, select JDK 25, set
   `BBV_MAC_SIGNING_IDENTITY` to the intended Developer ID identity, and build
   the deploy jar and DMG with `bazel run //app:jpackage -- --type=dmg`. An
   unsigned DMG is a development artifact, not a release candidate.
2. Inspect the finished `Info.plist`: short version `0.1.0`, bundle version `1`,
   the `.bviz` association, and no unsupported minimum-macOS claim. Inspect the
   launcher configuration for all required JVM options.
3. Before submission, run `codesign --verify --strict` on the DMG and
   `codesign --verify --deep --strict` on the mounted application, then inspect
   both with `codesign --display --verbose=4`. Confirm the expected Developer
   ID identity; stop if either artifact is unsigned or signed by another identity.
4. Smoke the signed candidate: run its launcher and `--version`, open the GUI,
   import a known session, double-click a `.bviz` file, and exercise both local
   and authorized-SSH Terminal text, resize, and alternate-screen behavior.
5. Set `BBV_MAC_NOTARY_PROFILE` and run `bazel run //app:notarize`. That script
   submits the already-signed DMG and staples the result; it does not sign it.
6. Hash the final stapled artifact. On a clean machine, verify Gatekeeper,
   install through Finder, and repeat every smoke in steps 2–4 against that
   exact final artifact. Do not mutate or repackage it afterward.
7. Record the host, JDK, final artifact hash, signing/notarization evidence, and
   results. Publish only the exact candidate that passed every applicable step.

## What is not here

- **No custom application icon or completed package polish.** jpackage still
  supplies its default icon, and the package has no explicit description or
  copyright metadata. The project makes no minimum-macOS promise beyond the
  measured host above; a release candidate must not inherit an unverified
  default deployment floor.
- **Preferences is intentionally narrow.** The modeless window has **Theme**
  and **Discovery** tabs, and packaged macOS integration routes its Preferences
  action to the same window as **Settings › Preferences…**. It is not yet a
  general surface for the limits in `docs/limits.md`.
- **No auto-update.** Out of scope for v1 (plan 1.4) and it would need a signing
  and hosting story the project does not have.
- **No installer for Linux or Windows.** The plan is macOS-first; jpackage would
  produce them and nobody has tested one.
