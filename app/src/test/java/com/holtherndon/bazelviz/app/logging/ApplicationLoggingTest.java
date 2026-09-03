package com.holtherndon.bazelviz.app.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.holtherndon.bazelviz.ui.logging.LogVerbosity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationLoggingTest {

  @TempDir Path temp;

  private final List<LoggerContext> contexts = new ArrayList<>();

  @AfterEach
  void stopContexts() {
    contexts.forEach(LoggerContext::stop);
  }

  @Test
  void processOverrideWinsCaseInsensitivelyWithoutChangingSavedValue() {
    LoggerContext context = context();

    try (ApplicationLogging logging =
        ApplicationLogging.start(temp.resolve("logs"), LogVerbosity.WARN, "dEbUg", context)) {
      assertThat(logging.available()).isTrue();
      assertThat(logging.verbosity()).isEqualTo(LogVerbosity.DEBUG);
      assertThat(logging.startupWarning()).isEmpty();
      assertThat(logging.currentLog())
          .isEqualTo(temp.resolve("logs/application.log").toAbsolutePath());
    }
  }

  @Test
  void invalidOverrideFallsBackToSavedAndWritesAWarning() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("invalid");
    ApplicationLogging logging =
        ApplicationLogging.start(logs, LogVerbosity.ERROR, "everything", context);

    assertThat(logging.verbosity()).isEqualTo(LogVerbosity.ERROR);
    assertThat(logging.startupWarning())
        .hasValueSatisfying(
            warning -> assertThat(warning).contains("everything").contains("saved Error"));

    logging.close();

    assertThat(Files.readString(logs.resolve("application.log")))
        .contains("Unknown bbv.log.level value 'everything'")
        .contains("WARN");
  }

  @Test
  void preLoggingConfigurationWarningIsRecordedEvenAtError() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("settings-warning");
    ApplicationLogging logging = ApplicationLogging.start(logs, LogVerbosity.ERROR, null, context);

    logging.recordConfigurationWarning("Saved logging settings are invalid; Info will be used.");
    logging.close();

    assertThat(Files.readString(logs.resolve("application.log")))
        .contains("Saved logging settings are invalid; Info will be used.")
        .contains("WARN");
  }

  @Test
  void controlTextCannotForgeAdditionalPhysicalLogRecords() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("control-safe");
    ApplicationLogging logging = ApplicationLogging.start(logs, LogVerbosity.INFO, null, context);
    Logger logger = context.getLogger("com.holtherndon.bazelviz.test.controls");

    logger.warn(
        "hostile path {}",
        "first\n2099-01-01 forged\u001b[2J\tlast",
        new IllegalArgumentException("failure\r\nforged exception"));
    logging.close();

    String contents = Files.readString(logs.resolve("application.log"));
    assertThat(contents)
        .contains("first\\n2099-01-01 forged\\u001b[2J\\tlast")
        .contains("failure\\r\\nforged exception")
        .doesNotContain("\u001b")
        .doesNotContain("\n2099-01-01 forged");
    assertThat(contents.lines()).hasSize(2);
  }

  @Test
  void acceptedRecordsReachTheFileBeforeCloseReturns() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("flush");
    ApplicationLogging logging = ApplicationLogging.start(logs, LogVerbosity.INFO, null, context);
    Logger logger = context.getLogger("com.holtherndon.bazelviz.test.flush");

    logger.info("accepted record");
    logger.debug("filtered record");
    logging.close();

    assertThat(Files.readString(logs.resolve("application.log")))
        .contains("accepted record")
        .doesNotContain("filtered record");
    assertThat(logging.available()).isFalse();
  }

  @Test
  void verbosityChangesTheApplicationNamespaceImmediately() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("dynamic");
    ApplicationLogging logging = ApplicationLogging.start(logs, LogVerbosity.WARN, null, context);
    Logger logger = context.getLogger("com.holtherndon.bazelviz.test.dynamic");

    logger.info("before level change");
    logging.setVerbosity(LogVerbosity.DEBUG);
    logger.debug("after level change");
    assertThat(logging.verbosity()).isEqualTo(LogVerbosity.DEBUG);
    logging.close();

    assertThat(Files.readString(logs.resolve("application.log")))
        .doesNotContain("before level change")
        .contains("after level change");
  }

  @Test
  void duplicateStartIsRejectedInsteadOfAttachingASecondWriter() {
    LoggerContext context = context();
    ApplicationLogging first =
        ApplicationLogging.start(temp.resolve("duplicate"), LogVerbosity.INFO, null, context);
    try {
      assertThatThrownBy(
              () ->
                  ApplicationLogging.start(
                      temp.resolve("duplicate"), LogVerbosity.INFO, null, context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already started");
    } finally {
      first.close();
    }
  }

  @Test
  void concurrentProcessCannotShareTheRollingDestination() {
    Path logs = temp.resolve("single-writer");
    ApplicationLogging first = ApplicationLogging.start(logs, LogVerbosity.INFO, null, context());
    ApplicationLogging second = ApplicationLogging.start(logs, LogVerbosity.INFO, null, context());

    assertThat(first.available()).isTrue();
    assertThat(second.available()).isFalse();
    assertThat(second.startupWarning())
        .hasValueSatisfying(
            warning ->
                assertThat(warning)
                    .contains("another application instance")
                    .contains("application log"));

    first.close();
    ApplicationLogging afterClose =
        ApplicationLogging.start(logs, LogVerbosity.INFO, null, context());
    assertThat(afterClose.available()).isTrue();
    afterClose.close();
  }

  @Test
  void traceDoesNotEnableDependencyInternals() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("namespace");
    ApplicationLogging logging = ApplicationLogging.start(logs, LogVerbosity.TRACE, null, context);

    context.getLogger("com.holtherndon.bazelviz.test.trace").trace("application trace record");
    context.getLogger("org.sqlite.core.NativeDB").trace("dependency trace with private SQL");
    context.getLogger("org.sqlite.core.NativeDB").warn("dependency warning");
    logging.close();

    assertThat(Files.readString(logs.resolve("application.log")))
        .contains("application trace record")
        .contains("dependency warning")
        .doesNotContain("dependency trace with private SQL");
  }

  @Test
  void rollingPolicyCreatesArchivesAndEnforcesTheirByteCap() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("rollover");
    long archiveCap = 4_096;
    ApplicationLogging logging =
        ApplicationLogging.start(
            logs,
            LogVerbosity.INFO,
            null,
            context,
            new ApplicationLogging.LoggingPolicy(1_024, 1, archiveCap));
    Logger logger = context.getLogger("com.holtherndon.bazelviz.test.rollover");

    for (int index = 0; index < 400; index++) {
      logger.info("rolling record {} {}", index, UUID.randomUUID().toString().repeat(8));
    }
    logging.close();

    List<Path> archives;
    try (var children = Files.list(logs)) {
      archives =
          children.filter(path -> path.getFileName().toString().endsWith(".log.gz")).toList();
    }
    assertThat(archives).isNotEmpty();
    long archiveBytes = 0;
    for (Path archive : archives) {
      archiveBytes += Files.size(archive);
    }
    assertThat(archiveBytes).isLessThanOrEqualTo(archiveCap);
    assertThat(logs.resolve("application.log")).isNotEmptyFile();
  }

  @Test
  void historyPolicyRemovesAnArchiveOutsideTheRetainedPeriod() throws Exception {
    LoggerContext context = context();
    Path logs = temp.resolve("history");
    Files.createDirectories(logs);
    Path stale = logs.resolve("application.2000-01-01.0.log.gz");
    Files.writeString(stale, "stale");
    Files.setLastModifiedTime(stale, FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));

    ApplicationLogging logging =
        ApplicationLogging.start(
            logs,
            LogVerbosity.INFO,
            null,
            context,
            new ApplicationLogging.LoggingPolicy(1_024, 1, 1_048_576));
    context.getLogger("com.holtherndon.bazelviz.test.history").info("current record");
    logging.close();

    assertThat(stale).doesNotExist();
  }

  @Test
  void fullQueueNeverBlocksCallerAndReportsExactLoss() throws Exception {
    LoggerContext context = context();
    BlockingAppender destination = new BlockingAppender();
    destination.setContext(context);
    destination.start();
    LossAwareAsyncAppender async =
        new LossAwareAsyncAppender(destination, 1, Duration.ofSeconds(2));
    async.setContext(context);
    async.start();

    async.doAppend(event(context, "writer blocker"));
    assertThat(destination.writerEntered.await(2, TimeUnit.SECONDS)).isTrue();
    async.doAppend(event(context, "queued"));

    long started = System.nanoTime();
    for (int i = 0; i < 10; i++) {
      async.doAppend(event(context, "dropped " + i));
    }
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(elapsedMillis).isLessThan(250);
    assertThat(async.droppedRecordCount()).isEqualTo(10);
    destination.releaseWriter.countDown();
    async.stop();

    assertThat(destination.messages())
        .anySatisfy(
            message ->
                assertThat(message)
                    .startsWith(LossAwareAsyncAppender.OVERFLOW_PREFIX)
                    .contains("exactly 10 record(s)"))
        .anySatisfy(
            message ->
                assertThat(message)
                    .isEqualTo(
                        LossAwareAsyncAppender.SHUTDOWN_PREFIX + "10 record(s) in this process."));
  }

  @Test
  void appendRacingWithStopIsEitherWrittenOrExactlyCounted() throws Exception {
    LoggerContext context = context();
    CollectingAppender destination = new CollectingAppender();
    destination.setContext(context);
    destination.start();
    CountDownLatch beforeOffer = new CountDownLatch(1);
    CountDownLatch releaseOffer = new CountDownLatch(1);
    LossAwareAsyncAppender async =
        new LossAwareAsyncAppender(
            destination,
            2,
            Duration.ofSeconds(2),
            () -> {
              beforeOffer.countDown();
              awaitLatch(releaseOffer, "test did not release the append");
            });
    async.setContext(context);
    async.start();

    Thread producer =
        Thread.ofPlatform()
            .name("logging-race-producer")
            .start(() -> async.doAppend(event(context, "racing record")));
    assertThat(beforeOffer.await(2, TimeUnit.SECONDS)).isTrue();
    Thread stopper = Thread.ofPlatform().name("logging-race-stopper").start(async::stop);
    awaitCondition(() -> !async.isStarted(), "stop did not close the acceptance gate");
    releaseOffer.countDown();
    producer.join(2_000);
    stopper.join(2_000);

    assertThat(producer.isAlive()).isFalse();
    assertThat(stopper.isAlive()).isFalse();
    long delivered = destination.messages().stream().filter("racing record"::equals).count();
    assertThat(delivered + async.droppedRecordCount()).isEqualTo(1);
    if (async.droppedRecordCount() == 1) {
      assertThat(destination.messages())
          .contains(LossAwareAsyncAppender.SHUTDOWN_PREFIX + "1 record(s) in this process.");
    }
  }

  @Test
  void stopDeadlineCountsEveryQueuedRecordBeforeReturning() throws Exception {
    LoggerContext context = context();
    BlockingAppender destination = new BlockingAppender();
    destination.setContext(context);
    destination.start();
    LossAwareAsyncAppender async =
        new LossAwareAsyncAppender(destination, 4, Duration.ofMillis(50));
    async.setContext(context);
    async.start();

    async.doAppend(event(context, "writer blocker"));
    assertThat(destination.writerEntered.await(2, TimeUnit.SECONDS)).isTrue();
    async.doAppend(event(context, "queued one"));
    async.doAppend(event(context, "queued two"));

    long started = System.nanoTime();
    async.stop();
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(elapsedMillis).isLessThan(500);
    assertThat(async.droppedRecordCount()).isEqualTo(2);
    destination.releaseWriter.countDown();
    assertThat(destination.stopped.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(destination.messages())
        .contains("writer blocker")
        .doesNotContain("queued one", "queued two")
        .anySatisfy(
            message ->
                assertThat(message)
                    .isEqualTo(
                        LossAwareAsyncAppender.SHUTDOWN_PREFIX + "2 record(s) in this process."));
  }

  private LoggerContext context() {
    LoggerContext context = new LoggerContext();
    context.setName("application-logging-test-" + contexts.size());
    context.setMDCAdapter(new LogbackMDCAdapter());
    context.start();
    contexts.add(context);
    return context;
  }

  private static ILoggingEvent event(LoggerContext context, String message) {
    Logger logger = context.getLogger("test.loss");
    return new LoggingEvent(
        ApplicationLoggingTest.class.getName(), logger, Level.INFO, message, null, null);
  }

  private static void awaitLatch(CountDownLatch latch, String timeoutMessage) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError(timeoutMessage);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test thread interrupted", interrupted);
    }
  }

  private static void awaitCondition(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(condition.getAsBoolean()).as(message).isTrue();
  }

  private static final class CollectingAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      events.add(event);
    }

    List<String> messages() {
      return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
  }

  private static final class BlockingAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private final CountDownLatch writerEntered = new CountDownLatch(1);
    private final CountDownLatch releaseWriter = new CountDownLatch(1);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      if (first.compareAndSet(true, false)) {
        writerEntered.countDown();
        try {
          if (!releaseWriter.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("test did not release the writer");
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("writer interrupted", interrupted);
        }
      }
      events.add(event);
    }

    @Override
    public void stop() {
      super.stop();
      stopped.countDown();
    }

    List<String> messages() {
      return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
  }
}
