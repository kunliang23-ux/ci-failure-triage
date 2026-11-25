package org.citriage.log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.citriage.log.DiagnosticCategory.ASSERTION_ERROR;
import static org.citriage.log.DiagnosticCategory.COMPILATION_ERROR;
import static org.citriage.log.DiagnosticCategory.MISSING_DEPENDENCY;
import static org.citriage.log.DiagnosticCategory.TIMEOUT;
import static org.citriage.log.DiagnosticCategory.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogGrouperTest {
    private static final String ASSERTION = "org.opentest4j.AssertionFailedError: expected: <42> but was: <41>";
    private static final String COMPILER = "[ERROR] src/Example.java:[8,9] cannot find symbol";

    private final LogGrouper grouper = new LogGrouper();
    private final LogClassifier classifier = new LogClassifier();
    private final LogReader reader = new LogReader();

    @Test
    void interleavedFixtureGroupsByDiagnosticAndRetainsEveryOriginalFinding() throws Exception {
        List<LogFinding> findings = classifier.classify(reader.read(fixture()));
        assertEquals(6, findings.size());
        List<FailureGroup> groups = grouper.group(findings);

        assertEquals(List.of(ASSERTION_ERROR, MISSING_DEPENDENCY, COMPILATION_ERROR),
                groups.stream().map(FailureGroup::category).toList());
        FailureGroup assertions = groups.get(0);
        FailureGroup dependencies = groups.get(1);
        FailureGroup compilation = groups.get(2);
        assertAll(
                () -> assertEquals(List.of(3, 2, 1), groups.stream().map(FailureGroup::occurrenceCount).toList()),
                () -> assertEquals(ASSERTION, assertions.signature()),
                () -> assertEquals(List.of("example.CartTest#calculatesTotal", "example.InvoiceTest#roundsTotal",
                                "example.OrderTest#appliesDiscount"), assertions.members().stream()
                        .map(finding -> finding.testIdentity().orElseThrow().displayName()).toList()),
                () -> assertTrue(dependencies.signature().contains("dependency: example:sample-library:jar:1.2.0")),
                () -> assertTrue(compilation.signature().contains("symbol:   class MissingType")),
                () -> assertSame(findings.get(0), assertions.members().get(0)),
                () -> assertSame(findings.get(2), assertions.members().get(1)),
                () -> assertSame(findings.get(4), assertions.members().get(2)),
                () -> assertSame(findings.get(1), dependencies.members().get(0)),
                () -> assertSame(findings.get(5), dependencies.members().get(1)),
                () -> assertSame(findings.get(3), compilation.members().get(0)),
                () -> assertThrows(UnsupportedOperationException.class, assertions.members()::clear),
                () -> assertThrows(UnsupportedOperationException.class, groups::clear));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("distinctDiagnostics")
    void meaningfulDifferencesStaySeparate(String difference, DiagnosticCategory category, String first, String second,
                                           @TempDir Path directory) throws Exception {
        List<FailureGroup> groups = grouper.group(findings(directory.resolve("distinct.log"), first + "\n\n" + second));

        assertEquals(List.of(category, category), groups.stream().map(FailureGroup::category).toList());
        assertEquals(List.of(1, 1), groups.stream().map(FailureGroup::occurrenceCount).toList());
        assertNotEquals(groups.get(0).signature(), groups.get(1).signature(), difference);
        assertNotEquals(groups.get(0).id(), groups.get(1).id());
    }

    static Stream<Arguments> distinctDiagnostics() {
        String dependency = "[ERROR] Could not resolve dependencies for project example:fixture:jar:1.0\n"
                + "[ERROR] dependency: example:sample-library:jar:";
        String compiler = "src/Example.java:8: error: cannot find symbol";
        return Stream.of(
                Arguments.of("expected value", ASSERTION_ERROR, ASSERTION, ASSERTION.replace("<42>", "<43>")),
                Arguments.of("actual value", ASSERTION_ERROR, ASSERTION, ASSERTION.replace("<41>", "<40>")),
                Arguments.of("exception type", ASSERTION_ERROR, ASSERTION,
                        ASSERTION.replace("org.opentest4j.AssertionFailedError", "java.lang.AssertionError")),
                Arguments.of("spaces inside a value", ASSERTION_ERROR, ASSERTION.replace("<42>", "<a b>"),
                        ASSERTION.replace("<42>", "<a  b>")),
                Arguments.of("trailing space in an unquoted value", ASSERTION_ERROR,
                        "java.lang.AssertionError: actual value", "java.lang.AssertionError: actual value "),
                Arguments.of("color escape inside a payload", ASSERTION_ERROR, ASSERTION,
                        ASSERTION.replace("<42>", "<\u001b[31m42\u001b[0m>")),
                Arguments.of("dependency version", MISSING_DEPENDENCY, dependency + "1.2.0", dependency + "1.3.0"),
                Arguments.of("artifact identity", MISSING_DEPENDENCY, dependency + "1.2.0",
                        dependency.replace("sample-library", "other-library") + "1.2.0"),
                Arguments.of("missing class and case", MISSING_DEPENDENCY,
                        "java.lang.ClassNotFoundException: example.FixtureDriver",
                        "java.lang.ClassNotFoundException: example.FixtureDRIVER"),
                Arguments.of("cause in a trace", MISSING_DEPENDENCY,
                        "java.lang.RuntimeException: fixture failed\nCaused by: java.lang.ClassNotFoundException: A",
                        "java.lang.RuntimeException: fixture failed\nCaused by: java.lang.ClassNotFoundException: B"),
                Arguments.of("compiler symbol", COMPILATION_ERROR, COMPILER + "\n[ERROR]   symbol:   class MissingType",
                        COMPILER + "\n[ERROR]   symbol:   class OtherType"),
                Arguments.of("compiler error code", COMPILATION_ERROR, COMPILER + " [code 101]", COMPILER + " [code 102]"),
                Arguments.of("compiler source path", COMPILATION_ERROR, COMPILER, COMPILER.replace("src/", "test/")),
                Arguments.of("compiler caret column", COMPILATION_ERROR, compiler + "\n    Missing value;\n    ^",
                        compiler + "\n    Missing value;\n            ^"),
                Arguments.of("timeout duration", TIMEOUT, "java.util.concurrent.TimeoutException: after 50 ms",
                        "java.util.concurrent.TimeoutException: after 100 ms"));
    }

    @Test
    void identicalUnknownDiagnosticsRemainSeparateWithUniqueIds(@TempDir Path directory) throws Exception {
        String diagnostic = "[ERROR] A fixture step ended unexpectedly";
        List<LogFinding> findings = findings(directory.resolve("unknown.log"), diagnostic + "\n" + diagnostic);
        List<FailureGroup> groups = grouper.group(findings);

        assertEquals(List.of(UNKNOWN, UNKNOWN), groups.stream().map(FailureGroup::category).toList());
        assertEquals(List.of(1, 1), groups.stream().map(FailureGroup::occurrenceCount).toList());
        assertEquals(groups.get(0).signature(), groups.get(1).signature());
        assertNotEquals(groups.get(0).id(), groups.get(1).id());
        assertEquals(groups, grouper.group(List.of(findings.get(1), findings.get(0))));
    }

    @Test
    void compilationSummariesAloneRemainSeparate(@TempDir Path directory) throws Exception {
        String run = "[INFO] Scanning for projects...\n[ERROR] COMPILATION ERROR :\n";
        List<FailureGroup> groups = grouper.group(findings(directory.resolve("summaries.log"), run + run));

        assertEquals(List.of(COMPILATION_ERROR, COMPILATION_ERROR), groups.stream().map(FailureGroup::category).toList());
        assertEquals(List.of(1, 1), groups.stream().map(FailureGroup::occurrenceCount).toList());
        assertEquals("COMPILATION ERROR :", groups.get(0).signature());
        assertNotEquals(groups.get(0).id(), groups.get(1).id());
    }

    @Test
    void matchingIdsSurviveNewSourcePathAndShiftedLinesAndOrderFollowsEvidence(@TempDir Path directory)
            throws Exception {
        Path input = fixture();
        List<LogFinding> findings = classifier.classify(reader.read(input));
        List<FailureGroup> groups = grouper.group(findings);
        List<LogFinding> reversed = new ArrayList<>(findings);
        Collections.reverse(reversed);
        List<FailureGroup> shifted = grouper.group(findings(directory.resolve("shifted.log"),
                "java.lang.AssertionError: independent diagnostic\n\n" + Files.readString(input)));

        assertEquals(groups, grouper.group(reversed));
        assertEquals(groups.stream().map(FailureGroup::id).toList(),
                shifted.stream().skip(1).map(FailureGroup::id).toList());
        assertEquals(groups.size(), groups.stream().map(FailureGroup::id).distinct().count());
    }

    @Test
    void groupingPreservesBytesHashSourceAndMixedLineEndings(@TempDir Path directory) throws Exception {
        String first = "\uFEFF\u001b[31m[ERROR]\u001b[0m " + ASSERTION + "\r\n"
                + "\tat example.CartTest.calculatesTotal(CartTest.java:24)\r\n";
        String text = first + "\n" + ASSERTION;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Path path = Files.write(directory.resolve("mixed line endings.log"), bytes);
        BuildLog log = reader.read(path);
        List<FailureGroup> groups = grouper.group(classifier.classify(log));
        assertEquals(1, groups.size());
        FailureGroup group = groups.get(0);
        assertEquals(2, group.occurrenceCount());
        LogEvidence firstEvidence = group.members().get(0).evidence();
        LogEvidence secondEvidence = group.members().get(1).evidence();

        assertAll(
                () -> assertEquals(ASSERTION, group.signature()),
                () -> assertEquals(path.toAbsolutePath().normalize().toString(), firstEvidence.source().name()),
                () -> assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        firstEvidence.source().sha256()),
                () -> assertSame(log.source(), firstEvidence.source()),
                () -> assertSame(log.source(), secondEvidence.source()),
                () -> assertArrayEquals(bytes, firstEvidence.source().bytes()),
                () -> assertArrayEquals(bytes, Files.readAllBytes(path)),
                () -> assertEquals(1, firstEvidence.firstLine()),
                () -> assertEquals(2, firstEvidence.lastLine()),
                () -> assertEquals(first, firstEvidence.originalText()),
                () -> assertEquals(4, secondEvidence.firstLine()),
                () -> assertEquals(ASSERTION, secondEvidence.originalText()));
    }

    @Test
    void countsRepeatedKnownTestOccurrencesAndUnknownIdentitiesSeparately(@TempDir Path directory) throws Exception {
        String named = "[ERROR] example.CartTest.calculatesTotal -- Time elapsed: 0.010 s <<< FAILURE!\n" + ASSERTION;
        List<FailureGroup> groups = grouper.group(findings(directory.resolve("repeated.log"),
                named + "\n\n" + named + "\n\n" + ASSERTION));
        assertEquals(1, groups.size());
        FailureGroup group = groups.get(0);

        assertEquals(3, group.occurrenceCount());
        assertEquals(1, group.distinctKnownTestCount());
        assertEquals(1, group.unknownTestOccurrenceCount());
        assertTrue(group.members().get(2).testIdentity().isEmpty());
    }

    @Test
    void emptyAndSingleFindingImports(@TempDir Path directory) throws Exception {
        assertTrue(grouper.group(findings(directory.resolve("empty.log"), "")).isEmpty());
        List<LogFinding> single = findings(directory.resolve("one.log"), ASSERTION);
        List<FailureGroup> groups = grouper.group(single);

        assertEquals(1, groups.size());
        assertEquals(single, groups.get(0).members());
        assertEquals(1, groups.get(0).occurrenceCount());
        assertEquals(0, groups.get(0).distinctKnownTestCount());
        assertEquals(1, groups.get(0).unknownTestOccurrenceCount());
    }

    @Test
    void requiresOneImportedSnapshot(@TempDir Path directory) throws Exception {
        LogFinding first = findings(directory.resolve("first.log"), ASSERTION).get(0);
        LogFinding second = findings(directory.resolve("second.log"), ASSERTION).get(0);

        assertThrows(IllegalArgumentException.class, () -> grouper.group(List.of(first, second)));
    }

    private List<LogFinding> findings(Path path, String text) throws Exception {
        return classifier.classify(reader.read(Files.writeString(path, text)));
    }

    private static Path fixture() throws Exception {
        return Path.of(LogGrouperTest.class.getResource("/fixtures/logs/grouping/grouped-failures.log").toURI());
    }
}
