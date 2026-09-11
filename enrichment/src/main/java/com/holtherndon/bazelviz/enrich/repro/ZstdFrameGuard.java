package com.holtherndon.bazelviz.enrich.repro;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Allocation preflight for every frame in an already bounded, private source snapshot. The existing
 * decoder still validates and decompresses the payload. This only scans framing and memory claims.
 * Format: https://github.com/facebook/zstd/blob/dev/doc/zstd_compression_format.md.
 */
final class ZstdFrameGuard {
  static final long MAX_WINDOW_BYTES = 8L * 1024 * 1024;
  static final long MAX_FRAMES = 65_536;

  private ZstdFrameGuard() {}

  static void verify(Path source, BooleanSupplier cancelled) throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(source))) {
      long frames = 0;
      int first;
      while ((first = in.read()) != -1) {
        check(cancelled);
        if (++frames > MAX_FRAMES) {
          throw new IOException(
              "Compact execution log exceeds the zstd frame count limit (" + MAX_FRAMES + ").");
        }
        long magic = first | (unsigned(in, 3) << 8);
        if (magic != 0xfd2fb528L) {
          throw new IOException(
              "Unsupported zstd frame or trailing data in compact execution log.");
        }
        int descriptor = (int) unsigned(in, 1);
        if ((descriptor & 8) != 0) {
          throw new IOException("Reserved zstd frame-header bit is set.");
        }
        boolean single = (descriptor & 32) != 0;
        long window = 0;
        if (!single) {
          int descriptorWindow = (int) unsigned(in, 1);
          long base = 1L << (10 + (descriptorWindow >>> 3));
          window = base + (base >>> 3) * (descriptorWindow & 7);
        }
        int dictionaryFlag = descriptor & 3;
        in.skipNBytes(dictionaryFlag == 0 ? 0 : 1 << (dictionaryFlag - 1));
        int sizeFlag = descriptor >>> 6;
        int sizeBytes = sizeFlag == 0 ? (single ? 1 : 0) : 1 << sizeFlag;
        long contentSize = unsigned(in, sizeBytes);
        if (sizeBytes == 2) {
          contentSize += 256;
        }
        if (single) {
          window = contentSize;
        }
        if (window < 0 || window > MAX_WINDOW_BYTES) {
          throw new IOException(
              "Compact execution log requests a zstd window above the byte limit ("
                  + MAX_WINDOW_BYTES
                  + ").");
        }
        boolean last;
        do {
          check(cancelled);
          int block = (int) unsigned(in, 3);
          last = (block & 1) != 0;
          int kind = (block >>> 1) & 3;
          int bytes = block >>> 3;
          if (kind == 3 || bytes > 131072) {
            throw new IOException("Invalid or oversized zstd block header.");
          }
          in.skipNBytes(kind == 1 ? 1 : bytes);
        } while (!last);
        if ((descriptor & 4) != 0) {
          in.skipNBytes(4);
        }
      }
    }
  }

  private static long unsigned(InputStream in, int bytes) throws IOException {
    long value = 0;
    for (int i = 0; i < bytes; i++) {
      int next = in.read();
      if (next == -1) {
        throw new EOFException("Truncated compact execution-log frame.");
      }
      value |= (long) next << (8 * i);
    }
    return value;
  }

  private static void check(BooleanSupplier cancelled) throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      throw new IOException("Compact execution-log preflight cancelled.");
    }
  }
}
