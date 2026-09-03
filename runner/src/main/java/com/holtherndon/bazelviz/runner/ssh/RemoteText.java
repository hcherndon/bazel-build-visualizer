package com.holtherndon.bazelviz.runner.ssh;

import java.io.IOException;

/** Parses one line of machine-readable remote output without trimming path spaces. */
final class RemoteText {

  private RemoteText() {}

  static String singleLine(String output, String description) throws IOException {
    String value = output;
    if (value.endsWith("\n")) {
      value = value.substring(0, value.length() - 1);
      if (value.endsWith("\r")) {
        value = value.substring(0, value.length() - 1);
      }
    }
    if (value.isEmpty()
        || value.indexOf('\0') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IOException(description + " returned an invalid single-line value");
    }
    return value;
  }
}
