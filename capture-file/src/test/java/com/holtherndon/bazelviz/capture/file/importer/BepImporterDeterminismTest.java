package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.EventRow;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepJsonWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exit criterion 4: event counts and offsets are reproducible.
 *
 * <p>Importing one file into two independent sessions must produce the same
 * number of events, the same journal offsets and the same id hashes. Anything
 * that varied between the two — a hash seeded by identity, an offset that
 * depended on timing, an ordinal drawn from a shared counter — would mean a
 * session could not be compared with another, re-derived, or checked against a
 * fixture.
 */
class BepImporterDeterminismTest {

    @Test
    @DisplayName("importing the same binary file twice produces identical rows")
    void binaryImportsAreReproducible(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(150));

        List<EventRow> first = importAndRead(temporary.resolve("a"), source);
        List<EventRow> second = importAndRead(temporary.resolve("b"), source);

        assertThat(second).hasSameSizeAs(first);
        assertThat(second).extracting(EventRow::reproducibleForm)
                .containsExactlyElementsOf(first.stream().map(EventRow::reproducibleForm).toList());
        // Spelled out separately so a failure names which property drifted.
        assertThat(second).extracting(EventRow::rawOffset)
                .containsExactlyElementsOf(first.stream().map(EventRow::rawOffset).toList());
        assertThat(second).extracting(EventRow::eventIdHash)
                .containsExactlyElementsOf(first.stream().map(EventRow::eventIdHash).toList());
    }

    @Test
    @DisplayName("importing the same JSON file twice produces identical rows")
    void jsonImportsAreReproducible(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.json");
        BepJsonWriter.write(source, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE,
                SyntheticBepStream.of(120));

        List<EventRow> first = importAndRead(temporary.resolve("a"), source);
        List<EventRow> second = importAndRead(temporary.resolve("b"), source);

        assertThat(second).extracting(EventRow::reproducibleForm)
                .containsExactlyElementsOf(first.stream().map(EventRow::reproducibleForm).toList());
    }

    @Test
    @DisplayName("the same file produces the same digest and the same journal bytes")
    void journalsAreByteIdentical(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, SyntheticBepStream.of(90));

        ImportResult first = importOnce(temporary.resolve("a"), source);
        ImportResult second = importOnce(temporary.resolve("b"), source);

        assertThat(second.source().sha256()).isEqualTo(first.source().sha256());
        assertThat(second.recordsJournaled()).isEqualTo(first.recordsJournaled());

        // The journal segments themselves match byte for byte apart from the
        // session UUID embedded in each segment header, which is by definition
        // per-session; comparing the frames alone would hide a framing change,
        // so the whole file is compared past the 32-byte header.
        byte[] firstSegment = java.nio.file.Files.readAllBytes(
                first.sessionRoot().resolve("raw").resolve("bes-000000.journal"));
        byte[] secondSegment = java.nio.file.Files.readAllBytes(
                second.sessionRoot().resolve("raw").resolve("bes-000000.journal"));
        assertThat(secondSegment.length).isEqualTo(firstSegment.length);
        assertThat(java.util.Arrays.copyOfRange(secondSegment, 32, secondSegment.length))
                .isEqualTo(java.util.Arrays.copyOfRange(firstSegment, 32, firstSegment.length));
    }

    private static List<EventRow> importAndRead(Path sessionsRoot, Path source) throws Exception {
        return ImportTestSupport.readEvents(importOnce(sessionsRoot, source).sessionRoot());
    }

    private static ImportResult importOnce(Path sessionsRoot, Path source) throws Exception {
        return new BepImporter(
                ImportTestSupport.sessionManager(sessionsRoot),
                ImportTestSupport.deterministicOptions())
                .importFile(source, SessionId.random(), ImportProgressListener.NONE, () -> false);
    }
}
