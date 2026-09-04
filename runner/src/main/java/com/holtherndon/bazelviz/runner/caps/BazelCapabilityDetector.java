package com.holtherndon.bazelviz.runner.caps;

import com.google.devtools.build.lib.runtime.commands.proto.BazelFlagsProto.FlagCollection;
import com.google.devtools.build.lib.runtime.commands.proto.BazelFlagsProto.FlagInfo;
import com.google.protobuf.InvalidProtocolBufferException;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.proc.Subprocess;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks a Bazel binary what it supports (plan 7.1 {@code BazelCapabilityDetector}).
 *
 * <h2>The probe runs outside the user's workspace, on purpose</h2>
 *
 * <p>This is the single most important decision in this class, and it was settled by experiment
 * rather than reasoning. {@code bazel help flags-as-proto} produces byte-identical output inside
 * and outside a workspace, but inside one it contacts the Bazel server — which means it takes the
 * server's command lock, so the probe blocks for the entire duration of a build the user has
 * running, and, if the startup options differ from theirs by so much as one flag, it <em>restarts
 * their server and discards their analysis cache</em>. Reaching for {@code --ignore_all_rc_files}
 * to make the probe deterministic is what guarantees that difference. Outside a workspace the
 * command runs in batch mode: no server, no lock, no cache to lose, and about 0.7 seconds.
 *
 * <p>Bazelisk complicates this, because outside a workspace it has no {@code .bazelversion} to read
 * and would happily probe a different Bazel than the build will use. So the version discovered by
 * {@link com.holtherndon.bazelviz.runner.exec.BazelExecutableResolver} is passed back in through
 * {@code USE_BAZEL_VERSION}, pinning the probe to the same binary without needing the workspace.
 *
 * <h2>Absent is not false</h2>
 *
 * <p>{@code FlagInfo}'s later fields arrived over time: Bazel 6.5 populates fields 1 through 9
 * only, 7.6 adds {@code requires_value}, and {@code default_value}, {@code type_converter} and
 * {@code enum_values} appear from 8.4. These are proto2 optional fields, so a getter returns {@code
 * ""} or an empty list for an absent one — indistinguishable from a real empty value. Every read
 * here goes through {@code hasField}, and what is absent becomes an empty {@link Optional} rather
 * than a fact about the binary.
 *
 * <h2>A failed probe is not a negative answer</h2>
 *
 * <p>A non-zero exit, an empty stdout or a timeout produces {@link BazelCapabilities#unprobed},
 * where every capability is {@link CapabilityStatus#UNKNOWN}. A user's {@code .bazelrc} containing
 * one unrecognized flag makes the probe exit 2 with no output on every version tested; reading that
 * as "your Bazel supports nothing" would silently disable all instrumentation for a configuration
 * error.
 */
public final class BazelCapabilityDetector {

  private static final Logger log = LoggerFactory.getLogger(BazelCapabilityDetector.class);

  /**
   * Generous, because the fallback is a worse answer rather than a slower one, and a first run may
   * have to start a JVM on a cold filesystem.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /**
   * Matches a flag in {@code bazel help build --long} output. Only used when the structured probe
   * failed, and only able to establish that a flag exists — never whether it takes a value.
   */
  private static final Pattern HELP_FLAG = Pattern.compile("(?m)^\\s*--(?:\\[no])?([a-zA-Z0-9_]+)");

  private final Duration timeout;
  private final CommandExecutor executor;
  private final Map<CacheKey, BazelCapabilities> cache = new ConcurrentHashMap<>();

  /**
   * The cache key from plan 7.1 rule 3: the resolved executable, the version it reports, and the
   * startup options that could change the answer. All three, because one binary can be several
   * Bazels and one Bazel can present different flags under different startup options.
   */
  private record CacheKey(Path executable, String version, List<String> startupArgs) {
    CacheKey {
      startupArgs = List.copyOf(startupArgs);
    }
  }

  public BazelCapabilityDetector() {
    this.executor = null;
    this.timeout = DEFAULT_TIMEOUT;
  }

  public BazelCapabilityDetector(Duration timeout) {
    this.executor = null;
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  /** Uses the executor's machine for scratch creation and every probe. */
  public BazelCapabilityDetector(CommandExecutor executor) {
    this(Objects.requireNonNull(executor, "executor"), DEFAULT_TIMEOUT);
  }

  public BazelCapabilityDetector(CommandExecutor executor, Duration timeout) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  /** Detects, or returns a cached answer for the same binary and options. */
  public BazelCapabilities detect(BazelExecutable executable, List<String> startupArgs) {
    Objects.requireNonNull(executable, "executable");
    CacheKey key =
        new CacheKey(
            executable.resolved(), executable.effectiveVersion().orElse("unknown"), startupArgs);
    return cache.computeIfAbsent(key, ignored -> probe(executable, startupArgs));
  }

  /** Forgets everything, so a reinstalled Bazel is re-probed. */
  public void invalidate() {
    cache.clear();
  }

  private BazelCapabilities probe(BazelExecutable executable, List<String> startupArgs) {
    if (executor != null) {
      return probeThroughExecutor(executable, startupArgs);
    }
    Path scratch;
    try {
      scratch = Files.createTempDirectory("bbv-bazel-probe");
    } catch (IOException failure) {
      return BazelCapabilities.unprobed(
          "could not create a scratch directory to probe from: " + failure);
    }
    try {
      Optional<BazelCapabilities> structured =
          probeFlagsProto(executable, startupArgs, scratch.toString());
      if (structured.isPresent()) {
        return structured.get();
      }
      return probeHelpText(executable, startupArgs, scratch.toString());
    } finally {
      try {
        Files.deleteIfExists(scratch);
      } catch (IOException ignored) {
        // A leftover empty temp directory is not worth reporting; the
        // operating system reclaims it.
      }
    }
  }

  private BazelCapabilities probeThroughExecutor(
      BazelExecutable executable, List<String> startupArgs) {
    CommandResult created;
    try {
      created =
          executor.run(
              CommandRequest.of(
                  List.of("/usr/bin/mktemp", "-d", "/tmp/bbv-bazel-probe.XXXXXXXX"), (String) null),
              timeout);
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return BazelCapabilities.unprobed(
          "could not create a scratch directory on the execution host: " + failure);
    }
    String scratch = created.stdout().strip();
    if (!created.isSuccess()
        || !scratch.startsWith("/")
        || scratch.contains("\n")
        || scratch.contains("\r")) {
      return BazelCapabilities.unprobed(
          "could not create a scratch directory on the execution host: " + created.failureDetail());
    }
    try {
      Optional<BazelCapabilities> structured = probeFlagsProto(executable, startupArgs, scratch);
      return structured.orElseGet(() -> probeHelpText(executable, startupArgs, scratch));
    } finally {
      try {
        executor.run(
            CommandRequest.of(List.of("/bin/rmdir", "--", scratch), (String) null), timeout);
      } catch (IOException | InterruptedException cleanupFailure) {
        if (cleanupFailure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        log.debug(
            "could not remove remote capability scratch directory; failureType={}",
            cleanupFailure.getClass().getSimpleName());
      }
    }
  }

  // ------------------------------------------------------- structured probe

  private Optional<BazelCapabilities> probeFlagsProto(
      BazelExecutable executable, List<String> startupArgs, String scratch) {
    List<String> argv = new ArrayList<>();
    argv.add(executable.resolved().toString());
    // Startup options come before the command. --ignore_all_rc_files is
    // safe here and only here: outside a workspace there is no server whose
    // startup options this could contradict, and the flag table is provably
    // identical with and without rc files.
    argv.add("--ignore_all_rc_files");
    argv.addAll(startupArgs);
    argv.add("help");
    argv.add("flags-as-proto");

    ProbeResult result;
    try {
      result = runProbe(argv, scratch, pinnedVersion(executable));
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.debug(
          "flags-as-proto probe could not run; failureType={}", failure.getClass().getSimpleName());
      return Optional.empty();
    }

    // Deliberately checked separately from the exit code: a binary can exit
    // zero and print nothing, and decoding "" would yield an empty flag
    // table that reads as a successful probe of a Bazel with no flags.
    String encoded = result.stdout().strip();
    if (!result.isSuccess() || encoded.isEmpty()) {
      log.debug(
          "flags-as-proto probe was inconclusive exitCode={} timedOut={}" + " outputPresent={}",
          result.exitCode(),
          result.timedOut(),
          !encoded.isEmpty());
      return Optional.empty();
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException notBase64) {
      log.debug(
          "flags-as-proto output was not base64; failureType={}",
          notBase64.getClass().getSimpleName());
      return Optional.empty();
    }

    FlagCollection flags;
    try {
      flags = FlagCollection.parseFrom(decoded);
    } catch (InvalidProtocolBufferException malformed) {
      log.debug(
          "flags-as-proto output was not a FlagCollection; failureType={}",
          malformed.getClass().getSimpleName());
      return Optional.empty();
    }

    Map<String, FlagSpec> specs = new LinkedHashMap<>();
    for (FlagInfo info : flags.getFlagInfosList()) {
      toSpec(info).ifPresent(spec -> specs.put(spec.name(), spec));
    }
    if (specs.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        BazelCapabilities.fromFlags(
            executable.versionOutput(),
            executable.effectiveVersion(),
            BazelCapabilities.DetectionMethod.FLAGS_PROTO,
            specs,
            warningsFrom(result)));
  }

  /**
   * Converts one {@code FlagInfo}, or nothing for a flag that cannot appear after a command.
   *
   * <p>A flag whose only command is {@code startup} is a startup option. Recording it as a
   * capability of {@code build} would let the planner place it after the subcommand, which Bazel
   * rejects outright — a plan that cannot run at all rather than one that captures less.
   */
  private static Optional<FlagSpec> toSpec(FlagInfo info) {
    if (!info.hasName() || info.getName().isEmpty()) {
      return Optional.empty();
    }
    Set<String> commands = Set.copyOf(info.getCommandsList());
    if (commands.equals(Set.of("startup"))) {
      return Optional.empty();
    }
    return Optional.of(
        new FlagSpec(
            info.getName(),
            commands,
            info.hasHasNegativeFlag() && info.getHasNegativeFlag(),
            info.hasAllowsMultiple() && info.getAllowsMultiple(),
            // hasField, not the getter: an absent optional bool reads as
            // false, and "this flag takes no value" is a different claim
            // from "this Bazel does not say".
            info.hasRequiresValue() ? Optional.of(info.getRequiresValue()) : Optional.empty(),
            info.hasDefaultValue() ? Optional.of(info.getDefaultValue()) : Optional.empty(),
            List.copyOf(info.getEnumValuesList())));
  }

  // ------------------------------------------------------- text-scrape probe

  /**
   * The fallback: scrape {@code bazel help <command> --long}.
   *
   * <p>Weaker in a way that matters. It establishes that a flag exists and nothing else, so every
   * {@link FlagSpec} it produces reports {@code requiresValue} and {@code defaultValue} as absent
   * rather than guessing from the layout of a help page that is free to change.
   */
  private BazelCapabilities probeHelpText(
      BazelExecutable executable, List<String> startupArgs, String scratch) {
    Map<String, FlagSpec> specs = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    Map<String, Set<String>> commandsByFlag = new HashMap<>();

    // The commands a user is likely to launch, plus the two the auxiliary
    // queries use. A command missing from this list gets an empty command
    // set, which the planner must not read as "this command publishes no
    // events" — see isInstrumentable.
    for (String command : List.of("build", "test", "run", "coverage", "aquery", "cquery")) {
      List<String> argv = new ArrayList<>();
      argv.add(executable.resolved().toString());
      argv.add("--ignore_all_rc_files");
      argv.addAll(startupArgs);
      argv.add("help");
      argv.add(command);
      argv.add("--long");

      ProbeResult result;
      try {
        result = runProbe(argv, scratch, pinnedVersion(executable));
      } catch (IOException | InterruptedException failure) {
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        warnings.add("could not run 'help " + command + "': " + failure);
        continue;
      }
      if (!result.isSuccess()) {
        warnings.add("'help " + command + "' failed: " + result.failureDetail());
        continue;
      }
      Matcher matcher = HELP_FLAG.matcher(result.stdout());
      while (matcher.find()) {
        commandsByFlag
            .computeIfAbsent(matcher.group(1), ignored -> new LinkedHashSet<>())
            .add(command);
      }
    }

    if (commandsByFlag.isEmpty()) {
      return BazelCapabilities.unprobed(
          "neither 'help flags-as-proto' nor 'help <command> --long' produced a flag list"
              + (warnings.isEmpty() ? "" : ": " + String.join("; ", warnings)));
    }
    commandsByFlag.forEach((name, commands) -> specs.put(name, FlagSpec.of(name, commands)));
    warnings.add(
        "capabilities came from scraping help text, so flag value requirements are unknown");
    return BazelCapabilities.fromFlags(
        executable.versionOutput(),
        executable.effectiveVersion(),
        BazelCapabilities.DetectionMethod.HELP_TEXT,
        specs,
        warnings);
  }

  // ------------------------------------------------------------- internals

  /**
   * Pins Bazelisk to the version the workspace resolved to.
   *
   * <p>Without this, probing from a scratch directory asks Bazelisk for whatever it considers
   * current — downloading a Bazel the user does not have and reporting its flags as theirs.
   */
  private static Map<String, String> pinnedVersion(BazelExecutable executable) {
    if (!executable.isBazelisk()) {
      return Map.of();
    }
    return executable
        .bazelVersion()
        .map(version -> Map.of("USE_BAZEL_VERSION", version))
        .orElseGet(Map::of);
  }

  private static List<String> warningsFrom(ProbeResult result) {
    String stderr = result.stderr().strip();
    if (stderr.isEmpty()) {
      return List.of();
    }
    // Kept, but as one entry: Bazel writes a batch-mode notice and JVM
    // warnings here on a perfectly successful probe, and turning each line
    // into a user-facing warning would bury the ones that matter.
    return List.of("the probe wrote to stderr: " + stderr.lines().findFirst().orElse(""));
  }

  private ProbeResult runProbe(List<String> argv, String scratch, Map<String, String> environment)
      throws IOException, InterruptedException {
    if (executor == null) {
      Subprocess.Result result = Subprocess.run(argv, Path.of(scratch), environment, timeout);
      return new ProbeResult(
          result.exitCode(),
          result.stdout(),
          result.stderr(),
          result.timedOut(),
          result.outputTruncated());
    }
    Map<String, Optional<String>> overrides = new LinkedHashMap<>();
    environment.forEach((name, value) -> overrides.put(name, Optional.of(value)));
    CommandResult result =
        executor.run(
            new CommandRequest(
                argv, Optional.of(scratch), overrides, RuntimeEnvironment.INHERIT_ALL, false),
            timeout);
    return new ProbeResult(
        result.exitCode(), result.stdout(), result.stderr(), result.timedOut(), false);
  }

  private record ProbeResult(
      int exitCode, String stdout, String stderr, boolean timedOut, boolean outputTruncated) {
    boolean isSuccess() {
      return !timedOut && !outputTruncated && exitCode == 0;
    }

    String failureDetail() {
      if (timedOut) {
        return "the command did not finish in time";
      }
      if (outputTruncated) {
        return "the command's probe output exceeded its bounded capture limit";
      }
      String text = stderr.isBlank() ? stdout : stderr;
      return text.isBlank()
          ? "exit code " + exitCode
          : "exit code " + exitCode + ": " + text.strip();
    }
  }

  /** The bytes of a {@code flags-as-proto} payload, decoded. For tests. */
  static Optional<FlagCollection> decode(String base64) {
    try {
      return Optional.of(FlagCollection.parseFrom(Base64.getDecoder().decode(base64.strip())));
    } catch (IllegalArgumentException | InvalidProtocolBufferException unreadable) {
      return Optional.empty();
    }
  }

  /** UTF-8, because that is what Bazel writes and what base64 decoding needs. */
  static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
