# Packaging for macOS

What `bazel run //app:jpackage` produces, what it deliberately does not do,
and where the credentials live.

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
with the `application` plugin.) jpackage itself comes from the **local** JDK
(`JAVA_HOME` or `PATH`) — the hermetic remote JDK is a build-time toolchain,
and packaging for the host is the one honestly host-specific step.

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

Verified on this machine: the image builds, its `Info.plist` declares the file
association, its `.cfg` carries all four JVM options, and the packaged launcher
runs the CLI subcommands.

The same macOS package export belongs in an IDE VM-options field or a direct
graphical `java -jar` command. `bazel run //app:app` and `//app:jpackage` add it
themselves; the README shows the manual command. It is intentionally not added
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
`META-INF/third-party/`. Its `THIRD-PARTY-NOTICES.txt` indexes JediTerm 3.74,
Pty4J 0.13.8 and the transitives introduced by that stack, plus the repository
browser's fixed SVG assets and renderer. Component-specific files preserve
JediTerm's selected Apache-2.0 text, Pty4J's complete EPL-1.0 text and upstream
notice, JNA's Apache license choice, and the Kotlin, JetBrains Annotations and
SLF4J texts and notices. The index also records the vendored google/pprof
schema and its pinned revision; the bundle's standard Apache-2.0 text covers
that source, while its exact upstream license remains beside the proto source.

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

FlatLaf Extras 3.7.2 renders those resources through JSVG 2.1.0. FlatLaf is
Apache-2.0 and JSVG is MIT; their exact upstream texts are retained as
`FLATLAF-APACHE-2.0.txt` and `JSVG-MIT.txt`. The matching FlatLaf release and
JSVG release were published 2026-07-09 and 2026-05-05 from active projects.
They are pure Java and add 904,150 bytes of resolved jars before deploy-jar
compression, with no native resource or runtime download.

Pty4J embeds native code for every supported host in the same jar. The legal
bundle therefore also keeps the WinPTY MIT text and the Windows Terminal
1.22.11141.0 MIT text and third-party notice, even in a macOS package. The
files and the icon-rendering legal texts are copied from version-pinned official
source revisions; they are not reconstructed from Maven metadata. The
`//app/src/test/java/com/holtherndon/bazelviz/app:ThirdPartyNoticesTest` target
opens the finished deploy jar, requires every named entry and verifies each
reviewed packaged file's SHA-256. This test protects both the Bazel fat jar and
the jpackage image, because `jpackage.sh` copies that jar without filtering it.

## The version stamped on the package is not the product's version

macOS refuses a `CFBundleVersion` whose first component is zero, and this
project is `0.1.0`. `CFBundleVersion` is a build-ordering number rather than an
identity — the version a user reads is `AppInfo.VERSION`, in the About dialog —
so the task stamps `1.0.0` and **says so on every run**:

```
jpackage: macOS will not accept an app-version starting with zero, so this
package is stamped 1.0.0 while the application reports 0.1.0.
```

`-- --app-version=…` overrides it. Once the project reaches `1.0.0` the
substitution stops happening by itself.

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
architecture without that architecture's `jmods`. So "build Apple Silicon and
Intel packages" means running `//app:jpackage` twice, on two machines or on
two CI runners:

```
runs-on: macos-14      # arm64
runs-on: macos-13      # x86_64
```

This is stated rather than worked around. A build that produced one package and
named it after both architectures would be worse than one that produces one and
says which.

A universal binary is a third option and is not taken: it would mean shipping
two JVMs in one bundle, roughly doubling a download that is already dominated by
the runtime image, to save a user one choice on a download page.

## What is not here

- **Preferences is intentionally narrow.** The modeless window has **Theme**
  and **Discovery** tabs, and packaged macOS integration routes its Preferences
  action to the same window as **Settings › Preferences…**. It is not yet a
  general surface for the limits in `docs/limits.md`.
- **No auto-update.** Out of scope for v1 (plan 1.4) and it would need a signing
  and hosting story the project does not have.
- **No installer for Linux or Windows.** The plan is macOS-first; jpackage would
  produce them and nobody has tested one.
