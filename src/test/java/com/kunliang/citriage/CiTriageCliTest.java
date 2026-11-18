package com.kunliang.citriage;

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
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
