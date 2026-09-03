package com.holtherndon.bazelviz.app.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Keeps one physical line per event by escaping embedded control characters.
 *
 * <p>Paths and exception messages can contain newlines or terminal escape bytes. Leaving them
 * intact would let imported text look like a second log record. Stack traces remain present, with
 * their line breaks rendered as printable escapes.
 */
public final class ControlSafePatternLayoutEncoder extends PatternLayoutEncoder {

  @Override
  public void start() {
    if (getCharset() == null) {
      setCharset(StandardCharsets.UTF_8);
    }
    super.start();
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    Charset charset = getCharset() == null ? StandardCharsets.UTF_8 : getCharset();
    String rendered = new String(super.encode(event), charset);
    int end = rendered.length();
    if (end >= 2 && rendered.charAt(end - 2) == '\r' && rendered.charAt(end - 1) == '\n') {
      end -= 2;
    } else if (end >= 1 && (rendered.charAt(end - 1) == '\n' || rendered.charAt(end - 1) == '\r')) {
      end--;
    }

    StringBuilder safe = new StringBuilder(rendered.length() + 1);
    for (int index = 0; index < end; index++) {
      char character = rendered.charAt(index);
      switch (character) {
        case '\n' -> safe.append("\\n");
        case '\r' -> safe.append("\\r");
        case '\t' -> safe.append("\\t");
        default -> {
          if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
            appendUnicodeEscape(safe, character);
          } else {
            safe.append(character);
          }
        }
      }
    }
    safe.append(System.lineSeparator());
    return safe.toString().getBytes(charset);
  }

  private static void appendUnicodeEscape(StringBuilder destination, char character) {
    char[] hexadecimal = "0123456789abcdef".toCharArray();
    destination
        .append("\\u")
        .append(hexadecimal[(character >>> 12) & 0x0f])
        .append(hexadecimal[(character >>> 8) & 0x0f])
        .append(hexadecimal[(character >>> 4) & 0x0f])
        .append(hexadecimal[character & 0x0f]);
  }
}
