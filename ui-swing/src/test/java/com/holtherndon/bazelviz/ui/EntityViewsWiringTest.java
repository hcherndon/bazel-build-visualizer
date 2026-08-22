package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.storage.entities.OverviewSnapshot;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.actions.ActionRowSource;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.tests.TestRowSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 3 read path over a real imported session: import a synthetic BEP
 * stream, open it through the same {@link EntityReader} the views use, and
 * check that every view has something true to show.
 *
 * <p>The unit tests each cover one link — a query, a row source, an inspection.
 * This is the chain: bytes on disk to normalized rows to the objects the five
 * views render. It runs headless because nothing here realizes a peer.
 */
class EntityViewsWiringTest {

    private static final int EVENT_COUNT = 300;

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    @Test
    @Timeout(180)
    @DisplayName("an imported session feeds all five entity views")
    void importedSessionFeedsEveryView(@TempDir Path temporary) throws Exception {
        SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, stream);
        SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        ImportResult imported = new BepImporter(sessions).importFile(source);

        try (SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot());
                EntityReader reader = opened.openEntityReader()) {

            // --- overview -------------------------------------------------
            OverviewSnapshot overview = reader.overview();
            assertThat(overview.bazelVersion()).isPresent();
            assertThat(overview.command()).isPresent();
            assertThat(overview.targets()).isPositive();
            assertThat(overview.actions()).isPositive();
            assertThat(overview.artifacts()).isPositive();
            // The fixture's OptionsParsed does not set
            // --build_event_publish_all_actions, so the honest reading is that
            // it was off -- and the view says so about the options rather than
            // claiming anything about the rows it did receive.
            assertThat(overview.publishesAllActions()).hasValue(false);
            assertThat(overview.actionsAreFailuresOnly()).isTrue();

            // --- actions --------------------------------------------------
            ActionRowSource actions = ActionRowSource.open(
                    reader, ActionFilter.NONE, ActionSort.ARRIVAL, false, 50);
            assertThat(actions.rowCount()).isPositive();
            assertThat(actions.rowCount()).isEqualTo(overview.actions());

            List<ActionRow> firstPage = actions.fetchPage(0, 50).rows();
            assertThat(firstPage).isNotEmpty();
            assertThat(firstPage.getFirst().primaryOutput()).isNotBlank();
            // Every row can name the event it came from: the whole of
            // "event-to-domain provenance is inspectable".
            assertThat(firstPage).allMatch(row -> row.bepEventId().isPresent());

            // Walking the pages visits every row exactly once, which is the
            // property a wrong keyset breaks silently.
            List<Long> walked = new ArrayList<>();
            long pages = (actions.rowCount() + 49) / 50;
            for (long page = 0; page < pages; page++) {
                actions.fetchPage(page, 50).rows().forEach(row -> walked.add(row.id()));
            }
            assertThat(walked).hasSize((int) actions.rowCount()).doesNotHaveDuplicates();

            // --- targets --------------------------------------------------
            List<TargetQueries.PackageSummary> packages = reader.packages();
            assertThat(packages).isNotEmpty();
            long targetsInPackages =
                    packages.stream().mapToLong(TargetQueries.PackageSummary::targets).sum();
            assertThat(targetsInPackages).isEqualTo(overview.configuredTargets()
                    + overview.targetsNotCompleted());
            assertThat(reader.targetsInPackage(packages.getFirst().path())).isNotEmpty();

            // --- tests ----------------------------------------------------
            TestRowSource tests = TestRowSource.open(reader, 50);
            assertThat(tests.rowCount()).isEqualTo(overview.tests());
            if (tests.rowCount() > 0) {
                TestRow test = tests.fetchPage(0, 50).rows().getFirst();
                assertThat(test.label()).isNotBlank();
                // The verdict comes from the summary. The target's own success
                // flag is true for a failing test, so this must not be derived
                // from it.
                assertThat(test.overallStatus()).isNotNull();
                assertThat(reader.testAttempts(test.id())).isNotEmpty();
            }

            // --- failures -------------------------------------------------
            EntityReader.FailureCounts failures = reader.failureCounts();
            assertThat(failures.failedActions()).isEqualTo(overview.actionsFailed());
            assertThat(failures.aborted()).isEqualTo(overview.abortedEvents());
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("filtering narrows the rows and the count says so")
    void filteringReportsWhatItHides(@TempDir Path temporary) throws Exception {
        SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, stream);
        SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        ImportResult imported = new BepImporter(sessions).importFile(source);

        try (SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot());
                EntityReader reader = opened.openEntityReader()) {

            long total = reader.actionCount();
            assertThat(total).isPositive();
            String mnemonic = reader.mnemonics().getFirst().mnemonic();
            ActionFilter filter =
                    ActionFilter.NONE.withMnemonic(java.util.Optional.of(mnemonic));

            ActionRowSource filtered =
                    ActionRowSource.open(reader, filter, ActionSort.MNEMONIC, false, 50);

            // Both numbers are available to the status line, so a filtered
            // table can never be read as the whole build.
            assertThat(filtered.rowCount()).isEqualTo(reader.actionCount(filter));
            assertThat(filtered.unfilteredCount()).isEqualTo(total);
            assertThat(filtered.rowCount()).isLessThanOrEqualTo(total);
            assertThat(filtered.fetchPage(0, 50).rows())
                    .allMatch(row -> row.mnemonic().equals(java.util.Optional.of(mnemonic)));
        }
    }
}
