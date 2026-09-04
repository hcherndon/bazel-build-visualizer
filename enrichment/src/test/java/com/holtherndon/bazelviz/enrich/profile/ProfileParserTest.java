package com.holtherndon.bazelviz.enrich.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The profile parser, against profiles Bazel actually wrote.
 *
 * <p>The two fixtures are the two anchor cases: Bazel 6.5.0 publishes {@code profile_finish_ts}
 * holding a floored start, and Bazel 8.4.1 publishes {@code profile_start_ts} holding an exact one
 * (P1). Everything else about reading a profile is the same; that difference is a one-build-length
 * error waiting for anyone who reads the key by its name.
 */
final class ProfileParserTest {

  @Test
  @DisplayName("6.5.0's profile_finish_ts is read as a start, and carries its uncertainty")
  void finishTsIsReallyAFlooredStart() throws Exception {
    EnrichmentCommand.ProfileHeaderSeen header = headerOf("bazel650-finish-ts.json");

    ProfileAnchor anchor = header.anchor();
    assertThat(anchor.meaning()).isEqualTo(ProfileAnchor.Meaning.START_FLOORED_TO_SECOND);
    assertThat(anchor.sourceKey()).isEqualTo("profile_finish_ts");
    // Floored to the whole second, so profile and execution-log times
    // cannot be lined up more closely than that.
    assertThat(anchor.uncertaintyMicros()).isEqualTo(1_000_000L);
    assertThat(anchor.epochMicros() % 1_000_000L).isZero();
    assertThat(anchor.precisionCaveat())
        .hasValueSatisfying(caveat -> assertThat(caveat).contains("one second"));
  }

  @Test
  @DisplayName("8.4.1's profile_start_ts is exact and says so")
  void startTsIsExact() throws Exception {
    ProfileAnchor anchor = headerOf("bazel841-start-ts.json").anchor();

    assertThat(anchor.meaning()).isEqualTo(ProfileAnchor.Meaning.EXACT_START);
    assertThat(anchor.sourceKey()).isEqualTo("profile_start_ts");
    assertThat(anchor.uncertaintyMicros()).isZero();
    assertThat(anchor.precisionCaveat()).isEmpty();
  }

  @Test
  @DisplayName("the build id is read, because it is what proves the profile is this build's")
  void buildIdIsRead() throws Exception {
    // Measured equal to the BEP's started.uuid on all four versions (V1).
    assertThat(headerOf("bazel841-start-ts.json").buildId())
        .hasValueSatisfying(id -> assertThat(id).hasSize(36));
    assertThat(headerOf("bazel841-start-ts.json").bazelVersion()).isPresent();
  }

  @Test
  @DisplayName("6.5.0's seven phases and 8.4.1's five are both read as they are")
  void phaseSetsDifferByVersion() throws Exception {
    List<String> old = phaseNames("bazel650-finish-ts.json");
    List<String> recent = phaseNames("bazel841-start-ts.json");

    // Three of 6.5.0's phases were merged into one from 7.6.1 (P2). A UI
    // rendering a fixed list shows three empty rows on one of these.
    assertThat(old).hasSize(7).contains("Prepare for build", "Build artifacts");
    assertThat(recent)
        .hasSize(5)
        .contains("Load, analyze dependencies and build artifacts")
        .doesNotContain("Prepare for build");
  }

  @Test
  @DisplayName("Launch Blaze starts before zero and is kept there")
  void phasesCanStartNegative() throws Exception {
    List<EnrichmentCommand.PhaseMarkerSeen> phases = phases("bazel841-start-ts.json");

    // ts = 0 is "Initialize command" (P3). Clamping to zero would move the
    // launch into the build.
    assertThat(phases.getFirst().name()).isEqualTo("Launch Blaze");
    assertThat(phases.getFirst().startMicros()).isNegative();
    assertThat(phases)
        .extracting(EnrichmentCommand.PhaseMarkerSeen::ordinal)
        .containsExactly(0, 1, 2, 3, 4);
  }

  @Test
  @DisplayName("action spans keep the primary output, which is the join key")
  void actionSpansAreAttributable() throws Exception {
    List<EnrichmentCommand.SpanObserved> spans = spans("bazel841-start-ts.json");

    assertThat(spans).isNotEmpty();
    assertThat(spans)
        .allSatisfy(span -> assertThat(span.category()).isEqualTo("action processing"));
    // `out` is the same key BEP uses for action identity, and it is present
    // even on 6.5.0 (P4).
    assertThat(spans).anySatisfy(span -> assertThat(span.primaryOutput()).isPresent());
    assertThat(spans("bazel650-finish-ts.json"))
        .anySatisfy(span -> assertThat(span.primaryOutput()).isPresent());
  }

  @Test
  @DisplayName("an empty args.target reads as absent rather than as a target called \"\"")
  void emptyTargetIsAbsent() throws Exception {
    assertThat(spans("bazel841-start-ts.json"))
        .allSatisfy(
            span ->
                assertThat(span.targetLabel())
                    .satisfiesAnyOf(
                        label -> assertThat(label).isEmpty(),
                        label -> assertThat(label.orElseThrow()).isNotEmpty()));
  }

  @Test
  @DisplayName("Bazel's critical path is kept, in order, unjoined")
  void criticalPathIsKept() throws Exception {
    List<EnrichmentCommand.CriticalPathComponentSeen> path =
        of("bazel841-start-ts.json", EnrichmentCommand.CriticalPathComponentSeen.class);

    assertThat(path).isNotEmpty();
    assertThat(path).extracting(EnrichmentCommand.CriticalPathComponentSeen::ordinal).isSorted();
    // The only identifier is a progress message, which is why nothing joins
    // it to an action (P5).
    assertThat(path.getFirst().description()).contains("action '");
  }

  @Test
  @DisplayName("counter series and thread names are kept")
  void countersAndThreadsAreKept() throws Exception {
    assertThat(of("bazel841-start-ts.json", EnrichmentCommand.CounterSampled.class))
        .isNotEmpty()
        .extracting(EnrichmentCommand.CounterSampled::series)
        .contains("action count");
    // Without these a timeline labels its rows with bare integers (P6).
    assertThat(of("bazel841-start-ts.json", EnrichmentCommand.ThreadNamed.class)).isNotEmpty();
  }

  @Test
  @DisplayName("what is skipped is counted, so \"selected spans\" does not become \"some\"")
  void skippedEventsAreCounted() throws Exception {
    List<EnrichmentCommand> commands = new ArrayList<>();
    ProfileParser parser = new ProfileParser(commands::add);
    parse(parser, "bazel841-start-ts.json");

    assertThat(parser.keptEvents()).isPositive();
    assertThat(parser.skippedEvents()).isPositive();
    assertThat(parser.keptEvents() + parser.skippedEvents())
        .as("every event is either kept or counted as skipped")
        .isGreaterThan(1000);
  }

  @Test
  @DisplayName("a profile with no otherData reports no anchor rather than a zero one")
  void missingAnchorIsAbsent() throws Exception {
    List<EnrichmentCommand> commands = new ArrayList<>();
    new ProfileParser(commands::add)
        .parse(
            new StringReader(
                "{\"traceEvents\":[{\"ph\":\"X\",\"cat\":\"action processing\","
                    + "\"name\":\"x\",\"ts\":1,\"dur\":2}]}"));

    // No header event at all means no anchor claim is made. A zero would
    // place every span in 1970.
    assertThat(commands).noneMatch(EnrichmentCommand.ProfileHeaderSeen.class::isInstance);
  }

  @Test
  @DisplayName("otherData after traceEvents is still read")
  void headerOrderDoesNotMatter() throws Exception {
    List<EnrichmentCommand> commands = new ArrayList<>();
    new ProfileParser(commands::add)
        .parse(
            new StringReader(
                "{\"traceEvents\":[],\"otherData\":{\"profile_start_ts\":1787434091653}}"));

    assertThat(commands)
        .singleElement()
        .isInstanceOfSatisfying(
            EnrichmentCommand.ProfileHeaderSeen.class,
            header ->
                assertThat(header.anchor().meaning()).isEqualTo(ProfileAnchor.Meaning.EXACT_START));
  }

  @Test
  @DisplayName("an anchor that cannot fit in microseconds is a controlled malformed profile")
  void overflowingAnchorIsRefused() {
    ProfileParser parser = new ProfileParser(command -> {});

    assertThatThrownBy(
            () ->
                parser.parse(
                    new StringReader(
                        "{\"otherData\":{\"profile_start_ts\":9223372036854776},"
                            + "\"traceEvents\":[]}")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("profile_start_ts")
        .hasMessageContaining("overflows");
  }

  @Test
  @DisplayName("absolute profile placement declines overflow without losing valid epoch zero")
  void absolutePlacementUsesExactAddition() {
    assertThat(ProfileAnchor.exact(Long.MAX_VALUE, "profile_start_ts").absolute(1)).isEmpty();
    assertThat(ProfileAnchor.exact(0, "profile_start_ts").absolute(0)).hasValue(0L);
    assertThat(ProfileAnchor.absent().absolute(0)).isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private EnrichmentCommand.ProfileHeaderSeen headerOf(String fixture) throws IOException {
    return of(fixture, EnrichmentCommand.ProfileHeaderSeen.class).getFirst();
  }

  private List<String> phaseNames(String fixture) throws IOException {
    return phases(fixture).stream().map(EnrichmentCommand.PhaseMarkerSeen::name).toList();
  }

  private List<EnrichmentCommand.PhaseMarkerSeen> phases(String fixture) throws IOException {
    return of(fixture, EnrichmentCommand.PhaseMarkerSeen.class);
  }

  private List<EnrichmentCommand.SpanObserved> spans(String fixture) throws IOException {
    return of(fixture, EnrichmentCommand.SpanObserved.class);
  }

  private <T extends EnrichmentCommand> List<T> of(String fixture, Class<T> type)
      throws IOException {
    List<EnrichmentCommand> commands = new ArrayList<>();
    parse(new ProfileParser(commands::add), fixture);
    return commands.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private void parse(ProfileParser parser, String fixture) throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/profile/" + fixture)) {
      if (in == null) {
        throw new IOException("missing fixture " + fixture);
      }
      parser.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }
}
