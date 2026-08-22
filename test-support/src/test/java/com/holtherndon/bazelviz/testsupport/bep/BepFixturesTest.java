package com.holtherndon.bazelviz.testsupport.bep;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one-call fixture set: every file present, every damage report accurate. */
final class BepFixturesTest {

    @TempDir
    Path tempDir;

    @Test
    void writesEveryFixtureFileAndReportsThemAccurately() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(203);
        BepFixtures.Set set = BepFixtures.writeAll(tempDir, stream);

        assertThat(set.directory()).isEqualTo(tempDir);
        assertThat(set.catalog().eventCount()).isEqualTo(203);

        assertThat(set.binary()).exists();
        assertThat(set.binaryBytes()).isEqualTo(Files.size(set.binary()));
        assertThat(LengthDelimitedFrames.index(set.binary()).frames()).hasSize(203);

        assertThat(Files.readAllLines(set.jsonLines(), StandardCharsets.UTF_8)).hasSize(203);
        assertThat(Files.size(set.jsonPretty())).isGreaterThan(Files.size(set.jsonLines()));

        assertThat(LengthDelimitedFrames.index(set.truncatedMidVarint().path()).tail())
                .isEqualTo(LengthDelimitedFrames.Tail.PARTIAL_VARINT);
        assertThat(LengthDelimitedFrames.index(set.truncatedMidPayload().path()).tail())
                .isEqualTo(LengthDelimitedFrames.Tail.PARTIAL_PAYLOAD);
        assertThat(set.truncatedHalfway().resultBytes()).isEqualTo(set.binaryBytes() / 2);
        assertThat(set.corruptPayloadByte().resultBytes()).isEqualTo(set.binaryBytes());
        assertThat(set.trailingGarbage().resultBytes()).isEqualTo(set.binaryBytes() + 64);

        for (Path path : List.of(
                set.binary(),
                set.jsonLines(),
                set.jsonPretty(),
                set.truncatedMidVarint().path(),
                set.truncatedMidPayload().path(),
                set.truncatedHalfway().path(),
                set.corruptPayloadByte().path(),
                set.trailingGarbage().path(),
                set.unknownField())) {
            assertThat(path).exists();
            assertThat(Files.size(path)).isPositive();
        }
    }

    @Test
    void theUnknownFieldFixtureCarriesUnknownFieldsAtTheReportedOffset() throws IOException {
        BepFixtures.Set set = BepFixtures.writeAll(tempDir, SyntheticBepStream.of(120));

        List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(set.unknownField()).frames();
        LengthDelimitedFrames.Frame frame = frames.get(set.unknownFieldEventIndex());
        assertThat(frame.payloadOffset()).isEqualTo(set.unknownFieldPayloadOffset());

        byte[] payload = LengthDelimitedFrames.payloadOf(set.unknownField(), frame);
        assertThat(BuildEvent.parseFrom(payload).getUnknownFields().asMap()).isNotEmpty();
    }

    @Test
    void everyDamagedFileSharesTheIntactPrefixOfTheGoodOne() throws IOException {
        BepFixtures.Set set = BepFixtures.writeAll(tempDir, SyntheticBepStream.of(80));
        byte[] good = Files.readAllBytes(set.binary());

        for (BepDamage.Damage damage : List.of(
                set.truncatedMidVarint(), set.truncatedMidPayload(), set.truncatedHalfway())) {
            byte[] damaged = Files.readAllBytes(damage.path());
            assertThat(damaged).isEqualTo(java.util.Arrays.copyOf(good, damaged.length));
        }

        byte[] garbage = Files.readAllBytes(set.trailingGarbage().path());
        assertThat(java.util.Arrays.copyOf(garbage, good.length)).isEqualTo(good);
    }
}
