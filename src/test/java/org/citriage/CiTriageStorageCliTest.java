package org.citriage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiTriageStorageCliTest {
    @TempDir
    Path directory;

    @Test
    void savesListsAndShowsOneRunWithSeparateXmlAndLogCounts() throws Exception {
        String db = directory.resolve("local runs.db").toString();
        Path report = Files.copy(Path.of(report()), directory.resolve("copied report.xml"));
        Path log = Files.copy(Path.of(fixture("logs/grouping/grouped-failures.log")), directory.resolve("copied.log"));
        Result junit = invoke("import-junit", report.toString(), "--run-id", "build 17", "--db", db,
                "--commit", "abc1234");
        assertSuccess(junit, "Run ID: build 17", "Storage: artifact saved.",
                "Tests: 5 | passed=2 failed=1 errors=1 skipped=1");
        Result logging = invoke("import-log", log.toString(), "--db", db, "--run-id", "build 17",
                "--environment", "sample CI");
        assertSuccess(logging, "Storage: artifact saved.", "Findings: 6");
        assertFalse(logging.out().contains("Groups:"));
        Result duplicate = invoke("import-log", log.toString(), "--group", "--run-id", "build 17", "--db", db);
        assertSuccess(duplicate, "Storage: artifact already saved.", "Groups: 3");
        Files.delete(report);
        Files.delete(log);

        assertSuccess(invoke("runs", "--db", db), "Runs: 1", "Run ID: build 17", "Commit: abc1234",
                "Environment: sample CI", "JUnit reports: 1 | JUnit executions: 5 | Logs: 1 | Log findings: 6 | Log groups: 3");
        assertSuccess(invoke("show-run", "build 17", "--group", "--db", db), "First imported:",
                "Execution time: unknown", "Repository: unknown", "JUnit executions: 5 | Log findings: 6",
                "Tests: 5 | passed=2 failed=1 errors=1 skipped=1", "XML diagnostic location: " + report.getFileName(),
                "Groups: 3", "Test: example.InvoiceTest#roundsTotal", "Test: unknown [maven-dependency-resolution]",
                "Evidence: " + log.getFileName() + ":9-11", "11 |     at example.InvoiceTest.roundsTotal");
        Result ungrouped = invoke("show-run", "build 17", "--db", db);
        assertSuccess(ungrouped, "Findings: 6", "ASSERTION_ERROR [java-assertion]", "Evidence: " + log.getFileName() + ":9-11");
        assertFalse(ungrouped.out().contains("Groups:"));
    }

    @Test
    void importWithoutExplicitRunIdCreatesFreshIdsForIdenticalContent() throws Exception {
        String db = directory.resolve("local.db").toString();
        Result first = invoke("import-junit", report(), "--db", db);
        Result second = invoke("import-junit", report(), "--db", db);
        assertSuccess(first, "Run ID: ", "Storage: artifact saved.");
        assertSuccess(second, "Run ID: ", "Storage: artifact saved.");
        String firstId = runId(first);
        String secondId = runId(second);

        assertNotEquals(UUID.fromString(firstId), UUID.fromString(secondId));
        assertSuccess(invoke("runs", "--db", db), "Runs: 2", firstId, secondId);
        assertSuccess(invoke("show-run", firstId, "--db", db), "JUnit executions: 5 | Log findings: 0");
        assertSuccess(invoke("show-run", secondId, "--db", db), "JUnit executions: 5 | Log findings: 0");
    }

    @Test
    void queriesUseExistingDatabasesAndMissingRunsReturnOne() throws Exception {
        Path path = directory.resolve("missing.db");
        String db = path.toString();
        assertError(invoke("runs", "--db", db), 1, "Database does not exist");
        assertError(invoke("show-run", "missing", "--db", db), 1, "Database does not exist");
        assertFalse(Files.exists(path));

        assertSuccess(invoke("import-junit", report(), "--db", db, "--run-id", "present"), "Run ID: present");
        assertError(invoke("show-run", "missing", "--db", db), 1, "Run 'missing' does not exist");
        assertError(invoke("runs", "--db", directory.toString()), 1, "Expected a regular database file");
    }

    @Test
    void failuresReportOnlyOnStderrAndLeaveTheDatabaseAndSourcesIntact() throws Exception {
        Path path = directory.resolve("runs.db");
        String db = path.toString();
        assertError(invoke("import-junit", fixture("malformed.xml"), "--db", db), 1, "malformed.xml");
        assertFalse(Files.exists(path));

        assertSuccess(invoke("import-junit", report(), "--db", db, "--run-id", "build", "--commit", "abc1234"),
                "Storage: artifact saved.");
        assertError(invoke("import-log", fixture("logs/assertion.log"), "--db", db, "--run-id", "build", "--commit", "other"),
                1, "Conflicting Commit metadata");
        assertSuccess(invoke("runs", "--db", db), "Commit: abc1234", "Logs: 0 | Log findings: 0");
        assertTrue(Files.isRegularFile(Path.of(report())));
    }

    @Test
    void helpAdvertisesStorageAndLegacyCommandsStillWorkWithoutIt() throws Exception {
        assertSuccess(invoke("--help"), "runs --db <database-path>", "show-run <id> --db <database-path>", "--run-id <id>");
        Result legacy = invoke("import-junit", report());
        assertSuccess(legacy, "Tests: 5 | passed=2 failed=1 errors=1 skipped=1");
        assertEquals(legacy, invoke("--report", report()));
        assertFalse(legacy.out().contains("Run ID:"));
        assertSuccess(invoke("import-log", fixture("logs/timeout.log"), "--commit", "abc1234", "--environment", "example"),
                "Commit: abc1234", "Environment: example", "TIMEOUT [java-timeout]");
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void validatesDatabaseFlagsBeforeReadingAnyFiles(String[] arguments, String expected) {
        assertError(invoke(arguments), 2, expected, "--help");
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.of(new String[]{"runs"}, "runs requires --db"),
                Arguments.of(new String[]{"runs", "--db"}, "--db requires a value"),
                Arguments.of(new String[]{"runs", "--db", "missing.db", "--group"}, "Unknown runs option"),
                Arguments.of(new String[]{"runs", "--db", "missing.db", "--db", "other.db"}, "--db may be specified only once"),
                Arguments.of(new String[]{"show-run", "--db", "missing.db"}, "show-run requires a run ID"),
                Arguments.of(new String[]{"show-run", "run"}, "show-run requires --db"),
                Arguments.of(new String[]{"show-run", "run", "--db", "missing.db", "--group", "--group"},
                        "--group may be specified only once"),
                Arguments.of(new String[]{"show-run", "run", "--db", "missing.db", "--run-id", "other"},
                        "Unknown show-run option"),
                Arguments.of(new String[]{"import-log", "missing.log", "--run-id", "run"}, "--run-id requires --db"),
                Arguments.of(new String[]{"import-junit", "missing.xml", "--run-id", "run"}, "--run-id requires --db"),
                Arguments.of(new String[]{"import-junit", "missing.xml", "--environment", "CI"}, "require --db"),
                Arguments.of(new String[]{"import-log", "missing.log", "--db", "--group"}, "--db requires a value"),
                Arguments.of(new String[]{"import-junit", "missing.xml", "--db", "missing.db", "--run-id", " "},
                        "--run-id requires a value"),
                Arguments.of(new String[]{"import-log", "missing.log", "--db", "some\u0000path"}, "Invalid database path"),
                Arguments.of(new String[]{"import-log", "missing.log", "--db", "missing.db", "--run-id", "a", "--run-id", "b"},
                        "--run-id may be specified only once"),
                Arguments.of(new String[]{"--report", "missing.xml", "--db", "missing.db"}, "requires exactly one file path"));
    }

    private static String runId(Result result) {
        return result.out().lines().filter(line -> line.startsWith("Run ID: ")).findFirst().orElseThrow().substring(8);
    }

    private static String report() throws Exception {
        return fixture("mixed-results.xml");
    }

    private static String fixture(String name) throws Exception {
        return Path.of(CiTriageStorageCliTest.class.getResource("/fixtures/" + name).toURI()).toString();
    }

    private static void assertSuccess(Result result, String... expected) {
        assertEquals(0, result.code(), result.err());
        assertEquals("", result.err());
        for (String text : expected) {
            assertTrue(result.out().contains(text), result.out());
        }
    }

    private static void assertError(Result result, int code, String... expected) {
        assertEquals(code, result.code(), result.err());
        assertEquals("", result.out());
        for (String text : expected) {
            assertTrue(result.err().contains(text), result.err());
        }
    }

    private static Result invoke(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int code = CiTriageCli.run(args, out, err);
            return new Result(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int code, String out, String err) {
    }
}
