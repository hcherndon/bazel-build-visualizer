package com.holtherndon.bazelviz.format.session.json;

import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonBool;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNull;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A strict, streaming, bounded RFC 8259 parser.
 *
 * <p>It reads one character at a time from a {@link Reader} and never
 * materializes the source document as a string, so the peak allocation is the
 * parsed tree plus one token, not the file. Both limits it enforces — nesting
 * depth and total characters — throw rather than truncate, because a limit that
 * silently drops input is exactly the failure mode this project forbids: every
 * limit has to be visible to the caller (plan 21.3, "limit JSON record size").
 *
 * <p>Strictness is deliberate. Trailing content after the top-level value,
 * duplicate object keys, unescaped control characters, and leading zeros are all
 * rejected. Metadata files this application wrote should parse exactly; anything
 * else is a signal worth surfacing, not smoothing over.
 */
public final class JsonReader {

    /** Maximum nesting depth. A manifest nests three deep; 64 is pure headroom. */
    public static final int DEFAULT_MAX_DEPTH = 64;

    /** Maximum characters in one document. Session metadata files are kilobytes. */
    public static final long DEFAULT_MAX_CHARS = 8L * 1024 * 1024;

    private static final Pattern NUMBER =
            Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?");

    private static final int NOTHING_BUFFERED = -2;
    private static final int EOF = -1;

    private final Reader in;
    private final int maxDepth;
    private final long maxChars;

    private long consumed;
    private int buffered = NOTHING_BUFFERED;

    private JsonReader(Reader in, int maxDepth, long maxChars) {
        this.in = in;
        this.maxDepth = maxDepth;
        this.maxChars = maxChars;
    }

    /** Parses a complete document held in memory. Convenience for tests and small literals. */
    public static JsonValue parse(String text) {
        try {
            return parse(new StringReader(text));
        } catch (IOException e) {
            // A StringReader cannot fail for I/O reasons; only JsonException escapes above.
            throw new UncheckedIOException(e);
        }
    }

    /** Parses a complete document, closing nothing — the caller owns {@code in}. */
    public static JsonValue parse(Reader in) throws IOException {
        return parse(in, DEFAULT_MAX_DEPTH, DEFAULT_MAX_CHARS);
    }

    public static JsonValue parse(Reader in, int maxDepth, long maxChars) throws IOException {
        JsonReader reader = new JsonReader(in, maxDepth, maxChars);
        JsonValue value = reader.readValue(1);
        reader.skipWhitespace();
        int trailing = reader.next();
        if (trailing != EOF) {
            throw reader.malformed("trailing content after the top-level value: '" + (char) trailing + "'");
        }
        return value;
    }

    /** Parses a UTF-8 file, streaming it through a buffered reader. */
    public static JsonValue parseFile(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    // ---------------------------------------------------------------- grammar

    private JsonValue readValue(int depth) throws IOException {
        if (depth > maxDepth) {
            throw malformed("nesting deeper than the " + maxDepth + " level limit");
        }
        skipWhitespace();
        int c = peek();
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> new JsonString(readString());
            case 't' -> readKeyword("true", JsonBool.TRUE);
            case 'f' -> readKeyword("false", JsonBool.FALSE);
            case 'n' -> readKeyword("null", JsonNull.INSTANCE);
            case EOF -> throw malformed("unexpected end of input where a value was expected");
            default -> readNumber();
        };
    }

    private JsonObject readObject(int depth) throws IOException {
        expect('{');
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            next();
            return new JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw malformed("object member names must be quoted strings");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            JsonValue value = readValue(depth + 1);
            if (members.putIfAbsent(key, value) != null) {
                // Duplicate keys make "the last one wins" a silent data choice.
                throw malformed("duplicate object member '" + key + "'");
            }
            skipWhitespace();
            int c = next();
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                return new JsonObject(members);
            }
            throw malformed("expected ',' or '}' in object, found " + describe(c));
        }
    }

    private JsonArray readArray(int depth) throws IOException {
        expect('[');
        List<JsonValue> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            next();
            return new JsonArray(elements);
        }
        while (true) {
            elements.add(readValue(depth + 1));
            skipWhitespace();
            int c = next();
            if (c == ',') {
                continue;
            }
            if (c == ']') {
                return new JsonArray(elements);
            }
            throw malformed("expected ',' or ']' in array, found " + describe(c));
        }
    }

    private String readString() throws IOException {
        expect('"');
        StringBuilder text = new StringBuilder();
        while (true) {
            int c = next();
            if (c == EOF) {
                throw malformed("unterminated string");
            }
            if (c == '"') {
                return text.toString();
            }
            if (c == '\\') {
                text.append(readEscape());
                continue;
            }
            if (c < 0x20) {
                throw malformed("unescaped control character U+%04X in string".formatted(c));
            }
            text.append((char) c);
        }
    }

    private char readEscape() throws IOException {
        int c = next();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw malformed("invalid escape sequence \\" + describe(c));
        };
    }

    private char readUnicodeEscape() throws IOException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int c = next();
            int digit = Character.digit(c, 16);
            if (c == EOF || digit < 0) {
                throw malformed("invalid \\u escape");
            }
            value = (value << 4) | digit;
        }
        // Surrogate halves are appended as-is; a well-formed pair reassembles
        // naturally in the StringBuilder.
        return (char) value;
    }

    private JsonValue readKeyword(String keyword, JsonValue value) throws IOException {
        for (int i = 0; i < keyword.length(); i++) {
            int c = next();
            if (c != keyword.charAt(i)) {
                throw malformed("expected '" + keyword + "'");
            }
        }
        return value;
    }

    private JsonNumber readNumber() throws IOException {
        StringBuilder literal = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == EOF || !isNumberChar((char) c)) {
                break;
            }
            literal.append((char) next());
        }
        if (literal.isEmpty()) {
            throw malformed("expected a value, found " + describe(peek()));
        }
        String text = literal.toString();
        if (!NUMBER.matcher(text).matches()) {
            throw malformed("malformed number literal '" + text + "'");
        }
        return new JsonNumber(text);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    // ----------------------------------------------------------- char plumbing

    private void skipWhitespace() throws IOException {
        while (true) {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                next();
            } else {
                return;
            }
        }
    }

    private void expect(char expected) throws IOException {
        int c = next();
        if (c != expected) {
            throw malformed("expected '" + expected + "', found " + describe(c));
        }
    }

    private int peek() throws IOException {
        if (buffered == NOTHING_BUFFERED) {
            buffered = readCounted();
        }
        return buffered;
    }

    private int next() throws IOException {
        if (buffered != NOTHING_BUFFERED) {
            int c = buffered;
            buffered = NOTHING_BUFFERED;
            return c;
        }
        return readCounted();
    }

    private int readCounted() throws IOException {
        int c = in.read();
        if (c >= 0) {
            consumed++;
            if (consumed > maxChars) {
                throw new JsonException(
                        "JSON document exceeds the " + maxChars + " character limit; refusing to read further");
            }
        }
        return c;
    }

    private JsonException malformed(String detail) {
        return new JsonException("malformed JSON at character " + consumed + ": " + detail);
    }

    private static String describe(int c) {
        return c == EOF ? "end of input" : "'" + (char) c + "'";
    }
}
