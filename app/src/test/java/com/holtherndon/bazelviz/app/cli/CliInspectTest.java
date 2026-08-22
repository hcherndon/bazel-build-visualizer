package com.holtherndon.bazelviz.app.cli;

import static com.holtherndon.bazelviz.app.cli.CliHarness.number;
import static com.holtherndon.bazelviz.app.cli.CliHarness.objects;
import static com.holtherndon.bazelviz.app.cli.CliHarness.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.JournalPayloadReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepDamage;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code bbv inspect} — the way an import is verified without the GUI.
 *
 * <p>The event-detail test does not take the command's word for the raw
 * location: it reads the journal itself at the segment, offset and length the
 * command printed and checks the bytes hash to what the command reported. That
 * is the Phase 1 "offsets are reproducible" criterion expressed as a round
 * trip — a row whose recorded location pointed anywhere else would fail here.
 */
class CliInspectTest {

    private static final int EVENT_COUNT = 120;

    @TempDir
    Path workspace;

    private final CliHarness cli = new CliHarness();

    private Path source;
    private Path sessionDirectory;

    @BeforeEach
    void importAFixture() throws IOException {
        source = workspace.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));
        CliHarness.Result imported = cli.run(
                "import", source.toString(),
                "--sessions-root", workspace.resolve("sessions").toString(),
                "--json");
        assertThat(imported.code()).as("stderr was:%n%s", imported.err()).isZero();
        sessionDirectory = CliHarness.sessionDirectory(imported.json());
    }

    @Test
    void listsAChronologicalPageOfEvents() {
        CliHarness.Result result = cli.run("inspect", sessionDirectory.toString());

        assertThat(result.code()).as("stderr was:%n%s", result.err()).isZero();
        assertThat(result.out())
                .contains("session ")
                .contains("state       READY")
                .contains("events      " + EVENT_COUNT)
                .contains("sequence")
                .contains("20 of 120 events shown")
                .contains("next page: bbv inspect");
    }

    @Test
    void eventsAreListedInArrivalOrderWithTheirPayloadTypeNamed() {
        CliHarness.Result result = cli.run(
                "inspect", sessionDirectory.toString(), "--events", "10", "--json");

        assertThat(result.code()).isZero();
        JsonObject page = result.json();
        assertThat(string(page, "view")).isEqualTo("events");
        assertThat(number(page, "eventCount")).isEqualTo(EVENT_COUNT);

        List<JsonObject> events = objects(page, "events");
        assertThat(events).hasSize(10);
        long previousId = Long.MIN_VALUE;
        long previousSequence = Long.MIN_VALUE;
        for (JsonObject event : events) {
            assertThat(number(event, "id")).isGreaterThan(previousId);
            assertThat(number(event, "sequence")).isGreaterThan(previousSequence);
            previousId = number(event, "id");
            previousSequence = number(event, "sequence");
            assertThat(string(event, "decodeStatus")).isEqualTo("OK");
            assertThat(string(event, "eventTypeName")).isNotBlank();
        }
        assertThat(string(events.get(0), "eventTypeName"))
                .as("the first event of any Bazel stream")
                .isEqualTo("started");
    }

    @Test
    void keysetPagingWalksTheWholeSessionWithoutRepeatingOrSkippingARow() {
        List<Long> seen = new ArrayList<>();
        long after = -1;
        for (int page = 0; page < 100 && seen.size() < EVENT_COUNT; page++) {
            List<String> arguments = new ArrayList<>(List.of(
                    "inspect", sessionDirectory.toString(), "--events", "25", "--json"));
            if (after >= 0) {
                arguments.add("--after");
                arguments.add(Long.toString(after));
            }
            CliHarness.Result result = cli.run(arguments.toArray(new String[0]));
            assertThat(result.code()).isZero();
            List<JsonObject> events = objects(result.json(), "events");
            if (events.isEmpty()) {
                break;
            }
            events.forEach(event -> seen.add(number(event, "id")));
            after = seen.get(seen.size() - 1);
        }
        assertThat(seen).hasSize(EVENT_COUNT).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void showsOneEventWithARawLocationThatReallyPointsAtItsBytes() throws IOException {
        long eventId = number(objects(
                cli.run("inspect", sessionDirectory.toString(), "--events", "1", "--json").json(),
                "events").get(0), "id");

        CliHarness.Result result = cli.run(
                "inspect", sessionDirectory.toString(), "--event", Long.toString(eventId), "--json");

        assertThat(result.code()).as("stderr was:%n%s", result.err()).isZero();
        JsonObject event = CliHarness.object(result.json(), "event");
        assertThat(number(event, "id")).isEqualTo(eventId);

        JsonObject raw = CliHarness.object(event, "raw");
        int segment = (int) number(raw, "segment");
        long offset = number(raw, "offset");
        int length = (int) number(raw, "length");
        assertThat(length).isPositive();

        byte[] payload = JournalPayloadReader.forSession(sessionDirectory)
                .read(new RawLocation(segment, offset, length));
        assertThat(payload).hasSize(length);
        assertThat(string(raw, "sha256"))
                .as("the digest the command printed is the digest of the bytes at that location")
                .isEqualTo(sha256(payload));
        assertThat(string(raw, "prefixHex"))
                .isEqualTo(HexFormat.of().formatHex(payload, 0, Math.min(48, payload.length)));
    }

    @Test
    void theTextDetailNamesTheJournalSegmentAndOffset() {
        long eventId = number(objects(
                cli.run("inspect", sessionDirectory.toString(), "--events", "1", "--json").json(),
                "events").get(0), "id");

        CliHarness.Result result = cli.run(
                "inspect", sessionDirectory.toString(), "--event", Long.toString(eventId));

        assertThat(result.code()).isZero();
        assertThat(result.out())
                .contains("event " + eventId)
                .contains("raw location       segment 0, offset ")
                .contains("raw file           ")
                .contains("raw sha256         ")
                .contains("raw bytes          ");
    }

    @Test
    void listsTheDiagnosticsOfADamagedImport() throws IOException {
        Path damaged = workspace.resolve("truncated.bep");
        BepDamage.truncateMidPayload(source, damaged);
        CliHarness.Result imported = cli.run(
                "import", damaged.toString(),
                "--sessions-root", workspace.resolve("sessions").toString(),
                "--json");
        assertThat(imported.code()).isEqualTo(ExitCode.PARTIAL.code());
        Path damagedSession = CliHarness.sessionDirectory(imported.json());

        CliHarness.Result result = cli.run(
                "inspect", damagedSession.toString(), "--diagnostics", "--json");

        assertThat(result.code()).isZero();
        List<JsonObject> diagnostics = objects(result.json(), "diagnostics");
        assertThat(diagnostics).isNotEmpty();
        assertThat(diagnostics)
                .filteredOn(entry -> string(entry, "code").equals("SOURCE_TRUNCATED"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(string(entry, "severity")).isEqualTo("WARNING");
                    assertThat(number(entry, "byteOffset")).isPositive();
                });

        CliHarness.Result text = cli.run("inspect", damagedSession.toString(), "--diagnostics");
        assertThat(text.code()).isZero();
        assertThat(text.out()).contains("SOURCE_TRUNCATED").contains("byte offset");
    }

    @Test
    void anEventIdThatIsNotThereFailsWithACountRatherThanAnEmptyPage() {
        CliHarness.Result result = cli.run(
                "inspect", sessionDirectory.toString(), "--event", "999999");

        assertThat(result.code()).isEqualTo(ExitCode.FAILED.code());
        assertThat(result.err())
                .contains("no event with id 999999")
                .contains("it holds 120 events");
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
