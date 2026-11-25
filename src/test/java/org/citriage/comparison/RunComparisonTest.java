package org.citriage.comparison;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

import org.citriage.comparison.PriorityService.GroupPriority;
import org.citriage.comparison.PriorityService.Priority;
import org.citriage.comparison.PriorityService.Tier;
import org.citriage.comparison.RunComparison.Failure;
import org.citriage.comparison.RunComparison.Status;
import org.citriage.log.BuildLog;
import org.citriage.log.LogClassifier;
import org.citriage.log.LogFinding;
import org.citriage.log.LogGrouper;
import org.citriage.log.LogReader;
import org.citriage.report.JUnitReport;
import org.citriage.report.JUnitXmlParser;
import org.citriage.report.ReportSource;
import org.citriage.report.TestCaseResult;
import org.citriage.report.TestOutcome;
import org.citriage.storage.RunFacts;
import org.citriage.storage.RunMetadata;
import org.citriage.storage.RunMetadata.Field;
import org.citriage.storage.RunMetadataFile;
import org.citriage.storage.SavedRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunComparisonTest {
    private final RunComparison service = new RunComparison();

    @Test
    void selectsLatestEarlierCompletionWithDeterministicTieBreakAndDifferentCommits() throws Exception {
        SavedRun current = fixtureRun("current");
        RunMetadata baseline = metadata("baseline");
        RunFacts old = facts("old", baseline);
        RunFacts second = facts("z-equal", field(baseline, Field.COMPLETED_AT, "2025-01-02T04:05:00-05:00"));
        RunFacts selected = facts("a-equal", field(baseline, Field.COMPLETED_AT, "2025-01-02T09:05:00Z"));
        RunFacts overlap = facts("overlap", field(baseline, Field.COMPLETED_AT, "2025-01-02T10:00:00Z"));

        var selection = service.select(current, List.of(second, old, overlap, selected));
        assertEquals("a-equal", selection.baseline().orElseThrow().run().id());
        assertEquals(List.of("old", "overlap", "z-equal"), selection.exclusions().stream()
                .map(RunComparison.Exclusion::runId).toList());
        assertTrue(selection.exclusions().get(0).reasons().get(0).contains("completed earlier"));
        assertTrue(selection.exclusions().get(1).reasons().get(0).contains("must precede"));
        assertTrue(selection.exclusions().get(2).reasons().get(0).contains("run ID sorts first"));
        assertEquals(selection, service.select(current, List.of(selected, overlap, second, old)));
    }

    @ParameterizedTest
    @EnumSource(value = Field.class, names = {"REPOSITORY", "WORKFLOW", "JOB", "ENVIRONMENT", "OS", "ARCHITECTURE",
            "TOOLCHAIN", "DEPENDENCY_FINGERPRINT", "TEST_COMMAND", "TEST_CONFIGURATION"})
    void everyCompatibilityFieldMustBeKnownAndMatch(Field key) throws Exception {
        SavedRun current = fixtureRun("current");
        RunMetadata before = metadata("baseline");
        var different = service.select(current, List.of(facts("old", field(before, key, "different"))));
        assertTrue(different.baseline().isEmpty());
        assertEquals(List.of("Different " + key + " in current and baseline metadata."), different.exclusions().get(0).reasons());

        SavedRun unknownCurrent = new SavedRun("current", Instant.EPOCH, field(current.metadata(), key, "unknown"),
                current.reports(), current.logs());
        var missing = service.select(unknownCurrent, List.of(facts("old", field(before, key, null))));
        assertTrue(missing.baseline().isEmpty());
        assertEquals(List.of("Unknown " + key + " in current and baseline metadata."), missing.exclusions().get(0).reasons());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            0 | 0 | 0 | 0 | SUCCESS | no JUnit executions
            3 | 0 | 0 | 0 | SUCCESS | no passed JUnit executions
            3 | 2 | 1 | 0 | SUCCESS | failed or errored JUnit executions
            3 | 3 | 0 | 1 | SUCCESS | contradictory log failure findings
            3 | 3 | 0 | 0 | FAILURE | Baseline CONCLUSION is FAILURE
            """)
    void rejectsIneligibleRecordedOutcomes(long total, long passed, long failed, long logFindings,
                                           String conclusion, String reason) throws Exception {
        RunFacts facts = new RunFacts("old", field(metadata("baseline"), Field.CONCLUSION, conclusion),
                total, passed, failed, logFindings);
        var selection = service.select(fixtureRun("current"), List.of(facts));
        assertTrue(selection.baseline().isEmpty());
        assertTrue(selection.exclusions().get(0).reasons().get(0).contains(reason));
    }

    @Test
    void requiresExplicitConclusionCompletionAndCurrentStart() throws Exception {
        SavedRun current = fixtureRun("current");
        SavedRun withoutStart = new SavedRun(current.id(), Instant.MAX, field(current.metadata(), Field.EXECUTED_AT, null),
                current.reports(), current.logs());
        RunMetadata before = field(field(metadata("baseline"), Field.CONCLUSION, null), Field.COMPLETED_AT, null);
        var selection = service.select(withoutStart, List.of(facts("old", before)));
        assertTrue(selection.baseline().isEmpty());
        assertEquals(List.of("Baseline CONCLUSION is unknown.", "Current EXECUTED_AT is missing or invalid.",
                "Baseline COMPLETED_AT is missing or invalid."), selection.exclusions().get(0).reasons());
        var invalidStart = service.select(current, List.of(facts("old", field(metadata("baseline"), Field.EXECUTED_AT, "legacy"))));
        assertTrue(invalidStart.baseline().isEmpty());
        assertEquals(List.of("Baseline EXECUTED_AT is invalid."), invalidStart.exclusions().get(0).reasons());
    }

    @Test
    void distinguishesNewUnseenAndUnobservedTests() throws Exception {
        SavedRun current = fixtureRun("current");
        SavedRun baseline = fixtureRun("baseline");
        var result = service.compareTests(current, Optional.of(baseline));

        assertEquals(List.of(Status.NEW_FAILURE, Status.UNSEEN_TEST_FAILURE), result.failures().stream().map(Failure::status).toList());
        assertEquals(List.of("A", "D"), result.failures().stream().map(failure -> failure.current().name()).toList());
        assertEquals(List.of("C"), result.notObservedNow().stream().map(TestCaseResult::name).toList());
        assertSame(baseline.reports().get(0).report().testCases().get(0), result.failures().get(0).baseline().orElseThrow());
    }

    @Test
    void preservesDuplicatesAndExplainsSkippedAndInsufficientIdentities() throws Exception {
        SavedRun before = xmlRun("before", """
                <testsuite name="suite">
                  <testcase classname="example.Test" name="Skipped"><skipped/></testcase>
                  <testcase classname="example.Test" name="Duplicate"/>
                  <testcase classname="example.Test" name="Duplicate"/>
                  <testcase classname="example.Test" name="Repeated"/>
                  <testcase name="MissingPriorClass"/>
                </testsuite>
                """);
        SavedRun now = xmlRun("now", """
                <testsuite name="suite">
                  <testcase classname="example.Test" name="Skipped"><error/></testcase>
                  <testcase classname="example.Test" name="Duplicate"><failure/></testcase>
                  <testcase classname="example.Test" name="Repeated"><failure/></testcase>
                  <testcase classname="example.Test" name="Repeated"><failure/></testcase>
                  <testcase classname="example.Test" name="MissingPriorClass"><failure/></testcase>
                  <testcase name="MissingCurrentClass"><failure/></testcase>
                </testsuite>
                """);
        var result = service.compareTests(now, Optional.of(before));
        assertEquals(List.of(Status.NO_PRIOR_PASS, Status.AMBIGUOUS, Status.AMBIGUOUS, Status.AMBIGUOUS,
                Status.AMBIGUOUS, Status.AMBIGUOUS), result.failures().stream().map(Failure::status).toList());
        assertEquals(TestOutcome.SKIPPED, result.failures().get(0).baseline().orElseThrow().outcome());
        assertTrue(result.notObservedNow().isEmpty());
    }

    @Test
    void suiteContextDistinguishesSameClassAndNameWhilePathsAndPositionsDoNot() throws Exception {
        SavedRun before = xmlRun("before", """
                <testsuites>
                  <testsuite name="unit"><testcase classname="example.Test" name="same"/></testsuite>
                  <testsuite name="integration"><testcase classname="example.Test" name="same"><skipped/></testcase></testsuite>
                </testsuites>
                """);
        SavedRun now = xmlRun("after", """
                <testsuites>

                  <testsuite name="integration"><testcase classname="example.Test" name="same"><failure/></testcase></testsuite>
                  <testsuite name="unit"><testcase classname="example.Test" name="same"><failure/></testcase></testsuite>
                </testsuites>
                """);
        assertEquals(List.of(Status.NO_PRIOR_PASS, Status.NEW_FAILURE), service.compareTests(now, Optional.of(before))
                .failures().stream().map(Failure::status).toList());
        SavedRun missingContext = xmlRun("after-unnamed", """
                <testsuite><testcase classname="example.Test" name="same"><failure/></testcase></testsuite>
                """);
        assertEquals(Status.AMBIGUOUS, service.compareTests(missingContext, Optional.of(before)).failures().get(0).status());
    }

    @Test
    void ranksEvidenceAndLinksOnlyUnambiguousReportedIdentities() throws Exception {
        SavedRun current = fixtureRun("current");
        BuildLog log = new LogReader().read(fixture("current.log"));
        List<LogFinding> findings = new LogClassifier().classify(log);
        SavedRun.SavedLog savedLog = new SavedRun.SavedLog(Instant.EPOCH, log, findings, new LogGrouper().group(findings));
        SavedRun now = new SavedRun(current.id(), current.firstImportedAt(), current.metadata(), current.reports(), List.of(savedLog));
        List<Failure> changes = service.compareTests(now, Optional.of(fixtureRun("baseline"))).failures();
        List<Priority> priorities = new PriorityService().prioritize(now, changes);

        assertEquals(List.of(Tier.BLOCKING_BUILD_OR_SETUP, Tier.CONFIRMED_NEW_FAILURE, Tier.MULTIPLE_KNOWN_TESTS,
                Tier.OBSERVED_TEST_FAILURE, Tier.OBSERVED_LOG_DIAGNOSTIC), priorities.stream().map(Priority::tier).toList());
        assertTrue(priorities.get(0).reason().contains("maven-dependency-resolution"));
        GroupPriority assertionGroup = (GroupPriority) priorities.get(2);
        assertEquals(3, assertionGroup.group().occurrenceCount());
        assertEquals(2, assertionGroup.group().distinctKnownTestCount());
        assertTrue(assertionGroup.reason().contains("2 distinct named tests"));
        assertEquals(List.of("A", "D", "A"), assertionGroup.members().stream()
                .map(member -> member.junitTest().orElseThrow().name()).toList());
        GroupPriority unknown = (GroupPriority) priorities.get(4);
        assertEquals(1, unknown.group().unknownTestOccurrenceCount());
        assertTrue(unknown.members().get(0).junitTest().isEmpty());
        assertSame(log.source(), assertionGroup.members().get(0).finding().evidence().source());

        var repeatedReport = xmlRun("other-suite", """
                <testsuite name="also-checkout"><testcase classname="example.CheckoutTest" name="A"/></testsuite>
                """).reports().get(0);
        SavedRun ambiguous = new SavedRun(now.id(), Instant.EPOCH, now.metadata(), List.of(now.reports().get(0), repeatedReport),
                now.logs());
        List<Priority> withoutBaseline = new PriorityService().prioritize(ambiguous,
                service.compareTests(ambiguous, Optional.empty()).failures());
        GroupPriority ambiguousGroup = (GroupPriority) withoutBaseline.get(1);
        assertTrue(ambiguousGroup.members().get(0).junitTest().isEmpty());
        assertEquals("D", ambiguousGroup.members().get(1).junitTest().orElseThrow().name());
        assertFalse(withoutBaseline.stream().anyMatch(priority -> priority.tier() == Tier.CONFIRMED_NEW_FAILURE));
    }

    private static RunFacts facts(String id, RunMetadata metadata) {
        return new RunFacts(id, metadata, 3, 3, 0, 0);
    }

    private static SavedRun fixtureRun(String name) throws Exception {
        return run(name, metadata(name), new JUnitXmlParser().parse(fixture(name + ".xml")));
    }

    private static SavedRun xmlRun(String name, String xml) throws Exception {
        return run(name, metadata("current"), new JUnitXmlParser().parse(
                new ReportSource(name + ".xml", xml.getBytes(StandardCharsets.UTF_8))));
    }

    private static SavedRun run(String name, RunMetadata metadata, JUnitReport report) {
        return new SavedRun(name, Instant.EPOCH, metadata, List.of(new SavedRun.SavedReport(Instant.EPOCH, report)), List.of());
    }

    private static RunMetadata metadata(String name) throws Exception {
        return new RunMetadataFile().read(fixture(name + ".properties"));
    }

    private static RunMetadata field(RunMetadata metadata, Field field, String value) {
        var fields = new EnumMap<>(metadata.values());
        if (value == null) {
            fields.remove(field);
        } else {
            fields.put(field, value);
        }
        return new RunMetadata(fields);
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(RunComparisonTest.class.getResource("/fixtures/comparison/" + name).toURI());
    }
}
