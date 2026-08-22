package com.holtherndon.bazelviz.testsupport.bep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One call that lays a complete set of BEP fixture files into a directory:
 * the good stream in all three on-disk shapes, plus one file per damage mode.
 *
 * <p>Intended use is a JUnit {@code @TempDir}:
 *
 * <pre>{@code
 * BepFixtures.Set fixtures = BepFixtures.writeAll(tempDir, SyntheticBepStream.of(200));
 * importer.importFile(fixtures.binary());          // clean path
 * importer.importFile(fixtures.truncatedMidPayload().path()); // must report TRUNCATED
 * importer.importFile(fixtures.corruptPayloadByte().path());  // must report CORRUPT
 * }</pre>
 *
 * Every damaged file is derived from the same {@link #binary()} bytes, so the
 * intact prefix of each is identical and a test can compare event counts
 * against {@link BepFixtureCatalog} directly.
 */
public final class BepFixtures {

    /** File names used by {@link #writeAll}. */
    public static final String BINARY_NAME = "build.bep";

    public static final String JSON_LINES_NAME = "build.jsonl.json";
    public static final String JSON_PRETTY_NAME = "build.pretty.json";
    public static final String TRUNCATED_MID_VARINT_NAME = "truncated-mid-varint.bep";
    public static final String TRUNCATED_MID_PAYLOAD_NAME = "truncated-mid-payload.bep";
    public static final String TRUNCATED_HALFWAY_NAME = "truncated-halfway.bep";
    public static final String CORRUPT_PAYLOAD_BYTE_NAME = "corrupt-payload-byte.bep";
    public static final String TRAILING_GARBAGE_NAME = "trailing-garbage.bep";
    public static final String UNKNOWN_FIELD_NAME = "unknown-field.bep";

    /**
     * The written fixture set.
     *
     * @param unknownFieldPayloadOffset byte offset of the payload of the one
     *     event in {@link #unknownField()} that carries unknown fields
     * @param unknownFieldEventIndex index of that event within the stream
     */
    public record Set(
            SyntheticBepStream stream,
            BepFixtureCatalog catalog,
            Path directory,
            Path binary,
            long binaryBytes,
            Path jsonLines,
            Path jsonPretty,
            BepDamage.Damage truncatedMidVarint,
            BepDamage.Damage truncatedMidPayload,
            BepDamage.Damage truncatedHalfway,
            BepDamage.Damage corruptPayloadByte,
            BepDamage.Damage trailingGarbage,
            Path unknownField,
            long unknownFieldPayloadOffset,
            int unknownFieldEventIndex) {}

    private BepFixtures() {}

    /** Writes the default 200-event fixture set. */
    public static Set writeAll(Path directory) throws IOException {
        return writeAll(directory, SyntheticBepStream.of(200));
    }

    /** Writes a fixture set generated from {@code stream}. */
    public static Set writeAll(Path directory, SyntheticBepStream stream) throws IOException {
        Files.createDirectories(directory);

        Path binary = directory.resolve(BINARY_NAME);
        long binaryBytes = BepBinaryWriter.write(binary, stream);

        Path jsonLines = directory.resolve(JSON_LINES_NAME);
        BepJsonWriter.write(jsonLines, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE, stream);

        Path jsonPretty = directory.resolve(JSON_PRETTY_NAME);
        BepJsonWriter.write(jsonPretty, BepJsonWriter.Layout.PRETTY_MULTILINE, stream);

        BepDamage.Damage midVarint =
                BepDamage.truncateMidVarint(binary, directory.resolve(TRUNCATED_MID_VARINT_NAME));
        BepDamage.Damage midPayload =
                BepDamage.truncateMidPayload(binary, directory.resolve(TRUNCATED_MID_PAYLOAD_NAME));
        BepDamage.Damage halfway =
                BepDamage.truncateAt(binary, directory.resolve(TRUNCATED_HALFWAY_NAME), binaryBytes / 2);

        // Damage the last target unit's ActionExecuted payload: late enough that
        // a partial import has real content before it, and a payload big enough
        // that a flipped byte lands in a field value rather than a tag.
        int corruptFrame = Math.max(0, stream.eventCount() - 6);
        BepDamage.Damage flipped = BepDamage.flipPayloadByte(
                binary, directory.resolve(CORRUPT_PAYLOAD_BYTE_NAME), corruptFrame, 1);

        BepDamage.Damage garbage =
                BepDamage.appendTrailingGarbage(binary, directory.resolve(TRAILING_GARBAGE_NAME), 64, 0xC0FFEEL);

        int unknownFieldEventIndex = Math.min(stream.eventCount() - 1, SyntheticBepStream.PROLOGUE_EVENTS);
        Path unknownField = directory.resolve(UNKNOWN_FIELD_NAME);
        long unknownOffset = UnknownFieldFixture.writeBinaryStreamWithUnknownFieldEvent(
                unknownField, stream, unknownFieldEventIndex);

        return new Set(
                stream,
                BepFixtureCatalog.of(stream),
                directory,
                binary,
                binaryBytes,
                jsonLines,
                jsonPretty,
                midVarint,
                midPayload,
                halfway,
                flipped,
                garbage,
                unknownField,
                unknownOffset,
                unknownFieldEventIndex);
    }
}
