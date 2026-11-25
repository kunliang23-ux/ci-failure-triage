package org.citriage.log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.citriage.report.TestIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogClassifierTest {
    private final LogClassifier classifier = new LogClassifier();
    private final LogReader reader = new LogReader();

    @ParameterizedTest
    @CsvSource({
            "assertion.log, ASSERTION_ERROR, java-assertion, 3, 5",
            "dependency.log, MISSING_DEPENDENCY, maven-dependency-resolution, 3, 5",
            "compilation.log, COMPILATION_ERROR, java-compiler, 4, 6",
            "timeout.log, TIMEOUT, java-timeout, 3, 5",
            "unknown.log, UNKNOWN, unclassified-error, 2, 2"
    })
    void specificAndLocalDiagnosticsTakePrecedenceOverSummaries(String name, DiagnosticCategory category,
                                                                 String rule, int first, int last) throws Exception {
        BuildLog log = reader.read(fixture(name));
        List<LogFinding> findings = classifier.classify(log);

        assertEquals(1, findings.size());
        LogFinding finding = findings.get(0);
        assertAll(
                () -> assertEquals(category, finding.category()),
                () -> assertEquals(rule, finding.ruleId()),
                () -> assertEquals(log.source().name(), finding.evidence().source().name()),
                () -> assertEquals(first, finding.evidence().firstLine()),
                () -> assertEquals(last, finding.evidence().lastLine()),
                () -> assertEquals(log.lines().subList(first - 1, last), finding.evidence().lines()),
                () -> assertFalse(finding.evidence().originalText().contains("BUILD FAILURE")));
    }

    @Test
    void mixedLogRetainsIndependentFindingsAndAttributesOnlyTheExplicitTest() throws Exception {
        List<LogFinding> findings = classifier.classify(reader.read(fixture("mixed.log")));

        assertAll(
                () -> assertEquals(List.of(DiagnosticCategory.ASSERTION_ERROR, DiagnosticCategory.MISSING_DEPENDENCY,
                                DiagnosticCategory.UNKNOWN, DiagnosticCategory.COMPILATION_ERROR),
                        findings.stream().map(LogFinding::category).toList()),
                () -> assertEquals(List.of(2, 6, 7, 9),
                        findings.stream().map(finding -> finding.evidence().firstLine()).toList()),
                () -> assertEquals(List.of(4, 6, 7, 9),
                        findings.stream().map(finding -> finding.evidence().lastLine()).toList()),
                () -> assertEquals(Optional.of(new TestIdentity(Optional.of("example.CartTest"), "calculatesTotal")),
                        findings.get(0).testIdentity()),
                () -> assertTrue(findings.stream().skip(1).allMatch(finding -> finding.testIdentity().isEmpty())));
    }

    @Test
    void successfulLogWithTimeoutMentionHasNoFindings() throws Exception {
        assertTrue(classifier.classify(reader.read(fixture("passing.log"))).isEmpty());
    }

    @Test
    void emptyLogHasNoFindings(@TempDir Path directory) throws Exception {
        assertTrue(classify("", directory).isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            [ERROR] BUILD FAILURE | build-failure-summary
            [ERROR] Failed to execute goal fixture:test on project fixture: | build-failure-summary
            [INFO] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0 | surefire-failure-summary
            Process completed with exit code 1. | build-failure-summary
            """)
    void summaryOnlyFailureHasUnknownFindingWithItsOriginalLine(String summary, String rule,
                                                                 @TempDir Path directory) throws Exception {
        List<LogFinding> findings = classify(summary, directory);

        assertEquals(1, findings.size());
        LogFinding finding = findings.get(0);
        assertAll(
                () -> assertEquals(DiagnosticCategory.UNKNOWN, finding.category()),
                () -> assertEquals(rule, finding.ruleId()),
                () -> assertEquals(1, finding.evidence().firstLine()),
                () -> assertEquals(1, finding.evidence().lastLine()),
                () -> assertEquals(summary, finding.evidence().originalText()));
    }

    @Test
    void aLaterMavenRunWithOnlyAFailureSummaryKeepsItsOwnFinding(@TempDir Path directory) throws Exception {
        String text = """
                [INFO] Scanning for projects...
                java.lang.AssertionError: synthetic assertion
                [INFO] BUILD FAILURE
                [INFO] Scanning for projects...
                [INFO] BUILD FAILURE
                """;
        List<LogFinding> findings = classify(text, directory);

        assertEquals(List.of(DiagnosticCategory.ASSERTION_ERROR, DiagnosticCategory.UNKNOWN),
                findings.stream().map(LogFinding::category).toList());
        assertEquals(List.of(2, 5), findings.stream().map(finding -> finding.evidence().firstLine()).toList());
    }

    @Test
    void genericMavenGoalAndHelpLinesDoNotRepeatOneSpecificDiagnostic(@TempDir Path directory) throws Exception {
        String text = """
                java.lang.AssertionError: synthetic assertion
                [ERROR] Failed to execute goal fixture:test on project fixture:
                [ERROR] Please refer to the synthetic test results.
                [ERROR] -> [Help 1]
                """;
        List<LogFinding> findings = classify(text, directory);

        assertEquals(1, findings.size());
        assertEquals(DiagnosticCategory.ASSERTION_ERROR, findings.get(0).category());
        assertEquals(1, findings.get(0).evidence().lastLine());
    }

    @Test
    void junitTimeoutMessageTakesPrecedenceOverItsAssertionType(@TempDir Path directory) throws Exception {
        List<LogFinding> findings = classify(
                "org.opentest4j.AssertionFailedError: execution timed out after 50 ms", directory);

        assertEquals(List.of(DiagnosticCategory.TIMEOUT), findings.stream().map(LogFinding::category).toList());
    }

    @Test
    void supportedNestedExceptionDoesNotInventTestIdentityFromAStackFrame(@TempDir Path directory) throws Exception {
        String text = """
                java.util.concurrent.ExecutionException: synthetic wrapper
                    at example.SampleTest.loadsFixture(SampleTest.java:17)
                Caused by: java.lang.ClassNotFoundException: example.FixtureDriver
                    at example.Loader.load(Loader.java:23)
                """;
        List<LogFinding> findings = classify(text, directory);

        assertEquals(1, findings.size());
        LogFinding finding = findings.get(0);
        assertAll(
                () -> assertEquals(DiagnosticCategory.MISSING_DEPENDENCY, finding.category()),
                () -> assertEquals("java-class-loading", finding.ruleId()),
                () -> assertEquals(Optional.empty(), finding.testIdentity()),
                () -> assertEquals(text, finding.evidence().originalText()));
    }

    @Test
    void initializationErrorDoesNotClaimThatADependencyIsMissing(@TempDir Path directory) throws Exception {
        List<LogFinding> findings = classify(
                "java.lang.NoClassDefFoundError: Could not initialize class example.FixtureDriver", directory);

        assertEquals(List.of(DiagnosticCategory.UNKNOWN), findings.stream().map(LogFinding::category).toList());
    }

    @Test
    void javacDiagnosticIncludesItsSourceAndCaretContext(@TempDir Path directory) throws Exception {
        String evidence = """
                src/Example.java:8: error: cannot find symbol
                    Missing value;
                    ^
                  symbol:   class Missing
                  location: class Example
                """;
        List<LogFinding> findings = classify(evidence + "[INFO] BUILD FAILURE\n", directory);

        assertEquals(1, findings.size());
        LogFinding finding = findings.get(0);
        assertEquals(DiagnosticCategory.COMPILATION_ERROR, finding.category());
        assertEquals(evidence, finding.evidence().originalText());
    }

    @Test
    void matchingThroughBomAndColorKeepsOriginalCompositionOfAnEvidenceRange(@TempDir Path directory)
            throws Exception {
        String evidence = "\uFEFF\u001b[31m[ERROR]\u001b[0m java.lang.AssertionError: caf\u00e9\r\n"
                + "\tat example.CartTest.total(CartTest.java:24)";
        List<LogFinding> findings = classify(evidence, directory);

        assertEquals(1, findings.size());
        LogFinding finding = findings.get(0);
        assertAll(
                () -> assertEquals(DiagnosticCategory.ASSERTION_ERROR, finding.category()),
                () -> assertEquals(1, finding.evidence().firstLine()),
                () -> assertEquals(2, finding.evidence().lastLine()),
                () -> assertEquals(evidence, finding.evidence().originalText()),
                () -> assertThrows(UnsupportedOperationException.class, () -> finding.evidence().lines().clear()));
    }

    private List<LogFinding> classify(String text, Path directory) throws Exception {
        return classifier.classify(reader.read(Files.writeString(directory.resolve("synthetic.log"), text)));
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(LogClassifierTest.class.getResource("/fixtures/logs/" + name).toURI());
    }
}
