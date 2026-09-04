package com.holtherndon.bazelviz.runner.proc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SubprocessTest {

  @Test
  @DisplayName("probe output is drained but only its bounded prefix is retained")
  void outputCaptureIsBoundedAndExplicit() throws Exception {
    Subprocess.Result result =
        Subprocess.run(
            List.of("/bin/sh", "-c", "printf '0123456789'; printf 'abcdefghij' >&2"),
            null,
            Map.of(),
            Duration.ofSeconds(5),
            4,
            5);

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).isEqualTo("0123");
    assertThat(result.stderr()).isEqualTo("abcde");
    assertThat(result.stdoutBytes()).isEqualTo(10);
    assertThat(result.stderrBytes()).isEqualTo(10);
    assertThat(result.stdoutTruncated()).isTrue();
    assertThat(result.stderrTruncated()).isTrue();
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.failureDetail())
        .contains("4-byte capture limit")
        .contains("5-byte capture limit")
        .contains("retained text is only a prefix");
  }

  @Test
  @DisplayName("complete output from a successful probe remains successful")
  void completeOutputRemainsSuccessful() throws Exception {
    Subprocess.Result result =
        Subprocess.run(
            List.of("/bin/sh", "-c", "printf 'version'; printf 'warning' >&2"),
            null,
            Map.of(),
            Duration.ofSeconds(5),
            64,
            64);

    assertThat(result.stdout()).isEqualTo("version");
    assertThat(result.stderr()).isEqualTo("warning");
    assertThat(result.outputTruncated()).isFalse();
    assertThat(result.isSuccess()).isTrue();
  }

  @Test
  @DisplayName("a timed-out process with inherited pipes is stopped and joined")
  void timeoutClosesInheritedPipes() throws Exception {
    long started = System.nanoTime();

    Subprocess.Result result =
        Subprocess.run(
            List.of("/bin/sh", "-c", "sleep 30 & printf 'started'; wait"),
            null,
            Map.of(),
            Duration.ofMillis(100),
            64,
            64);

    assertThat(result.timedOut()).isTrue();
    assertThat(result.isSuccess()).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
  }
}
