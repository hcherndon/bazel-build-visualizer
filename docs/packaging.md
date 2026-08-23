# Packaging for macOS

What `./gradlew :app:jpackage` produces, what it deliberately does not do, and
where the credentials live.

## What is built

```
./gradlew :app:jpackage                       # an .app image (the default)
./gradlew :app:jpackage -Pbbv.packageType=dmg # a disk image
./gradlew :app:notarize -Pbbv.packageType=dmg # submit and staple
```

jpackage runs against the `installDist` layout — the directory the `application`
plugin already produces — rather than a fat jar. A desktop application is never
on anybody else's classpath, so flattening the jars buys nothing and loses the
one-to-one mapping between a module and a file in `Contents/app`.

The image carries the JVM options the launcher scripts carry, which is not
cosmetic: `--enable-native-access=ALL-UNNAMED` is load-bearing, because
sqlite-jdbc and FlatLaf both call `System::load` and a future JDK makes that an
error rather than a warning (see `bbv.java-common.gradle.kts`).

Verified on this machine: the image builds, its `Info.plist` declares the file
association, its `.cfg` carries all four JVM options, and the packaged launcher
runs the CLI subcommands.

## The version stamped on the package is not the product's version

macOS refuses a `CFBundleVersion` whose first component is zero, and this
project is `0.1.0`. `CFBundleVersion` is a build-ordering number rather than an
identity — the version a user reads is `AppInfo.VERSION`, in the About dialog —
so the task stamps `1.0.0` and **says so on every run**:

```
jpackage: macOS will not accept an app-version starting with zero, so this
package is stamped 1.0.0 while the application reports 0.1.0.
```

`-Pbbv.packageVersion=…` overrides it. Once the project reaches `1.0.0` the
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

Neither credential is in this repository, and neither is read from
`gradle.properties` — that file is committed.

| Variable | What it is | Read by |
|---|---|---|
| `BBV_MAC_SIGNING_IDENTITY` | the Developer ID name | `:app:jpackage` |
| `BBV_MAC_NOTARY_PROFILE` | a keychain profile **name** | `:app:notarize` |

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
Intel packages" means running `:app:jpackage` twice, on two machines or on two
CI runners:

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

- **No Preferences menu item.** `APP_PREFERENCES` is available and deliberately
  unused: there is no settings screen yet, and a menu item that opens an empty
  dialog is worse than one that is not there. It goes in with the settings
  screen.
- **No auto-update.** Out of scope for v1 (plan 1.4) and it would need a signing
  and hosting story the project does not have.
- **No installer for Linux or Windows.** The plan is macOS-first; jpackage would
  produce them and nobody has tested one.
