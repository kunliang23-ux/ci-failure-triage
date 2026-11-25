package org.citriage.report;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitXmlParserTest {
    private final JUnitXmlParser parser = new JUnitXmlParser();

    @ParameterizedTest
    @CsvSource({
            "passing.xml, 2, 2, 0, 0, 0",
            "mixed-results.xml, 5, 2, 1, 1, 1",
            "nested-suites.xml, 3, 2, 1, 0, 0"
    })
    void day2FixturesHaveAccurateExecutionCounts(String name, int total, int passed, int failed,
                                                int errored, int skipped) throws Exception {
        JUnitReport report = parser.parse(day2Fixture(name));

        assertAll(
                () -> assertEquals(total, report.testCases().size()),
                () -> assertEquals(passed, report.count(TestOutcome.PASSED)),
                () -> assertEquals(failed, report.count(TestOutcome.FAILED)),
                () -> assertEquals(errored, report.count(TestOutcome.ERROR)),
                () -> assertEquals(skipped, report.count(TestOutcome.SKIPPED)));
    }

    @Test
    void day2MixedReportExtractsIdentityDurationAndOriginalDetails() throws Exception {
        Path input = day2Fixture("mixed-results.xml");
        byte[] original = Files.readAllBytes(input);
        JUnitReport report = parser.parse(input);
        TestCaseResult failure = report.testCases().get(2);
        TestCaseResult error = report.testCases().get(3);
        OutcomeDetail detail = failure.details().get(0);

        assertAll(
                () -> assertEquals(new TestIdentity(Optional.of("example.CheckoutTest"), "calculatesTotal"),
                        failure.identity()),
                () -> assertEquals("example.CheckoutTest#calculatesTotal", failure.identity().displayName()),
                () -> assertEquals(Optional.of(new BigDecimal("0.1250")), failure.durationSeconds()),
                () -> assertEquals(Optional.of(BigDecimal.ZERO), report.testCases().get(0).durationSeconds()),
                () -> assertEquals(Optional.empty(), report.testCases().get(4).durationSeconds()),
                () -> assertEquals(Optional.of("expected: <42> but was: <41>"), detail.message()),
                () -> assertEquals(Optional.of("org.opentest4j.AssertionFailedError"), detail.type()),
                () -> assertEquals("org.opentest4j.AssertionFailedError: expected: <42> but was: <41>\n"
                        + "    at example.CheckoutTest.calculatesTotal(CheckoutTest.java:42)\n", detail.text()),
                () -> assertEquals(Optional.of("java.io.IOException"), error.details().get(0).type()),
                () -> assertEquals(Optional.of("synthetic fixture config unavailable"), error.details().get(0).message()),
                () -> assertEquals("java.io.IOException: synthetic fixture config unavailable\n"
                        + "    at example.CheckoutTest.loadsFixtureConfig(CheckoutTest.java:57)\n",
                        error.details().get(0).text()),
                () -> assertArrayEquals(original, report.source().bytes()),
                () -> assertArrayEquals(original, Files.readAllBytes(input)),
                () -> assertEquals(input.toAbsolutePath().normalize().toString(), report.source().name()));
    }

    @Test
    void day2NestedReportPreservesSuitePathsAndRepeatedIdentities() throws Exception {
        JUnitReport report = parser.parse(day2Fixture("nested-suites.xml"));

        assertAll(
                () -> assertEquals(List.of(List.of("module"), List.of("module", "nested"), List.of("sibling")),
                        report.testCases().stream().map(TestCaseResult::suitePath).toList()),
                () -> assertEquals(1, report.testCases().stream().map(TestCaseResult::identity).distinct().count()));
    }

    @Test
    void durationIsOptionalAndDoesNotInventMissingClassMetadata() throws Exception {
        JUnitReport report = parser.parse(day2Fixture("passing.xml"));

        assertAll(
                () -> assertEquals(Optional.empty(), report.testCases().get(1).identity().className()),
                () -> assertEquals("passesWithoutClassMetadata", report.testCases().get(1).identity().displayName()),
                () -> assertEquals(Optional.of(new BigDecimal("0.025")), report.testCases().get(1).durationSeconds()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", "NaN", "Infinity", "", "not-a-duration"})
    void malformedDurationIsRejectedWithSourceAndOriginalEvidence(String value) {
        String xml = "<testsuite><testcase name=\"duration\" time=\"" + value + "\"/></testsuite>";
        ReportParseException exception = assertThrows(ReportParseException.class, () -> parseXml(xml));

        assertAll(
                () -> assertTrue(exception.getMessage().contains("Invalid testcase time")),
                () -> assertTrue(exception.getMessage().contains("nonnegative number of seconds")),
                () -> assertEquals("synthetic.xml", exception.location().sourceName()),
                () -> assertArrayEquals(xml.getBytes(StandardCharsets.UTF_8), exception.source().bytes()));
    }

    @Test
    void malformedDay2FixtureRetainsRejectedBytes() throws Exception {
        Path input = day2Fixture("malformed.xml");
        ReportParseException exception = assertThrows(ReportParseException.class, () -> parser.parse(input));

        assertArrayEquals(Files.readAllBytes(input), exception.source().bytes());
    }

    @Test
    void countsActualExecutionsRatherThanTrustingDeclaredSuiteCounters() throws Exception {
        JUnitReport report = parser.parse(fixture("mixed-suite.xml"));

        assertAll(
                () -> assertEquals(4, report.testCases().size()),
                () -> assertEquals(1, report.count(TestOutcome.PASSED)),
                () -> assertEquals(1, report.count(TestOutcome.FAILED)),
                () -> assertEquals(1, report.count(TestOutcome.ERROR)),
                () -> assertEquals(1, report.count(TestOutcome.SKIPPED)),
                () -> assertEquals(List.of(TestOutcome.PASSED, TestOutcome.FAILED, TestOutcome.ERROR, TestOutcome.SKIPPED),
                        report.testCases().stream().map(TestCaseResult::outcome).toList()));
    }

    @Test
    void preservesDecodedMessagesCdataStackTracesAndSkipReasons() throws Exception {
        JUnitReport report = parser.parse(fixture("mixed-suite.xml"));
        TestCaseResult failure = report.testCases().get(1);
        OutcomeDetail detail = failure.details().get(0);

        assertAll(
                () -> assertEquals(List.of("synthetic.checkout"), failure.suitePath()),
                () -> assertEquals(Optional.of("example.CheckoutTest"), failure.className()),
                () -> assertEquals("calculatesTotal", failure.name()),
                () -> assertEquals(Optional.of("org.opentest4j.AssertionFailedError"), detail.type()),
                () -> assertEquals(Optional.of("expected: <42> but was: <41>"), detail.message()),
                () -> assertEquals("org.opentest4j.AssertionFailedError: expected: <42> but was: <41>\n"
                        + "    at example.CheckoutTest.calculatesTotal(CheckoutTest.java:42)\n", detail.text()),
                () -> assertEquals(Optional.of("disabled in this synthetic environment"),
                        report.testCases().get(3).details().get(0).message()),
                () -> assertEquals("Synthetic skip explanation.", report.testCases().get(3).details().get(0).text()));
    }

    @Test
    void passingSuiteKeepsAbsentClassMetadataUnknown() throws Exception {
        JUnitReport report = parser.parse(fixture("passing-suite.xml"));

        assertAll(
                () -> assertEquals(2, report.count(TestOutcome.PASSED)),
                () -> assertEquals(Optional.empty(), report.testCases().get(1).className()),
                () -> assertTrue(report.testCases().stream().allMatch(test -> test.details().isEmpty())));
    }

    @Test
    void nestedNamespacedSuitesKeepOrderRepeatedExecutionsAndAllDetails() throws Exception {
        JUnitReport report = parser.parse(fixture("nested-suites.xml"));

        assertAll(
                () -> assertEquals(3, report.testCases().size()),
                () -> assertEquals(List.of(List.of("module"), List.of("module", "nested"), List.of("sibling")),
                        report.testCases().stream().map(TestCaseResult::suitePath).toList()),
                () -> assertEquals(List.of("repeatedName", "repeatedName", "repeatedName"),
                        report.testCases().stream().map(TestCaseResult::name).toList()),
                () -> assertEquals(TestOutcome.ERROR, report.testCases().get(1).outcome()),
                () -> assertEquals(List.of(TestOutcome.FAILED, TestOutcome.FAILED, TestOutcome.ERROR),
                        report.testCases().get(1).details().stream().map(OutcomeDetail::outcome).toList()));
    }

    @Test
    void snapshotsOriginalBytesWithoutRewritingInputAndDefendsAgainstMutation(@TempDir Path directory)
            throws Exception {
        byte[] original = Files.readAllBytes(fixture("mixed-suite.xml"));
        Path input = directory.resolve("original report.xml");
        Files.write(input, original);
        JUnitReport report = parser.parse(input);
        String originalHash = report.source().sha256();
        byte[] exportedBytes = report.source().bytes();
        exportedBytes[0] = 0;

        byte[] suppliedBytes = original.clone();
        ReportSource independentSnapshot = new ReportSource("in-memory.xml", suppliedBytes);
        suppliedBytes[0] = 0;

        assertAll(
                () -> assertArrayEquals(original, Files.readAllBytes(input)),
                () -> assertArrayEquals(original, report.source().bytes()),
                () -> assertArrayEquals(original, independentSnapshot.bytes()),
                () -> assertEquals(input.toAbsolutePath().normalize().toString(), report.source().name()),
                () -> assertTrue(originalHash.matches("[0-9a-f]{64}")),
                () -> assertEquals(originalHash, report.source().sha256()),
                () -> assertEquals(originalHash, independentSnapshot.sha256()),
                () -> assertThrows(UnsupportedOperationException.class, () -> report.testCases().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> report.testCases().get(1).details().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> report.testCases().get(1).suitePath().clear()));
    }

    @Test
    void xmlDecodingDoesNotReplaceTheOriginalEncodingOrCrLfEvidence() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\r\n"
                + "<testsuite><testcase name=\"caf\u00e9\"><failure message=\"caf&#233;\">"
                + "caf\u00e9\r\nexpected &lt;42&gt;\r\n</failure></testcase></testsuite>";
        byte[] original = xml.getBytes(StandardCharsets.ISO_8859_1);
        JUnitReport report = parser.parse(new ReportSource("latin1.xml", original));
        OutcomeDetail detail = report.testCases().get(0).details().get(0);

        assertAll(
                () -> assertArrayEquals(original, report.source().bytes()),
                () -> assertEquals("caf\u00e9", report.testCases().get(0).name()),
                () -> assertEquals(Optional.of("caf\u00e9"), detail.message()),
                () -> assertEquals("caf\u00e9\nexpected <42>\n", detail.text()));
    }

    @Test
    void positionsDescribeTheEndOfTheOpeningTagRatherThanInventingAnExactSpan() throws Exception {
        String xml = """
                <testsuite name="synthetic">
                  <testcase name="multiLine">
                    <failure
                      type="java.lang.AssertionError"
                      message="synthetic">body</failure>
                  </testcase>
                </testsuite>
                """;
        JUnitReport report = parseXml(xml);

        assertAll(
                () -> assertEquals(2, report.testCases().get(0).location().line()),
                () -> assertEquals(5, report.testCases().get(0).details().get(0).location().line()),
                () -> assertEquals("synthetic.xml", report.testCases().get(0).details().get(0).location().sourceName()));
    }

    @Test
    void malformedXmlRejectsTheWholeReportAndRetainsTheSource() throws Exception {
        Path input = fixture("malformed.xml");
        ReportParseException exception = assertThrows(ReportParseException.class, () -> parser.parse(input));

        assertAll(
                () -> assertEquals(5, exception.location().line()),
                () -> assertTrue(exception.location().column() > 0),
                () -> assertTrue(exception.getMessage().contains("malformed.xml:5:")),
                () -> assertArrayEquals(Files.readAllBytes(input), exception.source().bytes()));
    }

    @Test
    void rejectsTheUnsafeFixture() throws Exception {
        ReportParseException exception = assertThrows(ReportParseException.class,
                () -> parser.parse(fixture("doctype.xml")));

        assertTrue(exception.getMessage().contains("DOCTYPE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"entity", "dtd", "internal"})
    void rejectsDoctypesBeforeTheyCanResolveAnyResource(String kind, @TempDir Path directory) throws Exception {
        Path external = directory.resolve("synthetic-resource.txt");
        Files.writeString(external, "SYNTHETIC_EXTERNAL_CONTENT");
        String doctype = switch (kind) {
            case "entity" -> "<!DOCTYPE testsuite [<!ENTITY external SYSTEM \"" + external.toUri() + "\">]>";
            case "dtd" -> "<!DOCTYPE testsuite SYSTEM \"" + external.toUri() + "\">";
            default -> "<!DOCTYPE testsuite [<!ENTITY external \"synthetic internal entity\">]>";
        };
        String body = "dtd".equals(kind) ? "synthetic" : "&external;";
        String xml = doctype + "<testsuite><testcase name=\"unsafe\"><failure>" + body
                + "</failure></testcase></testsuite>";

        ReportParseException exception = assertThrows(ReportParseException.class, () -> parseXml(xml));

        assertTrue(exception.getMessage().contains("DOCTYPE"));
    }

    @Test
    void rejectsXIncludeEvenInsideIgnoredMetadata(@TempDir Path directory) throws Exception {
        Path external = directory.resolve("synthetic.xml");
        Files.writeString(external, "<testcase name=\"mustNotBeImported\"/>");
        String xml = "<testsuite xmlns:xi=\"http://www.w3.org/2001/XInclude\"><properties>"
                + "<xi:include href=\"" + external.toUri() + "\"/></properties></testsuite>";

        ReportParseException exception = assertThrows(ReportParseException.class, () -> parseXml(xml));

        assertTrue(exception.getMessage().contains("XInclude"));
    }

    @Test
    void metadataSubtreesCannotAccidentallyAddTestExecutions() throws Exception {
        JUnitReport report = parseXml("<testsuite><producer-extension><testcase name=\"metadata\">"
                + "<failure/></testcase></producer-extension><testcase name=\"actual\"/></testsuite>");

        assertAll(
                () -> assertEquals(1, report.testCases().size()),
                () -> assertEquals("actual", report.testCases().get(0).name()),
                () -> assertEquals(List.of(""), report.testCases().get(0).suitePath()));
    }

    @Test
    void emptySuitesAreValidAndParserCallsDoNotShareState() throws Exception {
        assertEquals(4, parser.parse(fixture("mixed-suite.xml")).testCases().size());
        assertTrue(parseXml("<testsuite/>").testCases().isEmpty());
        assertEquals(2, parser.parse(fixture("passing-suite.xml")).testCases().size());
    }

    @ParameterizedTest
    @MethodSource("unsupportedReports")
    void unsupportedStructuresHaveExplicitDiagnostics(String xml, String expectedDiagnostic) {
        ReportParseException exception = assertThrows(ReportParseException.class, () -> parseXml(xml));

        assertTrue(exception.getMessage().contains(expectedDiagnostic), exception.getMessage());
    }

    static Stream<Arguments> unsupportedReports() {
        return Stream.of(
                Arguments.of("<report/>", "document root"),
                Arguments.of("<testsuites><testcase name=\"misplaced\"/></testsuites>", "direct child"),
                Arguments.of("<testsuite><testcase/></testsuite>", "nonblank name"),
                Arguments.of("<testsuite><testcase name=\" \"/></testsuite>", "nonblank name"),
                Arguments.of("<testsuite><error/></testsuite>", "direct child"),
                Arguments.of("<testsuite><testcase name=\"mixed\"><skipped/><failure/></testcase></testsuite>", "skipped marker"),
                Arguments.of("<testsuite><testcase name=\"mixed\"><error/><skipped/></testcase></testsuite>", "skipped marker"),
                Arguments.of("<testsuite><testcase name=\"mixed\"><skipped/><skipped/></testcase></testsuite>", "skipped marker"),
                Arguments.of("<testsuite><testcase name=\"markup\"><failure><b>bad</b></failure></testcase></testsuite>", "CDATA"),
                Arguments.of("<testsuite>unexpected text</testsuite>", "Unexpected text"));
    }

    private JUnitReport parseXml(String xml) throws ReportParseException {
        return parser.parse(new ReportSource("synthetic.xml", xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(JUnitXmlParserTest.class.getResource("/junit/" + name).toURI());
    }

    private static Path day2Fixture(String name) throws Exception {
        return Path.of(JUnitXmlParserTest.class.getResource("/fixtures/" + name).toURI());
    }
}
