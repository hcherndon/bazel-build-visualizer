package com.holtherndon.bazelviz.core.text;

/**
 * JSON string escaping for streamed output.
 *
 * <h2>Why not the session format's JSON writer</h2>
 *
 * <p>{@code format.session.json.JsonWriter} builds a value tree and renders it,
 * which is right for a manifest of a few kilobytes and wrong for an export of
 * five million rows: the tree is the whole export in memory, and plan 24's
 * Phase 9 exit criterion is that "export does not require loading the entire
 * session into memory". So a streamed export writes its own structure and needs
 * only this — the part that is easy to get subtly wrong.
 *
 * <h2>What has to be escaped, and what merely may be</h2>
 *
 * <p>JSON requires escaping the quote, the backslash and everything below
 * {@code U+0020}. This also escapes {@code U+2028} and {@code U+2029}, which
 * are legal in JSON and are line terminators in JavaScript — an export that a
 * browser or a Node script reads would break on them, and a build's progress
 * output is exactly the sort of text that contains one.
 */
public final class Json {

    private Json() {}

    /** One string, quoted and escaped. Null becomes the literal {@code null}. */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20 || character == 0x2028 || character == 0x2029) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x",
                                (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
