package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.bepcodec.BepEventDecoder;
import com.holtherndon.bazelviz.bepcodec.BesEnvelope;
import com.holtherndon.bazelviz.bepcodec.BesEnvelopeDecoder;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Phase 2 exit criteria that can only be settled by a real Bazel:
 * "a real Bazel 6-9 fixture build can be launched" and "events arrive through
 * the embedded BES".
 *
 * <p>Skipped, never weakened, when this machine has no Bazel. A version of this
 * test that fell back to a scripted fake would report that real Bazel works
 * when nothing had checked, which is worse than reporting nothing.
 *
 * <p>Tagged {@code real-bazel}: these take tens of seconds each because Bazel
 * starts a server, so {@code bazel test //...} runs them but a tight edit loop
 * can exclude them with {@code --test_tag_filters=-bazel-sweep,-real-bazel}.
 */
@Tag("real-bazel")
class RealBazelBesTest {

    /**
     * The versions the project targets. Each runs a full build, so this is the
     * slowest test in the suite by a wide margin — and it is the only one that
     * proves the wire contract against the software the tool exists to observe.
     */
    @ParameterizedTest(name = "Bazel {0}")
    @ValueSource(strings = {"6.5.0", "7.6.1", "8.4.1", "9.2.0"})
    @DisplayName("a real Bazel build publishes its event stream to the embedded BES server")
    void realBuildPublishesToEmbeddedBes(String version, @TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 5);
        RecordingSink sink = new RecordingSink();
        try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
            BesEndpoint endpoint = server.start();

            BuildResult build = runBazel(bazel.orElseThrow(), version, workspace.root(), List.of(
                    "build",
                    "--bes_backend=" + endpoint.besBackendUri(),
                    "--build_event_publish_all_actions",
                    "//..."));

            assertThat(build.exitCode())
                    .describedAs("bazel %s build failed:%n%s", version, build.output())
                    .isZero();

            assertThat(sink.awaitStreamEnd(30_000))
                    .describedAs("the tool event stream never ended; output was:%n%s", build.output())
                    .isTrue();

            // Exit criterion: events arrive through the embedded BES.
            List<RawBesEvent> toolEvents = sink.events().stream()
                    .filter(event -> event.sourceKind() == SourceKind.BES_ENVELOPE)
                    .toList();
            assertThat(toolEvents).isNotEmpty();

            // Exit criterion: no accepted event is silently dropped. The stream
            // has to end having acknowledged everything it received, with no
            // gap and nothing still buffered.
            BesStreamState state = sink.ended().get(0);
            assertThat(state.completion()).isEqualTo(BesStreamState.Completion.FINISHED);
            assertThat(state.hasGap()).isFalse();
            assertThat(state.highestAcknowledged()).isEqualTo(state.highestReceived());
            assertThat(state.eventsAccepted()).isEqualTo(toolEvents.size());

            // BES sequence numbers are consecutive from 1, so the count and the
            // watermark agree only if nothing was lost in between.
            assertThat(state.highestReceived()).isEqualTo(toolEvents.size());

            // The envelopes really do carry decodable BEP events, and the first
            // one is the build's start.
            BesEnvelopeDecoder envelopes = new BesEnvelopeDecoder(64 * 1024 * 1024);
            BepEventDecoder events = BepEventDecoder.withDefaults();
            int decodedBuildEvents = 0;
            boolean sawStarted = false;
            for (RawBesEvent raw : toolEvents) {
                BesEnvelopeDecoder.Result result =
                        envelopes.decodeToolEvent(raw.payload(), 0, raw.payloadLength());
                assertThat(result.isFailed())
                        .describedAs("envelope %d did not parse: %s", raw.sequence(), result.failureDetail())
                        .isFalse();
                BesEnvelope envelope = result.envelope().orElseThrow();
                if (!envelope.kind().carriesBuildEvent()) {
                    continue;
                }
                byte[] inner = envelope.bazelEventBytes().orElseThrow().toByteArray();
                var decoded = events.decode(inner, 0, inner.length);
                assertThat(decoded.isFailed())
                        .describedAs("event %d did not decode: %s", raw.sequence(), decoded.failureDetail())
                        .isFalse();
                decodedBuildEvents++;
                if (decoded.requireEvent().hasStarted()) {
                    sawStarted = true;
                }
            }
            assertThat(decodedBuildEvents).isPositive();
            assertThat(sawStarted)
                    .describedAs("no BuildStarted event arrived, so the stream was not a whole build")
                    .isTrue();

            // The last envelope is the stream terminator, and it is the one
            // envelope that legitimately carries no build event.
            RawBesEvent last = toolEvents.get(toolEvents.size() - 1);
            BesEnvelope terminator = envelopes
                    .decodeToolEvent(last.payload(), 0, last.payloadLength())
                    .envelope()
                    .orElseThrow();
            assertThat(terminator.kind())
                    .isEqualTo(BesEnvelope.Kind.COMPONENT_STREAM_FINISHED);
        } finally {
            sink.stop();
            shutdownBazel(bazel.orElseThrow(), version, workspace.root());
        }
    }

    // ---------------------------------------------------------------- driving

    private record BuildResult(int exitCode, String output) {}

    private static BuildResult runBazel(Path bazel, String version, Path workspace, List<String> args)
            throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(bazel.toString());
        // Keep the server from lingering after the test, and keep this run from
        // reading a developer's ~/.bazelrc, which may set --bes_backend itself.
        argv.add("--max_idle_secs=20");
        argv.add("--nohome_rc");
        argv.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        builder.environment().put(BazelBinary.VERSION_ENV, version);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(10, TimeUnit.MINUTES);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("bazel " + version + " did not finish within ten minutes");
        }
        return new BuildResult(process.exitValue(), output);
    }

    private static void shutdownBazel(Path bazel, String version, Path workspace) {
        if (!Files.isDirectory(workspace)) {
            return;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(bazel.toString(), "shutdown")
                    .directory(workspace.toFile())
                    .redirectErrorStream(true);
            builder.environment().put(BazelBinary.VERSION_ENV, version);
            Process process = builder.start();
            process.getInputStream().readAllBytes();
            process.waitFor(60, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            // Best effort. A leftover idle server times out on its own, and
            // failing the test over cleanup would hide the result it produced.
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
