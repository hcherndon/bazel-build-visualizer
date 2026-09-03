package com.holtherndon.bazelviz.runner.ssh;

import java.nio.file.Path;
import java.util.Objects;

/** System OpenSSH programs used by one control session. */
record OpenSshBinaries(Path ssh, Path sftp) {

  static final OpenSshBinaries SYSTEM =
      new OpenSshBinaries(Path.of("/usr/bin/ssh"), Path.of("/usr/bin/sftp"));

  OpenSshBinaries {
    Objects.requireNonNull(ssh, "ssh");
    Objects.requireNonNull(sftp, "sftp");
  }
}
