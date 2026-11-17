package com.kunliang.citriage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
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
                () -> assertTrue(result.out().contains("Day 1")),
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
