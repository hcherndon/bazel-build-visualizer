# Troubleshooting

Grows as the tool grows; Phase 0 covers build/dev issues only. App-runtime
troubleshooting (capture failures, session recovery) arrives with Phase 2+.

## Build

- **First build is slow / downloads a JDK.** Expected: the Java 21 toolchain
  is auto-provisioned by the foojay resolver (ADR-002). Later builds reuse
  it from `~/.gradle/jdks/`.
- **"Timeout waiting to lock" / daemon lock errors.** Another Gradle
  invocation (IDE sync, second terminal) holds the lock. Wait and retry;
  do not delete lock files while a daemon runs. `./gradlew --status` lists
  daemons, `./gradlew --stop` stops them.
- **Dependency locking failures** after changing a dependency: regenerate
  lockfiles with `./gradlew resolveAndLockAll --write-locks
  --no-configuration-cache` and commit the lockfile diff with the change.
- **Configuration-cache problems** after editing build scripts: retry once
  (the cache invalidates itself); a persistent report lands under
  `build/reports/configuration-cache/`.
- **Stale/odd behavior**: `./gradlew --stop` then rebuild before deeper
  debugging — a long-lived daemon with old classpaths explains most
  mysteries.

## Where things live

- Test reports: `<module>/build/reports/tests/test/index.html`.
- App logs: console via logback (`app/src/main/resources/logback.xml`);
  per-session capture logs will live in the session directory
  (`logs/capture.log`, docs/session-format.md) from Phase 1.
- Headless app smoke run: `./gradlew :app:run -Dbbv.smoke=true` opens the
  window and exits after two seconds.
- CI failures: the workflow uploads `**/build/reports/tests` as an artifact
  on failure — download it from the run page rather than re-deriving
  locally.
