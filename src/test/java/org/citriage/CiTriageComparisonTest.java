package org.citriage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiTriageComparisonTest {
    @TempDir
    Path directory;

    @Test
    void comparesStoredEvidenceAfterRemovingTemporarySourceCopies() throws Exception {
        Path db = directory.resolve("triage comparison.sqlite");
        Path baseline = copyFixture("baseline.xml");
        Path current = copyFixture("current.xml");
        Path log = copyFixture("current.log");
        assertOutput(invoke("import-junit", baseline.toString(), "--db", db.toString(), "--run-id", "baseline",
                "--metadata", fixture("baseline.properties"), "--commit", "1111111"), "artifact saved");
        saveReport(db, "current", current.toString(), fixture("current.properties"));
        assertOutput(invoke("import-log", log.toString(), "--db", db.toString(), "--run-id", "current", "--metadata",
                fixture("current.properties"), "--group"), "Commit: 2222222", "Environment: sample CI", "Groups: 3");
        Files.delete(baseline);
        Files.delete(current);
        Files.delete(log);

        byte[] before = Files.readAllBytes(db);
        Result automatic = invoke("compare-runs", "current", "--db", db.toString());
        assertOutput(automatic, "Baseline: baseline", "latest eligible completion", "NEW_FAILURE example.CheckoutTest#A",
                "UNSEEN_TEST_FAILURE example.CheckoutTest#D", "NOT_OBSERVED_NOW example.CheckoutTest#C",
                "BLOCKING_BUILD_OR_SETUP", "CONFIRMED_NEW_FAILURE example.CheckoutTest#A", "MULTIPLE_KNOWN_TESTS",
                "Occurrences: 3 | Distinct known tests: 2 | Unknown test occurrences: 0",
                "Linked JUnit test: example.CheckoutTest#A", "Log test: unknown", "Evidence: " + log.getFileName() + ":5-7",
                "6 | [ERROR] dependency:", "XML diagnostic location: " + current.getFileName(), "expected: <42> but was: <41>");
        assertFalse(automatic.out().contains(directory.toAbsolutePath().normalize().toString()));
        assertOutput(invoke("compare-runs", "current", "--baseline", "baseline", "--db", db.toString()),
                "Baseline: baseline", "the requested run met all eligibility requirements");
        assertArrayEquals(before, Files.readAllBytes(db));
        assertEquals(automatic, invoke("compare-runs", "current", "--db", db.toString()));
    }

    @Test
    void explicitIneligibleBaselineKeepsPrioritiesAndNeverSelectsAnAlternative() throws Exception {
        Path db = directory.resolve("triage.sqlite");
        saveReport(db, "baseline", fixture("baseline.xml"), fixture("baseline.properties"));
        saveReport(db, "incompatible", fixture("baseline.xml"), metadataFile("different.properties",
                Files.readString(Path.of(fixture("baseline.properties"))).replace("OS=Linux", "OS=Windows")));
        saveReport(db, "current", fixture("current.xml"), fixture("current.properties"));
        assertOutput(invoke("compare-runs", "current", "--db", db.toString()), "Baseline: baseline", "Different OS");

        Result explicit = invoke("compare-runs", "current", "--db", db.toString(), "--baseline", "incompatible");
        assertOutput(explicit, "Requested baseline: incompatible", "Comparison: unavailable", "Different OS",
                "UNAVAILABLE example.CheckoutTest#A", "OBSERVED_TEST_FAILURE example.CheckoutTest#D");
        assertFalse(explicit.out().contains("Baseline: baseline"));
        assertFalse(explicit.out().contains("Not observed now:"));
    }

    @Test
    void missingMetadataStillReportsBlockingAndMultiTestPriorities() throws Exception {
        Path db = directory.resolve("triage.sqlite");
        saveReport(db, "baseline", fixture("baseline.xml"), fixture("baseline.properties"));
        assertOutput(invoke("import-junit", fixture("current.xml"), "--db", db.toString(), "--run-id", "unknown"), "artifact saved");
        assertOutput(invoke("import-log", fixture("current.log"), "--db", db.toString(), "--run-id", "unknown"), "artifact saved");

        Result comparison = invoke("compare-runs", "unknown", "--db", db.toString());
        assertOutput(comparison, "Comparison: unavailable", "Current EXECUTED_AT is missing or invalid",
                "Unknown REPOSITORY in current metadata", "BLOCKING_BUILD_OR_SETUP", "MULTIPLE_KNOWN_TESTS",
                "OBSERVED_TEST_FAILURE example.CheckoutTest#A");
        assertFalse(comparison.out().contains("CONFIRMED_NEW_FAILURE"));
    }

    @Test
    void metadataConflictsAndChronologyErrorsRollbackNewFieldsAndSources() throws Exception {
        Path db = directory.resolve("triage.sqlite");
        saveReport(db, "baseline", fixture("baseline.xml"), fixture("baseline.properties"));
        Result before = invoke("show-run", "baseline", "--db", db.toString());
        String conflict = metadataFile("conflict.properties", "ATTEMPT=2\nCONCLUSION=FAILURE\n");
        assertError(invoke("import-junit", fixture("current.xml"), "--db", db.toString(), "--run-id", "baseline",
                "--metadata", conflict), 1, "Conflicting Run conclusion");
        assertEquals(before, invoke("show-run", "baseline", "--db", db.toString()));

        saveReport(db, "unfinished", fixture("baseline.xml"), metadataFile("start.properties", "EXECUTED_AT=2025-01-02T10:00:00Z\n"));
        before = invoke("show-run", "unfinished", "--db", db.toString());
        String invalidEnd = metadataFile("early-end.properties", "ENVIRONMENT=sample CI\nCOMPLETED_AT=2025-01-02T09:00:00Z\n");
        assertError(invoke("import-log", fixture("current.log"), "--db", db.toString(), "--run-id", "unfinished",
                "--metadata", invalidEnd), 1, "COMPLETED_AT precedes EXECUTED_AT");
        assertEquals(before, invoke("show-run", "unfinished", "--db", db.toString()));

        String validEnd = metadataFile("valid-end.properties", "COMPLETED_AT=2025-01-02T10:01:00Z\nCONCLUSION=SUCCESS\n");
        assertOutput(invoke("import-junit", fixture("baseline.xml"), "--db", db.toString(), "--run-id", "unfinished",
                "--metadata", validEnd), "artifact already saved");
        assertOutput(invoke("show-run", "unfinished", "--db", db.toString()), "Completion time: 2025-01-02T10:01:00Z",
                "Run conclusion: SUCCESS", "JUnit executions: 3 | Log findings: 0");
    }

    @Test
    void validatesMetadataInputBeforeOpeningTheDestinationDatabase() throws Exception {
        Path db = directory.resolve("uncreated.sqlite");
        assertError(invoke("import-junit", fixture("baseline.xml"), "--db", db.toString(), "--metadata",
                fixture("baseline.properties"), "--commit", "different"), 1, "Conflicting COMMIT metadata");
        Path bad = Files.write(directory.resolve("invalid.properties"), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertError(invoke("import-log", fixture("current.log"), "--db", db.toString(), "--metadata", bad.toString()), 1,
                "expected valid UTF-8");
        assertError(invoke("import-log", fixture("current.log"), "--db", db.toString(), "--metadata",
                directory.resolve("absent.properties").toString()), 1, "Cannot use metadata");
        assertFalse(Files.exists(db));
    }

    @Test
    void reportsMissingDatabaseCurrentAndExplicitBaselineAsErrors() throws Exception {
        Path db = directory.resolve("triage.sqlite");
        assertError(invoke("compare-runs", "absent", "--db", db.toString()), 1, "Database does not exist");
        assertFalse(Files.exists(db));
        saveReport(db, "current", fixture("current.xml"), fixture("current.properties"));
        assertError(invoke("compare-runs", "absent", "--db", db.toString()), 1, "Run 'absent' does not exist");
        assertError(invoke("compare-runs", "current", "--db", db.toString(), "--baseline", "absent"), 1,
                "Baseline run 'absent' does not exist");
        assertOutput(invoke("compare-runs", "current", "--db", db.toString(), "--baseline", "current"),
                "Comparison: unavailable", "The current run cannot be its own baseline");
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidUsageBeforeQuerying(String[] args, String message) {
        assertError(invoke(args), 2, message, "--help");
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.of(new String[]{"compare-runs"}, "requires a current run ID"),
                Arguments.of(new String[]{"compare-runs", "current"}, "requires --db"),
                Arguments.of(new String[]{"compare-runs", "current", "--db", "abc", "--baseline"}, "--baseline requires a value"),
                Arguments.of(new String[]{"compare-runs", "current", "--db", "abc", "--baseline", "a", "--baseline", "b"},
                        "--baseline may be specified only once"),
                Arguments.of(new String[]{"compare-runs", "current", "--db", "abc", "--group"}, "Unknown compare-runs option"),
                Arguments.of(new String[]{"import-junit", "report.xml", "--metadata", "run.properties"}, "--metadata requires --db"),
                Arguments.of(new String[]{"import-log", "job.log", "--metadata", "run.properties"}, "--metadata requires --db"),
                Arguments.of(new String[]{"import-log", "job.log", "--db", "abc", "--metadata", "has\u0000nul"}, "Invalid metadata path"));
    }

    private static void saveReport(Path db, String id, String report, String metadata) {
        assertOutput(invoke("import-junit", report, "--db", db.toString(), "--run-id", id, "--metadata", metadata), "artifact saved");
    }

    private Path copyFixture(String name) throws Exception {
        return Files.copy(Path.of(fixture(name)), directory.resolve(name));
    }

    private String metadataFile(String name, String text) throws Exception {
        return Files.writeString(directory.resolve(name), text).toString();
    }

    private static String fixture(String name) throws Exception {
        return Path.of(CiTriageComparisonTest.class.getResource("/fixtures/comparison/" + name).toURI()).toString();
    }

    private static Result invoke(String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            return new Result(CiTriageCli.run(args, out, err), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private static void assertOutput(Result result, String... expected) {
        assertEquals(0, result.code(), result.err());
        assertEquals("", result.err());
        for (String fragment : expected) {
            assertTrue(result.out().contains(fragment), result.out());
        }
    }

    private static void assertError(Result result, int code, String... expected) {
        assertEquals(code, result.code(), result.err());
        assertEquals("", result.out());
        for (String fragment : expected) {
            assertTrue(result.err().contains(fragment), result.err());
        }
    }

    private record Result(int code, String out, String err) {
    }
}
