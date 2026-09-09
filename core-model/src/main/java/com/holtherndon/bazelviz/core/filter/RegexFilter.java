package com.holtherndon.bazelviz.core.filter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Shared Java-regex search semantics, with bounded caching and matching work. */
public final class RegexFilter {
  public static final int MAX_CACHED_PATTERNS = 64;
  public static final int MAX_CHARACTER_READS = 1_000_000;
  private static final Map<String, Pattern> CACHE = new LinkedHashMap<>();

  private RegexFilter() {}

  public static synchronized Pattern compile(String expression) {
    if (expression.length() > FilterExpression.MAX_VALUE_CHARACTERS) {
      throw new IllegalArgumentException("Regex pattern is too long.");
    }
    Pattern cached = CACHE.get(expression);
    if (cached != null) {
      return cached;
    }
    try {
      Pattern pattern = Pattern.compile(expression);
      if (CACHE.size() >= MAX_CACHED_PATTERNS) {
        CACHE.remove(CACHE.keySet().iterator().next());
      }
      CACHE.put(expression, pattern);
      return pattern;
    } catch (PatternSyntaxException invalid) {
      throw new IllegalArgumentException("Invalid regex: " + invalid.getDescription(), invalid);
    } catch (StackOverflowError excessive) {
      throw new IllegalArgumentException("Regex is too complex; simplify the pattern.", excessive);
    }
  }

  public static boolean matches(String expression, String text) {
    try {
      return compile(expression)
          .matcher(new BoundedText(text, 0, text.length(), new int[1]))
          .find();
    } catch (StackOverflowError excessive) {
      throw new IllegalArgumentException("Regex is too complex; simplify the pattern.", excessive);
    }
  }

  private record BoundedText(String text, int start, int end, int[] reads) implements CharSequence {
    @Override
    public int length() {
      return end - start;
    }

    @Override
    public char charAt(int index) {
      if (++reads[0] > MAX_CHARACTER_READS || Thread.currentThread().isInterrupted()) {
        throw new IllegalArgumentException(
            "Regex matching stopped at its work limit or was cancelled; simplify the pattern or"
                + " narrow the filters.");
      }
      if (index < 0 || index >= length()) {
        throw new IndexOutOfBoundsException(index);
      }
      return text.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
      if (from < 0 || to < from || to > length()) {
        throw new IndexOutOfBoundsException();
      }
      return new BoundedText(text, start + from, start + to, reads);
    }

    @Override
    public String toString() {
      return text.substring(start, end);
    }
  }
}
