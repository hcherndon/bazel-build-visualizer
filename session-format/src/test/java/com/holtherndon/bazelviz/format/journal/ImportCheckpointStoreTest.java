package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportCheckpointStoreTest {

    @TempDir
    Path directory;

    private static ImportCheckpoint checkpoint(int segment, long offset, long frames) {
        return ImportCheckpoint.at(
                new JournalPosition(segment, offset), OptionalLong.of(frames - 1), frames, frames, 42L);
    }

    @Test
    void writeThenReadReturnsTheSameCheckpoint() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        ImportCheckpoint written = checkpoint(2, 4096, 100);

        store.write(written);

        assertThat(store.read()).hasValue(written);
    }

    @Test
    void theCheckpointDirectoryIsCreatedOnDemand() throws IOException {
        Path nested = directory.resolve("session/checkpoints");
        ImportCheckpointStore store = new ImportCheckpointStore(nested);

        store.write(checkpoint(0, 64, 1));

        assertThat(Files.isRegularFile(nested.resolve(ImportCheckpointStore.CHECKPOINT_FILE_NAME)))
                .isTrue();
    }

    @Test
    void readingBeforeAnythingIsWrittenIsEmptyRatherThanAnError() throws IOException {
        assertThat(new ImportCheckpointStore(directory).read()).isEmpty();
    }

    @Test
    void aReplacementLeavesNoTemporaryFileBehind() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);

        store.write(checkpoint(0, 64, 1));
        store.write(checkpoint(0, 128, 2));
        store.write(checkpoint(1, 256, 3));

        try (var entries = Files.list(directory)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
            assertThat(names).containsExactly(
                    ImportCheckpointStore.CHECKPOINT_FILE_NAME,
                    ImportCheckpointStore.CHECKPOINT_FILE_NAME + ".previous");
        }
        assertThat(store.read()).hasValue(checkpoint(1, 256, 3));
    }

    @Test
    void aCorruptedCheckpointFallsBackToThePreviousOneRatherThanCrashing() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        ImportCheckpoint older = checkpoint(0, 1024, 10);
        ImportCheckpoint newer = checkpoint(0, 2048, 20);
        store.write(older);
        store.write(newer);

        // Whatever damaged it — a half-written file on a filesystem that lied
        // about atomic rename, a bad sector — the current checkpoint is garbage.
        Files.writeString(store.checkpointFile(), "{\"formatVersion\": 1, \"segmentInd");

        Optional<ImportCheckpoint> recovered = store.read();

        assertThat(recovered)
                .as("falling back to an older position is safe; honouring garbage is not")
                .hasValue(older);
    }

    @Test
    void aTruncatedCheckpointWithNoPreviousOneReadsAsAbsent() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        Files.createDirectories(directory);
        Files.writeString(store.checkpointFile(), "{\"formatVersion\": 1,");

        assertThat(store.read()).isEmpty();
    }

    @Test
    void aCheckpointOfBinaryGarbageIsIgnored() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        Files.createDirectories(directory);
        Files.write(store.checkpointFile(), new byte[] {0, (byte) 0xC3, (byte) 0x28, 0x7F, 0});

        assertThat(store.read()).isEmpty();
    }

    @Test
    void readFileIsStrictWhereReadIsForgiving() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        Files.createDirectories(directory);
        Files.writeString(store.checkpointFile(), "not json at all");

        assertThatThrownBy(() -> store.readFile(store.checkpointFile()))
                .isInstanceOf(CheckpointFormatException.class);
        assertThat(store.read()).isEmpty();
    }

    @Test
    void theFileOnDiskIsCompleteWheneverItExists() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        ImportCheckpoint point = checkpoint(5, 8192, 500);

        store.write(point);

        String text = Files.readString(store.checkpointFile(), StandardCharsets.UTF_8);
        assertThat(text).isEqualTo(point.toJson());
        assertThat(text).endsWith("}\n");
    }

    @Test
    void deleteRemovesTheCheckpointAndItsBackup() throws IOException {
        ImportCheckpointStore store = new ImportCheckpointStore(directory);
        store.write(checkpoint(0, 64, 1));
        store.write(checkpoint(0, 128, 2));

        store.delete();

        assertThat(Files.exists(store.checkpointFile())).isFalse();
        assertThat(Files.exists(store.previousCheckpointFile())).isFalse();
        assertThat(store.read()).isEmpty();
    }

    @Test
    void aCheckpointSurvivesRecoveryAndDrivesIt() throws IOException {
        // The end-to-end shape of plan 21.1: journal, checkpoint, crash, recover.
        Path raw = directory.resolve("raw");
        JournalWriterConfig config = new JournalWriterConfig(1L << 20, 4096, 128);
        ImportCheckpointStore store = new ImportCheckpointStore(directory.resolve("checkpoints"));

        ImportCheckpoint taken;
        try (JournalWriter writer = JournalWriter.create(raw, JournalFixture.SESSION, config)) {
            for (int i = 0; i < 8; i++) {
                writer.append(com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind.BEP_BINARY,
                        0, 100L + i, 1L, JournalFixture.payload(32, i));
            }
            writer.flush();
            taken = ImportCheckpoint.at(writer.position(), writer.lastSequence(), 8, 5, 99L);
            store.write(taken);
        }
        // Something lands after the checkpoint and does not survive the crash.
        JournalFixture.appendBytes(JournalFixture.segment(raw, 0), JournalFixture.payload(23, 3));

        RecoveryReport report =
                JournalRecovery.recover(raw, store.read(), JournalReaderConfig.verifyOnly());

        assertThat(report.checkpointHonoured()).isTrue();
        assertThat(report.bytesTruncated()).isEqualTo(23);
        assertThat(report.lastValidPosition()).hasValue(taken.position());
        assertThat(report.lastSequence()).hasValue(107L);
        assertThat(taken.normalizationBacklog())
                .as("three journaled frames still need normalizing after recovery")
                .isEqualTo(3L);
    }
}
