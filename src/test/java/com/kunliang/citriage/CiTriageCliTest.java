package com.kunliang.citriage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CiTriageCliTest {
    @Test
    void helpDescribesPurposeUsageAndAvailableArguments() {
        Result result = invoke("--help");

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertTrue(result.out().contains("Why did my tests fail")),
                () -> assertTrue(result.out().contains("java -jar target/ci-failure-triage.jar")),
                () -> assertTrue(result.out().contains("--help")),
                () -> assertTrue(result.out().contains("--version")),
                () -> assertTrue(result.out().contains("--report <path>")),
                () -> assertTrue(result.out().contains("import-junit <report-path>")),
                () -> assertTrue(result.out().contains("import-log <log-path> [--commit <sha>] [--environment <label>]")),
                () -> assertTrue(result.out().contains("[--group]")),
                () -> assertTrue(result.out().contains("JUnit XML")),
                () -> assertEquals("", result.err()));
    }

    @Test
    void noArgumentsShowsHelpSuccessfully() {
        Result result = invoke();

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals(invoke("--help").out(), result.out()),
                () -> assertEquals("", result.err()));
    }

    @Test
    void versionShowsTheApplicationReleaseWithoutHelpOrErrors() {
        Result result = invoke("--version");

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("CI Failure Triage 0.1.0" + System.lineSeparator(), result.out()),
                () -> assertEquals("", result.err()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--unknown", "report.xml", ""})
    void unknownArgumentsProduceAnActionableErrorAndNoSuccessfulOutput(String argument) {
        assertUnknownArgument(invoke(argument), argument);
    }

    @ParameterizedTest
    @MethodSource("argumentsMixingKnownAndUnknownOptions")
    void unknownArgumentsAreRejectedRegardlessOfTheirPosition(String first, String second) {
        assertUnknownArgument(invoke(first, second), "--unknown");
    }

    static Stream<Arguments> argumentsMixingKnownAndUnknownOptions() {
        return Stream.of(
                Arguments.of("--help", "--unknown"),
                Arguments.of("--unknown", "--help"),
                Arguments.of("--version", "--unknown"),
                Arguments.of("--unknown", "--version"));
    }

    @ParameterizedTest
    @MethodSource("multipleKnownOptions")
    void multipleKnownOptionsAreRejectedWithUsageGuidance(String first, String second) {
        Result result = invoke(first, second);

        assertAll(
                () -> assertEquals(2, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("Choose only one argument")),
                () -> assertTrue(result.err().contains("--help")));
    }

    static Stream<Arguments> multipleKnownOptions() {
        return Stream.of(
                Arguments.of("--help", "--version"),
                Arguments.of("--version", "--help"),
                Arguments.of("--help", "--help"),
                Arguments.of("--version", "--version"));
    }

    @Test
    void importJunitSummarizesFiveExecutionsAndShowsFailureDetails() throws Exception {
        Path input = Path.of(CiTriageCliTest.class.getResource("/fixtures/mixed-results.xml").toURI());
        byte[] original = Files.readAllBytes(input);
        Result result = invoke("import-junit", input.toString());

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("Report: " + input.toAbsolutePath().normalize())),
                () -> assertTrue(result.out().contains("Tests: 5 | passed=2 failed=1 errors=1 skipped=1")),
                () -> assertTrue(result.out().contains("FAILED example.CheckoutTest#calculatesTotal")),
                () -> assertTrue(result.out().contains("ERROR example.CheckoutTest#loadsFixtureConfig")),
                () -> assertTrue(result.out().contains("expected: <42> but was: <41>")),
                () -> assertTrue(result.out().contains("Type: java.io.IOException")),
                () -> assertTrue(result.out().contains("CheckoutTest.java:42")),
                () -> assertTrue(result.out().contains("Duration (seconds): 0.1250")),
                () -> assertEquals(result, invoke("--report", input.toString())),
                () -> assertArrayEquals(original, Files.readAllBytes(input)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed.xml", "doctype.xml"})
    void importJunitRejectsMalformedAndUnsafeFixturesWithoutPartialOutput(String name) throws Exception {
        Path input = Path.of(CiTriageCliTest.class.getResource("/fixtures/" + name).toURI());
        Result result = invoke("import-junit", input.toString());

        assertAll(
                () -> assertEquals(1, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains(name)),
                () -> assertTrue(result.err().contains("doctype.xml".equals(name) ? "DOCTYPE" : "malformed.xml:5:")));
    }

    @Test
    void importJunitClearlyRejectsUnsupportedRoots(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("unsupported.xml");
        Files.writeString(input, "<not-junit/>");
        Result result = invoke("import-junit", input.toString());

        assertAll(
                () -> assertEquals(1, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("unsupported.xml")),
                () -> assertTrue(result.err().contains("Expected a <testsuite> or <testsuites> document root")));
    }

    @Test
    void reportImportShowsObservedOutcomesAndFailureEvidence() throws Exception {
        Path fixture = Path.of(CiTriageCliTest.class.getResource("/junit/mixed-suite.xml").toURI());
        byte[] originalBytes = Files.readAllBytes(fixture);

        Result result = invoke("--report", fixture.toString());

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("Tests: 4 | passed=1 failed=1 errors=1 skipped=1")),
                () -> assertTrue(result.out().contains("FAILED example.CheckoutTest#calculatesTotal")),
                () -> assertTrue(result.out().contains("expected: <42> but was: <41>")),
                () -> assertTrue(result.out().contains("CheckoutTest.java:42")),
                () -> assertTrue(result.out().contains("SHA-256: ")),
                () -> assertArrayEquals(originalBytes, Files.readAllBytes(fixture)));
    }

    @Test
    void malformedReportProducesOnlyAnErrorWithItsSourceLocation() throws Exception {
        Path fixture = Path.of(CiTriageCliTest.class.getResource("/junit/malformed.xml").toURI());

        Result result = invoke("--report", fixture.toString());

        assertAll(
                () -> assertEquals(1, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("malformed.xml:5:")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"import-junit", "--report"})
    void missingReportHasAnActionableReadError(String operation, @TempDir Path directory) {
        Result result = invoke(operation, directory.resolve("does-not-exist.xml").toString());

        assertAll(
                () -> assertEquals(1, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("Cannot read report")),
                () -> assertTrue(result.err().contains("does-not-exist.xml")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"import-junit", "--report"})
    void directoryInsteadOfReportHasAnActionableReadError(String operation, @TempDir Path directory) {
        Result result = invoke(operation, directory.toString());

        assertAll(
                () -> assertEquals(1, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("Cannot read report")),
                () -> assertTrue(result.err().contains(directory.toString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"import-junit", "--report"})
    void reportPathsContainingSpacesWork(String operation, @TempDir Path directory) throws Exception {
        Path report = directory.resolve("passing report.xml");
        Files.writeString(report, "<testsuite><testcase name=\"synthetic\"/></testsuite>");

        Result result = invoke(operation, report.toString());

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("Tests: 1 | passed=1 failed=0 errors=0 skipped=0")));
    }

    @ParameterizedTest
    @MethodSource("invalidReportArguments")
    void reportUsageIsValidatedBeforeReadingAnyFile(String[] args) {
        Result result = invoke(args);

        assertAll(
                () -> assertEquals(2, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("--help")));
    }

    static Stream<Arguments> invalidReportArguments() {
        return Stream.of(
                Arguments.of((Object) new String[]{"--report"}),
                Arguments.of((Object) new String[]{"--report", ""}),
                Arguments.of((Object) new String[]{"--report", "--help"}),
                Arguments.of((Object) new String[]{"--report", "missing.xml", "extra.xml"}),
                Arguments.of((Object) new String[]{"--help", "--report"}),
                Arguments.of((Object) new String[]{"--report", "invalid\u0000path.xml"}),
                Arguments.of((Object) new String[]{"import-junit"}),
                Arguments.of((Object) new String[]{"import-junit", ""}),
                Arguments.of((Object) new String[]{"import-junit", "--help"}),
                Arguments.of((Object) new String[]{"import-junit", "missing.xml", "extra.xml"}),
                Arguments.of((Object) new String[]{"--version", "import-junit"}),
                Arguments.of((Object) new String[]{"import-junit", "invalid\u0000path.xml"}));
    }

    @Test
    void importsLogWithSpacesAndPrintsSuppliedMetadataAndNumberedEvidence(@TempDir Path directory) throws Exception {
        Path fixture = logFixture("timeout.log");
        Path path = Files.copy(fixture, directory.resolve("timeout evidence.log"));
        byte[] original = Files.readAllBytes(path);
        Result result = invoke("import-log", path.toString(), "--environment", "test runner label",
                "--commit", "abc123def");

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("Log: " + path.toAbsolutePath().normalize())),
                () -> assertTrue(result.out().contains("Commit: abc123def")),
                () -> assertTrue(result.out().contains("Environment: test runner label")),
                () -> assertTrue(result.out().contains("TIMEOUT [java-timeout]")),
                () -> assertTrue(result.out().contains("Test: example.InventoryTest#waitsForInventory")),
                () -> assertTrue(result.out().contains("timeout evidence.log:3-5")),
                () -> assertTrue(result.out().contains("4 | java.util.concurrent.TimeoutException:")),
                () -> assertTrue(result.out().contains("5 |     at example.InventoryTest.waitsForInventory")),
                () -> assertTrue(result.out().contains("SHA-256: ")),
                () -> assertArrayEquals(original, Files.readAllBytes(path)));
    }

    @Test
    void passingLogWithMissingMetadataShowsNoFindings(@TempDir Path directory) throws Exception {
        Path path = Files.copy(logFixture("passing.log"), directory.resolve("passing.log"));
        Result result = invoke("import-log", path.toString());

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("Commit: unknown")),
                () -> assertTrue(result.out().contains("Environment: unknown")),
                () -> assertTrue(result.out().contains("Findings: 0")),
                () -> assertTrue(result.out().contains("No failure diagnostics matched.")));
    }

    @Test
    void groupedLogShowsThreeGroupsAndEveryNumberedEvidenceLine() throws Exception {
        Path input = logFixture("grouping/grouped-failures.log");
        String path = input.toString();
        List<String> sourceLines = Files.readAllLines(input);
        List<String> expectedEvidence = List.of(1, 2, 3, 9, 10, 11, 17, 18, 19, 5, 6, 7, 21, 22, 23, 13, 14, 15)
                .stream().map(line -> "    " + line + " | " + sourceLines.get(line - 1)).toList();
        Result grouped = invoke("import-log", path, "--commit", "abc123", "--group", "--environment", "sample CI");
        Result ungrouped = invoke("import-log", path, "--commit", "abc123", "--environment", "sample CI");

        assertAll(
                () -> assertEquals(0, grouped.exitCode()),
                () -> assertEquals("", grouped.err()),
                () -> assertEquals(0, ungrouped.exitCode()),
                () -> assertEquals("", ungrouped.err()),
                () -> assertEquals(ungrouped.out().lines().limit(6).toList(), grouped.out().lines().limit(6).toList()),
                () -> assertTrue(grouped.out().contains("Commit: abc123")),
                () -> assertTrue(grouped.out().contains("Environment: sample CI")),
                () -> assertTrue(grouped.out().contains("Findings: 6")),
                () -> assertTrue(grouped.out().contains("Groups: 3")),
                () -> assertTrue(grouped.out().contains("Groups indicate diagnostic similarity. "
                        + "A shared root cause requires further evidence.")),
                () -> assertEquals(List.of("ASSERTION_ERROR | occurrences=3", "MISSING_DEPENDENCY | occurrences=2",
                                "COMPILATION_ERROR | occurrences=1"), grouped.out().lines()
                        .filter(line -> line.matches("Group g-[0-9a-f]{16} \\| .*"))
                        .map(line -> line.substring(line.indexOf(" | ") + 3)).toList()),
                () -> assertTrue(grouped.out().contains("Distinct known tests: 3 | Unknown test occurrences: 0")),
                () -> assertTrue(grouped.out().contains("Distinct known tests: 0 | Unknown test occurrences: 2")),
                () -> assertTrue(grouped.out().contains("    org.opentest4j.AssertionFailedError: expected: <42> but was: <41>")),
                () -> assertTrue(grouped.out().contains("Test: example.CartTest#calculatesTotal")),
                () -> assertTrue(grouped.out().contains("Test: example.InvoiceTest#roundsTotal")),
                () -> assertTrue(grouped.out().contains("Test: example.OrderTest#appliesDiscount")),
                () -> assertTrue(grouped.out().contains("Test: unknown [maven-dependency-resolution]")),
                () -> assertEquals(expectedEvidence, grouped.out().lines()
                        .filter(line -> line.matches("    \\d+ \\| .*")).toList()),
                () -> assertEquals(6, ungrouped.out().lines().filter(line -> line.startsWith("  Evidence: ")).count()),
                () -> assertFalse(ungrouped.out().contains("Groups:")),
                () -> assertEquals(grouped,
                        invoke("import-log", path, "--group", "--environment", "sample CI", "--commit", "abc123")),
                () -> assertEquals(grouped,
                        invoke("import-log", path, "--environment", "sample CI", "--commit", "abc123", "--group")));
    }

    @Test
    void emptyImportedLogReportsUnavailableEvidence(@TempDir Path directory) throws Exception {
        Path path = Files.createFile(directory.resolve("empty.log"));
        Result result = invoke("import-log", path.toString(), "--commit", "abc123");

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("Commit: abc123")),
                () -> assertTrue(result.out().contains("Environment: unknown")),
                () -> assertTrue(result.out().contains("Lines: 0")),
                () -> assertTrue(result.out().contains("Findings: 0")),
                () -> assertTrue(result.out().contains("Evidence unavailable")));
    }

    @Test
    void groupedEmptyAndPassingLogsStillReportZeroFindings(@TempDir Path directory) throws Exception {
        Result empty = invoke("import-log", Files.createFile(directory.resolve("empty.log")).toString(), "--group");
        Result passing = invoke("import-log", logFixture("passing.log").toString(), "--group");
        for (Result result : List.of(empty, passing)) {
            assertEquals(0, result.exitCode());
            assertEquals("", result.err());
            assertTrue(result.out().contains("Findings: 0"));
            assertTrue(result.out().contains("Groups: 0"));
        }
        assertTrue(empty.out().contains("Evidence unavailable"));
        assertTrue(passing.out().contains("No failure diagnostics matched."));
    }

    @Test
    void logReadAndDecodeErrorsGoOnlyToStderr(@TempDir Path directory) throws Exception {
        Path wrongEncoding = Files.write(directory.resolve("invalid-utf8.log"), new byte[]{(byte) 0xff});

        assertAll(
                () -> assertLogError(invoke("import-log", directory.resolve("missing.log").toString()), 1,
                        "missing.log", "file does not exist"),
                () -> assertLogError(invoke("import-log", directory.toString()), 1,
                        "Cannot read log", "Expected a regular file"),
                () -> assertLogError(invoke("import-log", wrongEncoding.toString()), 1,
                        "invalid-utf8.log", "expected valid UTF-8"));
    }

    @Test
    void terminalControlsInEvidenceAreShownAsLiteralEscapes(@TempDir Path directory) throws Exception {
        Path path = Files.writeString(directory.resolve("color.log"), "\u001b[31m[ERROR]\u001b[0m Synthetic error");
        Result result = invoke("import-log", path.toString());

        assertAll(
                () -> assertEquals(0, result.exitCode()),
                () -> assertEquals("", result.err()),
                () -> assertTrue(result.out().contains("1 | \\u001b[31m[ERROR]\\u001b[0m Synthetic error")),
                () -> assertTrue(result.out().indexOf('\u001b') < 0));
    }

    @ParameterizedTest
    @MethodSource("invalidLogArguments")
    void validatesImportLogArgumentsBeforeReadingAnyFile(String[] args, String expected) {
        assertLogError(invoke(args), 2, expected, "--help");
    }

    static Stream<Arguments> invalidLogArguments() {
        return Stream.of(
                Arguments.of(new String[]{"import-log"}, "requires a log file path"),
                Arguments.of(new String[]{"import-log", " "}, "requires a log file path"),
                Arguments.of(new String[]{"import-log", "--environment"}, "requires a log file path"),
                Arguments.of(new String[]{"import-log", "missing.log", "--unknown"}, "Unknown import-log option"),
                Arguments.of(new String[]{"import-log", "missing.log", "extra.log"}, "Unknown import-log option"),
                Arguments.of(new String[]{"import-log", "missing.log", "--commit"}, "--commit requires a value"),
                Arguments.of(new String[]{"import-log", "missing.log", "--commit", "--environment", "ci"},
                        "--commit requires a value"),
                Arguments.of(new String[]{"import-log", "missing.log", "--environment", ""},
                        "--environment requires a value"),
                Arguments.of(new String[]{"import-log", "missing.log", "--commit", "abc123", "--commit", "abc456"},
                        "--commit may be specified only once"),
                Arguments.of(new String[]{"import-log", "missing.log", "--environment", "ci", "--environment", "test"},
                        "--environment may be specified only once"),
                Arguments.of(new String[]{"import-log", "--group", "missing.log"}, "requires a log file path"),
                Arguments.of(new String[]{"import-log", "missing.log", "--group", "--commit", "sha", "--group"},
                        "--group may be specified only once"),
                Arguments.of(new String[]{"import-log", "missing.log", "--group", "true"}, "Unknown import-log option"),
                Arguments.of(new String[]{"import-log", "missing.log", "--group=true"}, "Unknown import-log option"),
                Arguments.of(new String[]{"import-log", "missing.log", "--commit", "--group"}, "--commit requires a value"),
                Arguments.of(new String[]{"--group"}, "Unknown argument: '--group'"),
                Arguments.of(new String[]{"--help", "--group"}, "Unknown argument: '--group'"),
                Arguments.of(new String[]{"import-junit", "missing.xml", "--group"}, "requires exactly one file path"),
                Arguments.of(new String[]{"--report", "missing.xml", "--group"}, "requires exactly one file path"),
                Arguments.of(new String[]{"import-log", "invalid\u0000path.log"}, "Invalid log path"),
                Arguments.of(new String[]{"--help", "import-log"}, "Choose only one argument"));
    }

    private static void assertLogError(Result result, int exitCode, String firstText, String secondText) {
        assertAll(
                () -> assertEquals(exitCode, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains(firstText), result.err()),
                () -> assertTrue(result.err().contains(secondText), result.err()));
    }

    private static Path logFixture(String name) throws Exception {
        return Path.of(CiTriageCliTest.class.getResource("/fixtures/logs/" + name).toURI());
    }

    private static void assertUnknownArgument(Result result, String argument) {
        assertAll(
                () -> assertEquals(2, result.exitCode()),
                () -> assertEquals("", result.out()),
                () -> assertTrue(result.err().contains("Unknown argument: '" + argument + "'")),
                () -> assertTrue(result.err().contains("--help")));
    }

    private static Result invoke(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            int exitCode = CiTriageCli.run(args, out, err);
            return new Result(exitCode, output.toString(StandardCharsets.UTF_8),
                    errors.toString(StandardCharsets.UTF_8));
        }
    }

    private record Result(int exitCode, String out, String err) {
    }
}
