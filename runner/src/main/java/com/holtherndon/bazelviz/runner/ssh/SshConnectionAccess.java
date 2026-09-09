package com.holtherndon.bazelviz.runner.ssh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Worker-only access to the current transport of a live workspace. */
interface SshConnectionAccess {
  Path socket() throws IOException;

  default IOException failed(Path socket, IOException failure) {
    return failure;
  }

  static SshConnectionAccess fixed(Path socket, BooleanSupplier open) {
    return () -> {
      if (!open.getAsBoolean()) {
        throw new IOException("the SSH control session is closed");
      }
      return socket;
    };
  }

  /** Recovery was attempted; retain its outcome instead of attempting it again in outer layers. */
  class RecoveryFailure extends IOException {
    private static final long serialVersionUID = 1L;

    RecoveryFailure(String message, IOException cause) {
      super(message, cause);
    }
  }

  /** The operation's outcome is unknown, but a safe read may now retry once. */
  final class RecoveredFailure extends RecoveryFailure {
    private static final long serialVersionUID = 1L;

    RecoveredFailure(IOException cause) {
      super(
          "SSH reconnected. The interrupted operation was not replayed; its outcome may be unknown."
              + " "
              + cause.getMessage(),
          cause);
    }
  }
}
