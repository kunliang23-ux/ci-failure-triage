package org.citriage.report;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Parses a documented subset of JUnit XML without modifying the supplied report. */
public final class JUnitXmlParser {
    public JUnitReport parse(Path path) throws IOException, ReportParseException {
        Path sourcePath = path.toAbsolutePath().normalize();
        return parse(new ReportSource(sourcePath.toString(), Files.readAllBytes(sourcePath)));
    }

    public JUnitReport parse(ReportSource source) throws ReportParseException {
        Objects.requireNonNull(source, "source");
        ReportHandler handler = new ReportHandler(source.name());
        try {
            XMLReader reader = secureReader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External XML resources are disabled.");
            });
            reader.parse(new InputSource(new ByteArrayInputStream(source.bytes())));
            return new JUnitReport(source, handler.tests);
        } catch (SAXParseException exception) {
            SourceLocation location = new SourceLocation(source.name(), exception.getLineNumber(),
                    exception.getColumnNumber());
            throw new ReportParseException(source, location, exception.getMessage(), exception);
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new ReportParseException(source, handler.location(),
                    "Cannot parse report safely: " + exception.getMessage(), exception);
        }
    }

    private static XMLReader secureReader() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newDefaultNSInstance();
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return reader;
    }

    private static final class ReportHandler extends DefaultHandler {
        private final String sourceName;
        private final List<TestCaseResult> tests = new ArrayList<>();
        private final List<String> suites = new ArrayList<>();
        private final Deque<String> elements = new ArrayDeque<>();
        private Locator locator;
        private int ignoredDepth;
        private PendingTest test;
        private PendingDetail detail;

        private ReportHandler(String sourceName) {
            this.sourceName = sourceName;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String name, String qualifiedName, Attributes attributes)
                throws SAXException {
            if ("http://www.w3.org/2001/XInclude".equals(uri)) {
                throw invalid("XInclude is not supported; import a self-contained report.");
            }
            if (ignoredDepth > 0) {
                ignoredDepth++;
                return;
            }
            String parent = elements.peek();
            if (parent == null && !isContainer(name)) {
                throw invalid("Expected a <testsuite> or <testsuites> document root.");
            }
            if (detail != null) {
                throw invalid("Outcome text must use escaped XML or CDATA, rather than nested elements.");
            }
            switch (name) {
                case "testsuite", "testsuites" -> {
                    if (parent != null && !isContainer(parent)) {
                        throw invalid("<" + name + "> must be inside a suite container.");
                    }
                    if ("testsuite".equals(name)) {
                        suites.add(Optional.ofNullable(attributes.getValue("name")).orElse(""));
                    }
                }
                case "testcase" -> {
                    if (!"testsuite".equals(parent)) {
                        throw invalid("<testcase> must be a direct child of <testsuite>.");
                    }
                    String testName = attributes.getValue("name");
                    if (testName == null || testName.isBlank()) {
                        throw invalid("<testcase> requires a nonblank name attribute.");
                    }
                    TestIdentity identity = new TestIdentity(Optional.ofNullable(attributes.getValue("classname")), testName);
                    test = new PendingTest(List.copyOf(suites), identity,
                            duration(attributes.getValue("time")), location());
                }
                case "failure", "error", "skipped" -> {
                    if (!"testcase".equals(parent)) {
                        throw invalid("<" + name + "> must be a direct child of <testcase>.");
                    }
                    TestOutcome outcome = switch (name) {
                        case "failure" -> TestOutcome.FAILED;
                        case "error" -> TestOutcome.ERROR;
                        default -> TestOutcome.SKIPPED;
                    };
                    if ((outcome == TestOutcome.SKIPPED && !test.details.isEmpty())
                            || test.details.stream().anyMatch(item -> item.outcome() == TestOutcome.SKIPPED)) {
                        throw invalid("A skipped marker cannot be repeated or mixed with failures/errors.");
                    }
                    detail = new PendingDetail(outcome, Optional.ofNullable(attributes.getValue("type")),
                            Optional.ofNullable(attributes.getValue("message")), location());
                }
                default -> {
                    // Metadata/producer extensions are opaque, including any nested elements.
                    ignoredDepth = 1;
                    return;
                }
            }
            elements.push(name);
        }

        @Override
        public void characters(char[] characters, int start, int length) throws SAXException {
            if (ignoredDepth > 0) {
                return;
            }
            if (detail != null) {
                detail.text.append(characters, start, length);
            } else if (!new String(characters, start, length).isBlank()) {
                throw invalid("Unexpected text outside an outcome or metadata element.");
            }
        }

        @Override
        public void endElement(String uri, String name, String qualifiedName) {
            if (ignoredDepth > 0) {
                ignoredDepth--;
                return;
            }
            switch (name) {
                case "failure", "error", "skipped" -> {
                    test.details.add(new OutcomeDetail(detail.outcome, detail.type, detail.message,
                            detail.text.toString(), detail.location));
                    detail = null;
                }
                case "testcase" -> {
                    tests.add(new TestCaseResult(test.suites, test.identity, test.durationSeconds,
                            test.details, test.location));
                    test = null;
                }
                case "testsuite" -> suites.remove(suites.size() - 1);
                default -> {
                }
            }
            elements.pop();
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }

        private SourceLocation location() {
            return new SourceLocation(sourceName, locator == null ? -1 : locator.getLineNumber(),
                    locator == null ? -1 : locator.getColumnNumber());
        }

        private SAXParseException invalid(String message) {
            return new SAXParseException(message, locator);
        }

        private Optional<BigDecimal> duration(String value) throws SAXException {
            if (value == null) {
                return Optional.empty();
            }
            try {
                BigDecimal seconds = new BigDecimal(value);
                if (seconds.signum() >= 0) {
                    return Optional.of(seconds);
                }
            } catch (NumberFormatException exception) {
                // Report malformed metadata with the same source-aware diagnostic as a negative duration.
            }
            throw invalid("Invalid testcase time '" + value + "'; expected a nonnegative number of seconds.");
        }

        private static boolean isContainer(String name) {
            return "testsuite".equals(name) || "testsuites".equals(name);
        }
    }

    private static final class PendingTest {
        private final List<String> suites;
        private final TestIdentity identity;
        private final Optional<BigDecimal> durationSeconds;
        private final SourceLocation location;
        private final List<OutcomeDetail> details = new ArrayList<>();

        private PendingTest(List<String> suites, TestIdentity identity, Optional<BigDecimal> durationSeconds,
                            SourceLocation location) {
            this.suites = suites;
            this.identity = identity;
            this.durationSeconds = durationSeconds;
            this.location = location;
        }
    }

    private static final class PendingDetail {
        private final TestOutcome outcome;
        private final Optional<String> type;
        private final Optional<String> message;
        private final SourceLocation location;
        private final StringBuilder text = new StringBuilder();

        private PendingDetail(TestOutcome outcome, Optional<String> type, Optional<String> message,
                              SourceLocation location) {
            this.outcome = outcome;
            this.type = type;
            this.message = message;
            this.location = location;
        }
    }
}
