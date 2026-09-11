package com.holtherndon.bazelviz.format.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionAuditReferenceTest {
  @TempDir Path temporary;

  @Test
  void survivesRestartAndDoesNotReplaceAnOwner() throws Exception {
    Path session = Files.createDirectory(temporary.resolve("session"));
    Path audit = temporary.resolve("audit");
    assertThat(SessionAuditReference.isProtected(session)).isFalse();
    SessionAuditReference.protect(session, audit);
    SessionAuditReference.protect(session, audit);
    assertThat(SessionAuditReference.isProtected(session)).isTrue();
    assertThatThrownBy(() -> SessionAuditReference.protect(session, temporary.resolve("other")))
        .isInstanceOf(IOException.class);
    assertThat(Files.readString(ManagedSessionLayout.at(session).auditReferenceFile()))
        .isEqualTo(audit.toAbsolutePath().normalize() + "\n");
  }

  @Test
  void incompleteAndSymlinkedReferencesStillPreventDeletion() throws Exception {
    Path session = Files.createDirectory(temporary.resolve("session"));
    ManagedSessionLayout layout = ManagedSessionLayout.at(session);
    Files.createDirectory(layout.locksDirectory());
    Files.createFile(layout.auditReferenceFile());
    assertThat(SessionAuditReference.isProtected(session)).isTrue();
    assertThatThrownBy(() -> SessionAuditReference.protect(session, temporary))
        .isInstanceOf(IOException.class);
    Files.delete(layout.auditReferenceFile());
    Files.createSymbolicLink(layout.auditReferenceFile(), temporary.resolve("missing"));
    assertThat(SessionAuditReference.isProtected(session)).isTrue();
    assertThatThrownBy(() -> SessionAuditReference.protect(session, temporary))
        .isInstanceOf(IOException.class);
  }

  @Test
  void refusesRedirectedLockDirectoryAndOversizedOwner() throws Exception {
    Path session = Files.createDirectory(temporary.resolve("session"));
    Path elsewhere = Files.createDirectory(temporary.resolve("elsewhere"));
    Path locks = ManagedSessionLayout.at(session).locksDirectory();
    Files.createSymbolicLink(locks, elsewhere);
    assertThatThrownBy(() -> SessionAuditReference.protect(session, temporary))
        .isInstanceOf(IOException.class);
    try (var entries = Files.list(elsewhere)) {
      assertThat(entries.findAny()).isEmpty();
    }
    Files.delete(locks);
    assertThatThrownBy(
            () ->
                SessionAuditReference.protect(
                    session, Path.of("x".repeat(SessionAuditReference.MAX_REFERENCE_BYTES + 1))))
        .isInstanceOf(IOException.class);
  }
}
