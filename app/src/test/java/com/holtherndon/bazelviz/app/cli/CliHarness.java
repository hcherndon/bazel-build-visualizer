package com.holtherndon.bazelviz.app.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Drives the commands in-process with captured streams.
 *
 * <p>In-process rather than by shelling out, for three reasons that all matter here: a forked JVM
 * would hide the exception behind an exit code, it would double every test's runtime with a second
 * JVM start, and it could not inject a fake shutdown-hook registry — which is the only way to test
 * the Ctrl-C path without actually killing the test JVM.
 *
 * <p>The default sessions root deliberately throws. Every test passes {@code --sessions-root}, and
 * a test that forgot would otherwise quietly write a session into the developer's real
 * application-support directory; failing loudly is better than a test that pollutes a home
 * directory.
 */
final class CliHarness {

  static final String APP_VERSION = "0.1.0-test";

  private final RecordingHooks hooks = new RecordingHooks();

  /** What one command line did. */
  record Result(ExitCode exit, String out, String err) {

    int code() {
      return exit.code();
    }

    /** The stdout of a {@code --json} run, parsed. */
    JsonObject json() {
      JsonValue parsed = JsonReader.parse(out);
      assertThat(parsed).isInstanceOf(JsonObject.class);
      return (JsonObject) parsed;
    }
  }

  RecordingHooks hooks() {
    return hooks;
  }

  Result run(String... args) {
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    ExitCode exit;
    try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
      CliContext context =
          new CliContext(
              out,
              err,
              APP_VERSION,
              () -> {
                throw new AssertionError(
                    "a test reached the real application-support"
                        + " directory; pass --sessions-root");
              },
              hooks,
              false);
      exit = CliMain.run(List.of(args), context);
    }
    return new Result(
        exit, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
  }

  /**
   * A stand-in for the JVM's shutdown hooks.
   *
   * <p>{@link #fireOnRegister} makes the hook run the moment the command installs it, which is how
   * the Ctrl-C path is tested without a signal and without a race: the cancellation flag is raised
   * before the importer reads its first record, so the run always cancels and the test never
   * depends on beating the import to the finish line.
   */
  static final class RecordingHooks implements CancellationGuard.HookRegistry {

    private final List<Thread> registered = new CopyOnWriteArrayList<>();
    private final List<Thread> removed = new CopyOnWriteArrayList<>();
    private volatile boolean fireOnRegister;

    @Override
    public void addShutdownHook(Thread hook) {
      registered.add(hook);
      if (fireOnRegister) {
        hook.start();
        awaitFlagRaised(hook);
      }
    }

    /**
     * Blocks until the freshly started hook has reached its wait on the command, which it can only
     * do after raising the cancellation flag.
     *
     * <p>Without this the test would be racing a thread start against a small import, and on a fast
     * machine the import could finish first and report COMPLETE. The wait costs microseconds and
     * turns a probabilistic test into a deterministic one.
     */
    private static void awaitFlagRaised(Thread hook) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        Thread.State state = hook.getState();
        if (state == Thread.State.WAITING
            || state == Thread.State.TIMED_WAITING
            || state == Thread.State.TERMINATED) {
          return;
        }
        Thread.onSpinWait();
      }
      throw new AssertionError("the shutdown hook never reached its wait");
    }

    @Override
    public void removeShutdownHook(Thread hook) {
      removed.add(hook);
    }

    void fireOnRegister(boolean value) {
      fireOnRegister = value;
    }

    List<Thread> registered() {
      return List.copyOf(registered);
    }

    List<Thread> removed() {
      return List.copyOf(removed);
    }
  }

  // ------------------------------------------------------------ JSON helpers

  static String string(JsonObject object, String key) {
    JsonValue value =
        object
            .member(key)
            .orElseThrow(
                () ->
                    new AssertionError("no member '" + key + "' in " + object.members().keySet()));
    assertThat(value).isInstanceOf(JsonString.class);
    return ((JsonString) value).value();
  }

  static Optional<String> optionalString(JsonObject object, String key) {
    return object.member(key).map(value -> ((JsonString) value).value());
  }

  static long number(JsonObject object, String key) {
    JsonValue value =
        object
            .member(key)
            .orElseThrow(
                () ->
                    new AssertionError("no member '" + key + "' in " + object.members().keySet()));
    assertThat(value).isInstanceOf(JsonNumber.class);
    return ((JsonNumber) value).asLong();
  }

  static boolean bool(JsonObject object, String key) {
    JsonValue value =
        object
            .member(key)
            .orElseThrow(
                () ->
                    new AssertionError("no member '" + key + "' in " + object.members().keySet()));
    assertThat(value).isInstanceOf(JsonValue.JsonBool.class);
    return ((JsonValue.JsonBool) value).value();
  }

  static boolean isNull(JsonObject object, String key) {
    assertThat(object.hasKey(key)).as("member '%s' is present at all", key).isTrue();
    return object.member(key).isEmpty();
  }

  static JsonObject object(JsonObject parent, String key) {
    return (JsonObject)
        parent.member(key).orElseThrow(() -> new AssertionError("no object member '" + key + "'"));
  }

  static List<JsonObject> objects(JsonObject parent, String key) {
    JsonValue value =
        parent.member(key).orElseThrow(() -> new AssertionError("no array member '" + key + "'"));
    assertThat(value).isInstanceOf(JsonArray.class);
    List<JsonObject> elements = new ArrayList<>();
    for (JsonValue element : ((JsonArray) value).elements()) {
      elements.add((JsonObject) element);
    }
    return elements;
  }

  static Path sessionDirectory(JsonObject summary) {
    return Path.of(string(summary, "sessionDirectory"));
  }
}
