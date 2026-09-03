package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalSegmentsTest {

  @TempDir Path directory;

  @Test
  void segmentIndexesParseBackOutOfTheirFileNames() {
    assertThat(JournalSegments.parseSegmentIndex("bes-000000.journal")).hasValue(0);
    assertThat(JournalSegments.parseSegmentIndex("bes-000123.journal")).hasValue(123);
    assertThat(JournalSegments.parseSegmentIndex("bes-1234567.journal")).hasValue(1234567);
  }

  @Test
  void namesThatAreNotSegmentsAreNotGuessedAt() {
    assertThat(JournalSegments.parseSegmentIndex("bes-1.journal")).isEmpty();
    assertThat(JournalSegments.parseSegmentIndex("bes-000001.journal.tmp")).isEmpty();
    assertThat(JournalSegments.parseSegmentIndex("stdout.log")).isEmpty();
    assertThat(JournalSegments.parseSegmentIndex("bes-00000x.journal")).isEmpty();
    assertThat(JournalSegments.parseSegmentIndex("bes-99999999999999.journal"))
        .as("a number no int can hold is not a segment this build wrote")
        .isEmpty();
  }

  @Test
  void listingIgnoresEverythingThatIsNotASegment() throws IOException {
    Files.createFile(directory.resolve(JournalFormat.segmentFileName(2)));
    Files.createFile(directory.resolve(JournalFormat.segmentFileName(0)));
    Files.createFile(directory.resolve(JournalFormat.segmentFileName(10)));
    Files.createFile(directory.resolve("stdout.log"));
    Files.createDirectory(directory.resolve(JournalFormat.segmentFileName(5)));

    assertThat(JournalSegments.listSegmentIndexes(directory)).containsExactly(0, 2, 10);
    assertThat(JournalSegments.highestSegmentIndex(directory)).hasValue(10);
  }

  @Test
  void listingAJournalThatDoesNotExistYetIsEmptyNotAnError() throws IOException {
    Path missing = directory.resolve("never-created");

    assertThat(JournalSegments.listSegmentIndexes(missing)).isEmpty();
    assertThat(JournalSegments.highestSegmentIndex(missing)).isEmpty();
  }

  @Test
  void holesInTheSegmentRunAreReported() {
    assertThat(JournalSegments.missingSegmentIndexes(List.of(0, 1, 3, 6))).containsExactly(2, 4, 5);
    assertThat(JournalSegments.missingSegmentIndexes(List.of(0, 1, 2))).isEmpty();
    assertThat(JournalSegments.missingSegmentIndexes(List.of())).isEmpty();
    // A run that starts above 0 has lost its front. Every journal begins at
    // segment 0 and nothing deletes one, so this is loss, not a journal
    // that legitimately starts later. This assertion previously expected
    // the opposite, which made front-of-journal loss invisible and let
    // recovery call a mutilated journal complete.
    assertThat(JournalSegments.missingSegmentIndexes(List.of(4, 5))).containsExactly(0, 1, 2, 3);
    assertThat(JournalSegments.missingSegmentIndexes(List.of(2, 3))).containsExactly(0, 1);
  }

  @Test
  void aNegativeSegmentIndexIsRefused() {
    assertThatThrownBy(() -> JournalSegments.segmentFile(directory, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void positionsOrderBySegmentThenOffset() {
    JournalPosition early = new JournalPosition(0, 4096);
    JournalPosition later = new JournalPosition(0, 8192);
    JournalPosition nextSegment = new JournalPosition(1, 32);

    assertThat(early).isLessThan(later).isLessThan(nextSegment);
    assertThat(JournalPosition.startOfSegment(3))
        .isEqualTo(new JournalPosition(3, JournalFormat.SEGMENT_HEADER_BYTES));
    assertThatThrownBy(() -> new JournalPosition(0, 8))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void locationsDescribeTheirOwnFrameGeometry() {
    JournalLocation location = new JournalLocation(2, 1000L, 300);

    assertThat(location.payloadOffset()).isEqualTo(1000L + JournalFormat.FRAME_HEADER_BYTES);
    assertThat(location.totalFrameBytes()).isEqualTo(JournalFixture.frameBytes(300));
    assertThat(location.start()).isEqualTo(new JournalPosition(2, 1000L));
    assertThat(location.end())
        .isEqualTo(new JournalPosition(2, 1000L + JournalFixture.frameBytes(300)));
  }
}
