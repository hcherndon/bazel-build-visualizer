package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.EventRow;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading raw bytes back by their stored location.
 *
 * <p>The happy path is asserted at scale by {@code BepImporterImportTest}; what
 * matters here is the unhappy path. A row whose {@code raw_offset} does not
 * point at the frame it claims must fail loudly, because the alternative is
 * handing a decoder bytes from the middle of some other event and letting it
 * produce a plausible, wrong answer.
 */
class JournalPayloadReaderTest {

    @Test
    @DisplayName("a location that is not a frame boundary is rejected, not decoded")
    void rejectsAnOffsetThatIsNotAFrame(@TempDir Path temporary) throws Exception {
        ImportResult result = importFixture(temporary, 40);
        List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
        RawLocation good = rows.get(10).rawLocation();
        JournalPayloadReader reader = JournalPayloadReader.forSession(result.sessionRoot());

        assertThat(reader.read(good)).hasSize(good.length());

        RawLocation offByOne = new RawLocation(good.segment(), good.offset() + 1, good.length());
        assertThatThrownBy(() -> reader.read(offByOne))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no journal frame");
    }

    @Test
    @DisplayName("the frame header comes back with the payload, for the raw-inspector pane")
    void readsTheWholeFrame(@TempDir Path temporary) throws Exception {
        ImportResult result = importFixture(temporary, 30);
        List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
        EventRow row = rows.get(7);

        var frame = JournalPayloadReader.forSession(result.sessionRoot()).readFrame(row.rawLocation());

        assertThat(frame.header().sequence())
                .as("the frame's own ordinal matches the row's sequence")
                .isEqualTo(row.sequence());
        assertThat(frame.header().payloadLength()).isEqualTo(row.rawLength());
        assertThat(frame.header().sourceKind())
                .isEqualTo(com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind.BEP_BINARY);
        assertThat(frame.header().receiveMicros()).isEqualTo(row.receiveMicros());
        assertThat(frame.requirePayload()).hasSize(row.rawLength());
    }

    @Test
    @DisplayName("a stored length that disagrees with the frame is reported, not trimmed")
    void rejectsALengthThatDisagrees(@TempDir Path temporary) throws Exception {
        ImportResult result = importFixture(temporary, 40);
        List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
        RawLocation good = rows.get(5).rawLocation();
        JournalPayloadReader reader = JournalPayloadReader.forSession(result.sessionRoot());

        RawLocation shortened = new RawLocation(good.segment(), good.offset(), good.length() - 1);
        assertThatThrownBy(() -> reader.read(shortened))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("payload bytes");
    }

    @Test
    @DisplayName("an offset inside the segment header names the header rather than guessing")
    void rejectsAnOffsetInsideTheHeader(@TempDir Path temporary) throws Exception {
        ImportResult result = importFixture(temporary, 20);
        JournalPayloadReader reader = JournalPayloadReader.forSession(result.sessionRoot());

        assertThatThrownBy(() -> reader.read(new RawLocation(0, 8, 16)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("lies inside the header of segment 0");
    }

    @Test
    @DisplayName("a missing segment file is an error, not an empty payload")
    void rejectsAMissingSegment(@TempDir Path temporary) throws Exception {
        ImportResult result = importFixture(temporary, 20);
        JournalPayloadReader reader = JournalPayloadReader.forSession(result.sessionRoot());

        assertThatThrownBy(() -> reader.read(new RawLocation(7, 32, 10)))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a frame damaged after the fact fails its checksum instead of being returned")
    void rejectsADamagedFrame(@TempDir Path temporary) throws Exception {
        ImportResult result = importFixture(temporary, 40);
        List<EventRow> rows = ImportTestSupport.readEvents(result.sessionRoot());
        RawLocation target = rows.get(9).rawLocation();

        Path segment = result.sessionRoot().resolve("raw").resolve("bes-000000.journal");
        byte[] bytes = Files.readAllBytes(segment);
        // Flip a byte inside the payload; the frame is fully present, so this is
        // corruption rather than truncation and the CRC must catch it.
        int payloadStart = (int) target.offset() + 27;
        bytes[payloadStart + 1] ^= 0x40;
        Files.write(segment, bytes);

        assertThatThrownBy(() -> JournalPayloadReader.forSession(result.sessionRoot()).read(target))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CRC_MISMATCH");
    }

    private static ImportResult importFixture(Path temporary, int events) throws IOException {
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(events));
        return new BepImporter(
                ImportTestSupport.sessionManager(temporary.resolve("sessions")),
                ImportTestSupport.deterministicOptions())
                .importFile(source);
    }
}
