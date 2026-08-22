package com.holtherndon.bazelviz.ui.events;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.bepcodec.BepEventDecoder;
import com.holtherndon.bazelviz.bepcodec.DecodeResult;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns one record's verbatim bytes into the two things the inspector shows:
 * a readable rendering of the record, and a hex/ASCII dump of the bytes
 * themselves.
 *
 * <h2>Bytes are shown whatever happens</h2>
 *
 * <p>A record that will not decode still has its bytes dumped and the failure
 * printed above them. That is the point of storing raw payloads at all
 * (ADR-004, plan 21.5): a later build with newer protos can read what this one
 * cannot, and in the meantime the user can see exactly what arrived rather than
 * an empty pane.
 *
 * <h2>Display limits are stated, never silent</h2>
 *
 * <p>A single BEP event can be megabytes. Both renderings are capped, and every
 * cap that actually fired appends a notice saying what was withheld and how
 * much of it there was — plan section 3's "show every imposed display limit"
 * and project rule 12. Nothing is ever trimmed quietly.
 *
 * <p>Pure and blocking-free apart from protobuf parsing; called from the
 * inspector's background executor, never the EDT.
 */
public final class RawPayloadRenderer {

    /** Characters of decoded text rendered before the display gives up. */
    public static final int MAX_TEXT_CHARS = 200_000;

    /** Bytes dumped in hex before the display gives up. */
    public static final int MAX_HEX_BYTES = 64 * 1024;

    private static final int HEX_BYTES_PER_LINE = 16;

    private RawPayloadRenderer() {}

    /**
     * A rendered payload.
     *
     * @param text the readable form: protobuf text for a binary BEP record, the
     *     record itself for a JSON one, or an explanation when neither applies
     * @param decodeFailure why the bytes could not be read, when they could not
     * @param notices display limits and discrepancies the user must be told
     *     about; empty when there is nothing to disclose
     */
    public record Rendered(String text, Optional<String> decodeFailure, List<String> notices) {

        public Rendered {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(decodeFailure, "decodeFailure");
            notices = List.copyOf(notices);
        }
    }

    /**
     * Renders {@code payload}, cross-checking against the status the import
     * recorded for it.
     *
     * @param storedStatus {@code bep_events.decode_status} for this row
     */
    public static Rendered render(RawPayload payload, DecodeStatus storedStatus) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(storedStatus, "storedStatus");
        List<String> notices = new ArrayList<>();
        return switch (payload.sourceKind()) {
            case BEP_BINARY -> renderBinary(payload, storedStatus, notices);
            case BEP_JSON_RECORD -> renderJson(payload, storedStatus, notices);
            case BES_ENVELOPE -> new Rendered(
                    "This record is a BES envelope. Envelope decoding arrives with the embedded"
                            + " BES server in Phase 2; the raw bytes below are complete and"
                            + " unmodified in the meantime.",
                    Optional.empty(),
                    notices);
        };
    }

    private static Rendered renderBinary(
            RawPayload payload, DecodeStatus storedStatus, List<String> notices) {
        // One copy out of the record, reused: RawPayload.bytes() defensively
        // clones, and a multi-megabyte payload should be cloned once, not once
        // per question asked about it.
        byte[] bytes = payload.bytes();
        DecodeResult result = BepEventDecoder.withDefaults().decode(bytes);
        if (result.status() != storedStatus) {
            // Re-reading the same bytes should reach the same verdict the
            // import did. When it does not, something changed between the two
            // — a proto update, or a row and a journal that disagree — and the
            // user is told rather than shown whichever answer came last.
            notices.add("The import recorded this record as " + storedStatus
                    + ", but decoding it again now says " + result.status() + ".");
        }
        if (result.isFailed()) {
            return new Rendered(
                    "This record could not be decoded as a BuildEvent. Its bytes are preserved"
                            + " exactly as they arrived and are shown below; a later build with"
                            + " newer protocol definitions may be able to read them.",
                    result.failureDetail(),
                    notices);
        }
        BuildEvent event = result.requireEvent();
        if (result.hasUnknownFields()) {
            notices.add("This record carried fields this build does not know. They are absent"
                    + " from the text below and present in the raw bytes.");
        }
        return new Rendered(cap(event.toString(), notices, "decoded text"), Optional.empty(), notices);
    }

    private static Rendered renderJson(
            RawPayload payload, DecodeStatus storedStatus, List<String> notices) {
        if (storedStatus == DecodeStatus.FAILED) {
            notices.add("The import could not decode this JSON record into a BuildEvent."
                    + " The record itself is shown as it was written.");
        } else if (storedStatus == DecodeStatus.UNKNOWN_FIELDS) {
            notices.add("This record carried fields this build does not know; they are present"
                    + " in the text below and were not normalized.");
        }
        String text = new String(payload.bytes(), StandardCharsets.UTF_8);
        return new Rendered(cap(text, notices, "JSON record"), Optional.empty(), notices);
    }

    /**
     * A hex/ASCII dump of at most {@link #MAX_HEX_BYTES} bytes, with a trailing
     * line naming everything it did not show.
     */
    public static String hexDump(byte[] bytes) {
        return hexDump(bytes, MAX_HEX_BYTES);
    }

    /** As {@link #hexDump(byte[])}, with an explicit limit. For tests. */
    public static String hexDump(byte[] bytes, int limit) {
        Objects.requireNonNull(bytes, "bytes");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if (bytes.length == 0) {
            return "(0 bytes)";
        }
        int shown = Math.min(bytes.length, limit);
        StringBuilder out = new StringBuilder(shown * 4 + 64);
        for (int offset = 0; offset < shown; offset += HEX_BYTES_PER_LINE) {
            int lineEnd = Math.min(offset + HEX_BYTES_PER_LINE, shown);
            out.append("%08x  ".formatted(offset));
            for (int i = offset; i < offset + HEX_BYTES_PER_LINE; i++) {
                out.append(i < lineEnd ? "%02x ".formatted(bytes[i]) : "   ");
                if (i - offset == 7) {
                    out.append(' ');
                }
            }
            out.append(" |");
            for (int i = offset; i < lineEnd; i++) {
                int value = bytes[i] & 0xFF;
                out.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
            }
            out.append("|\n");
        }
        if (shown < bytes.length) {
            out.append("\n… ")
                    .append(bytes.length - shown)
                    .append(" of ")
                    .append(bytes.length)
                    .append(" bytes are not shown: the hex view is limited to ")
                    .append(limit)
                    .append(" bytes. The record is stored complete in the journal.\n");
        }
        return out.toString();
    }

    private static String cap(String text, List<String> notices, String what) {
        if (text.length() <= MAX_TEXT_CHARS) {
            return text;
        }
        notices.add("The " + what + " is " + text.length() + " characters; only the first "
                + MAX_TEXT_CHARS + " are shown.");
        return text.substring(0, MAX_TEXT_CHARS);
    }
}
