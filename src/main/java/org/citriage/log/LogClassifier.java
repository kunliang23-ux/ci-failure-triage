package org.citriage.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.citriage.report.TestIdentity;

import static org.citriage.log.LogDiagnosticText.ERROR_LEVEL;
import static org.citriage.log.LogDiagnosticText.JAVA_NAME;
import static org.citriage.log.LogDiagnosticText.QUALIFIED_NAME;
import static org.citriage.log.LogDiagnosticText.STACK_FRAME;
import static org.citriage.log.LogDiagnosticText.SUREFIRE_TEST;
import static org.citriage.log.LogDiagnosticText.diagnosticText;

/** Recognizes explicit diagnostics and attaches only adjacent, recognizable context. */
public final class LogClassifier {
    private static final Pattern SUREFIRE_TOTALS = Pattern.compile(
            "Tests run:\\s*\\d+,\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)(?:,.*)?");
    private static final Pattern SUREFIRE_RESULT_ENTRY = Pattern.compile(
            QUALIFIED_NAME + "(?:\\[\\d+])?(?::\\d+\\s+\\S.*|\\s+\u00bb\\s+\\S.*)");
    private static final Pattern TEST_FAILURE_SUMMARY = Pattern.compile(
            "(?:Failed to execute goal .+?:\\s+)?There are test failures\\b.*|Tests? failed[.!]?");
    private static final Pattern BUILD_FAILURE_SUMMARY = Pattern.compile(
            "(?i:BUILD (?:FAILURE|FAILED))[.!]?|Process completed with exit code 0*[1-9]\\d*\\.?"
                    + "|Failed to execute goal (?:.+ )?on project [^\\s:]+\\s*:?\\s*(?:-> \\[Help \\d+])?");

    private static final Pattern EXCEPTION_PREFIX = Pattern.compile(
            "^(?:(?:Caused by:|Suppressed:)\\s*|Exception in thread \"[^\"]+\"\\s+)");
    private static final Pattern STACK_CONTEXT = Pattern.compile(
            STACK_FRAME.pattern() + "|(?:Caused by:|Suppressed:)\\s+\\S.*");
    private static final String ASSERTION_TYPE = "(?:(?:java\\.lang\\.)?AssertionError"
            + "|(?:org\\.opentest4j\\.|junit\\.framework\\.)?AssertionFailedError"
            + "|(?:org\\.opentest4j\\.)?MultipleFailuresError"
            + "|(?:org\\.junit\\.|junit\\.framework\\.)?ComparisonFailure)";
    private static final Pattern ASSERTION = Pattern.compile(ASSERTION_TYPE + "(?::.*)?");
    private static final Pattern TIMEOUT_TYPE = Pattern.compile(
            "(?:(?:java\\.util\\.concurrent\\.)?TimeoutException|(?:java\\.net\\.)?SocketTimeoutException"
                    + "|(?:org\\.junit\\.runners\\.model\\.)?TestTimedOutException)(?::.*)?");
    private static final Pattern TIMEOUT_MESSAGE = Pattern.compile(
            "(?:" + ASSERTION_TYPE + ":\\s*)?(?i:(?:(?:execution |test )?timed out after"
                    + "|execution exceeded timeout of)\\s+\\d+(?:\\.\\d+)?\\s*"
                    + "(?:ns|ms|s|nanoseconds?|microseconds?|milliseconds?|seconds?|minutes?|hours?)\\b.*)"
                    + "|There was a timeout in the fork\\b.*");
    private static final Pattern CLASS_LOADING = Pattern.compile(
            "(?:java\\.lang\\.)?ClassNotFoundException(?::.*)?"
                    + "|(?:java\\.lang\\.)?NoClassDefFoundError(?::(?!\\s*Could not initialize class\\b).*)?");
    private static final Pattern THROWABLE = Pattern.compile(
            "(?:" + JAVA_NAME + "\\.)*(?:" + JAVA_NAME + ")?(?:Error|Exception|Throwable)(?::.*)?");

    private static final Pattern DEPENDENCY_HEADER = Pattern.compile(
            "(?:Failed to execute goal .+?:\\s+)?"
                    + "(?:Could not resolve dependencies|Failed to collect dependencies)\\b.*");
    private static final Pattern ARTIFACT_FAILURE = Pattern.compile(
            "(?:Could not (?:find|resolve|transfer) artifact|Failed to read artifact descriptor for)\\s+\\S.*");
    private static final Pattern COMPILER_DETAIL = Pattern.compile(
            ".+\\.java:(?:\\[\\d+,\\s*\\d+]\\s*\\S.*|\\d+:(?:\\d+:)?\\s*error:\\s*\\S.*)"
                    + "|(?:error:\\s*)?(?:cannot find symbol\\b.*|package\\s+" + QUALIFIED_NAME
                    + "\\s+does not exist\\b.*|invalid (?:target|source) release\\b.*"
                    + "|release version \\d+ not supported\\b.*)");
    private static final Pattern COMPILER_SUMMARY = Pattern.compile(
            "(?i:COMPILATION (?:ERROR|FAILURE))\\b.*"
                    + "|Failed to execute goal .*maven-compiler-plugin.*\\b"
                    + "(?:Compilation failure|Compilation error|(?:Fatal error|Error) compiling)\\b.*");
    private static final Pattern CARET = Pattern.compile("\\^+");
    private static final Pattern COMPILER_CONTEXT = Pattern.compile(
            "(?:symbol|location|required|found|reason):.*|\\^+");

    public List<LogFinding> classify(BuildLog log) {
        List<String> matchingLines = log.lines().stream().map(LogDiagnosticText::matchingText).toList();
        List<LogFinding> findings = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < matchingLines.size(); i++) {
            if (i > start && "[INFO] Scanning for projects...".equals(matchingLines.get(i))) {
                findings.addAll(classifyRun(log, matchingLines, start, i));
                start = i;
            }
        }
        findings.addAll(classifyRun(log, matchingLines, start, matchingLines.size()));
        return List.copyOf(findings);
    }

    private static List<LogFinding> classifyRun(BuildLog log, List<String> lines, int start, int end) {
        List<LogFinding> diagnostics = new ArrayList<>();
        LogFinding compilerSummary = null;
        LogFinding testSummary = null;
        LogFinding buildSummary = null;
        for (int i = start; i < end;) {
            String line = lines.get(i);
            String body = diagnosticText(line);
            String summary = line.startsWith("[INFO]") ? line.substring("[INFO]".length()).stripLeading() : body;
            Matcher testHeader = SUREFIRE_TEST.matcher(body);
            Matcher totals = SUREFIRE_TOTALS.matcher(summary);
            if (testHeader.matches()) {
                TestIdentity test = new TestIdentity(Optional.of(testHeader.group(1)), testHeader.group(2));
                LogFinding finding = testFinding(log, lines, i, end, test);
                diagnostics.add(finding);
                // The next zero-based index is the inclusive one-based line number of the last line.
                i = finding.evidence().lastLine();
            } else if (totals.matches()) {
                if (testSummary == null && (positive(totals.group(1)) || positive(totals.group(2)))) {
                    testSummary = finding(log, Rule.TEST_SUMMARY, i, i + 1);
                }
                i++;
            } else if ("Failures:".equals(summary) || "Errors:".equals(summary)) {
                int after = i + 1;
                while (after < end && SUREFIRE_RESULT_ENTRY.matcher(diagnosticText(lines.get(after))).matches()) {
                    after++;
                }
                if (testSummary == null || after - i > testSummary.evidence().lines().size()) {
                    testSummary = finding(log, Rule.TEST_SUMMARY, i, after);
                }
                i = after;
            } else if (TEST_FAILURE_SUMMARY.matcher(summary).matches()) {
                if (testSummary == null) {
                    testSummary = finding(log, Rule.TEST_SUMMARY, i, i + 1);
                }
                i++;
            } else if (BUILD_FAILURE_SUMMARY.matcher(summary).matches()) {
                if (buildSummary == null) {
                    buildSummary = finding(log, Rule.BUILD_FAILURE, i, i + 1);
                }
                i++;
            } else if (COMPILER_SUMMARY.matcher(body).matches()) {
                if (compilerSummary == null) {
                    compilerSummary = finding(log, Rule.COMPILER_SUMMARY, i, i + 1);
                }
                i++;
            } else {
                LogFinding finding = diagnosticFinding(log, lines, i, end, body);
                if (finding == null) {
                    i++;
                } else {
                    diagnostics.add(finding);
                    i = finding.evidence().lastLine();
                }
            }
        }

        if (compilerSummary != null && diagnostics.stream().noneMatch(
                finding -> finding.category() == DiagnosticCategory.COMPILATION_ERROR)) {
            diagnostics.add(compilerSummary);
            diagnostics.sort(Comparator.comparingInt(finding -> finding.evidence().firstLine()));
        }
        if (diagnostics.isEmpty()) {
            LogFinding fallback = testSummary != null ? testSummary : buildSummary;
            return fallback == null ? List.of() : List.of(fallback);
        }
        return diagnostics;
    }

    private static LogFinding diagnosticFinding(BuildLog log, List<String> lines, int index, int end, String body) {
        Rule exception = exceptionRule(body);
        if (exception != null) {
            return exceptionFinding(log, lines, index, index, end, exception, Optional.empty());
        }
        if (DEPENDENCY_HEADER.matcher(body).matches()) {
            int after = index + 1;
            while (after < end && isDependencyDetail(diagnosticText(lines.get(after)))) {
                after++;
            }
            return finding(log, Rule.MAVEN_DEPENDENCY, index, after);
        }
        if (ARTIFACT_FAILURE.matcher(body).matches()) {
            return finding(log, Rule.MAVEN_DEPENDENCY, index, index + 1);
        }
        if (COMPILER_DETAIL.matcher(body).matches()) {
            return finding(log, Rule.COMPILER_DETAIL, index, compilerEnd(lines, index + 1, end));
        }
        if (!lines.get(index).equals(body) && !isMavenBoilerplate(body)) {
            return finding(log, Rule.ERROR_LINE, index, index + 1);
        }
        return null;
    }

    private static LogFinding testFinding(BuildLog log, List<String> lines, int start, int end, TestIdentity test) {
        int next = start + 1;
        Rule exception = next < end ? exceptionRule(diagnosticText(lines.get(next))) : null;
        if (exception == null) {
            return finding(log, Rule.TEST_FAILURE, start, next, Optional.of(test));
        }
        return exceptionFinding(log, lines, start, next, end, exception, Optional.of(test));
    }

    private static LogFinding exceptionFinding(BuildLog log, List<String> lines, int start, int exceptionIndex,
                                               int end, Rule rule, Optional<TestIdentity> test) {
        int after = exceptionIndex + 1;
        while (after < end) {
            String body = diagnosticText(lines.get(after));
            if (!STACK_CONTEXT.matcher(body).matches()) {
                break;
            }
            if (rule == Rule.EXCEPTION) {
                Rule cause = exceptionRule(body);
                if (cause != null) {
                    rule = cause;
                }
            }
            after++;
        }
        return finding(log, rule, start, after, test);
    }

    private static Rule exceptionRule(String body) {
        String message = EXCEPTION_PREFIX.matcher(body).replaceFirst("");
        if (TIMEOUT_TYPE.matcher(message).matches() || TIMEOUT_MESSAGE.matcher(message).matches()) {
            return Rule.TIMEOUT;
        }
        if (ASSERTION.matcher(message).matches()) {
            return Rule.ASSERTION;
        }
        if (CLASS_LOADING.matcher(message).matches()) {
            return Rule.CLASS_LOADING;
        }
        return THROWABLE.matcher(message).matches() ? Rule.EXCEPTION : null;
    }

    private static int compilerEnd(List<String> lines, int next, int end) {
        // javac may print a source line followed by the caret pointing to the error.
        if (next + 1 < end && !lines.get(next).isBlank() && !ERROR_LEVEL.matcher(lines.get(next)).find()
                && exceptionRule(lines.get(next)) == null && !COMPILER_DETAIL.matcher(lines.get(next)).matches()
                && CARET.matcher(lines.get(next + 1)).matches()) {
            next++;
        }
        while (next < end && COMPILER_CONTEXT.matcher(diagnosticText(lines.get(next))).matches()) {
            next++;
        }
        return next;
    }

    private static boolean isDependencyDetail(String body) {
        return body.startsWith("dependency: ") || ARTIFACT_FAILURE.matcher(body).matches();
    }

    private static boolean isMavenBoilerplate(String body) {
        return body.isEmpty() || body.matches("-{3,}") || body.startsWith("-> [Help ")
                || body.startsWith("[Help ") || body.startsWith("Please refer to ")
                || body.startsWith("To see the full stack trace") || body.startsWith("Re-run Maven using ")
                || body.startsWith("For more information about the errors");
    }

    private static boolean positive(String digits) {
        return digits.chars().anyMatch(digit -> digit != '0');
    }

    private static LogFinding finding(BuildLog log, Rule rule, int start, int end) {
        return finding(log, rule, start, end, Optional.empty());
    }

    private static LogFinding finding(BuildLog log, Rule rule, int start, int end, Optional<TestIdentity> test) {
        LogEvidence evidence = new LogEvidence(log.source(), log.lines().subList(start, end));
        return new LogFinding(rule.category, rule.id, rule.explanation, evidence, test);
    }

    private enum Rule {
        TIMEOUT(DiagnosticCategory.TIMEOUT, "java-timeout", "Java or JUnit reported an exceeded time limit."),
        ASSERTION(DiagnosticCategory.ASSERTION_ERROR, "java-assertion", "Java or JUnit reported an assertion failure."),
        CLASS_LOADING(DiagnosticCategory.MISSING_DEPENDENCY, "java-class-loading",
                "Java reported a class it could not load."),
        MAVEN_DEPENDENCY(DiagnosticCategory.MISSING_DEPENDENCY, "maven-dependency-resolution",
                "Maven reported a dependency or artifact it could not resolve."),
        COMPILER_DETAIL(DiagnosticCategory.COMPILATION_ERROR, "java-compiler",
                "The Java compiler reported an error."),
        COMPILER_SUMMARY(DiagnosticCategory.COMPILATION_ERROR, "maven-compilation",
                "Maven reported compilation failure in its summary."),
        TEST_FAILURE(DiagnosticCategory.UNKNOWN, "surefire-test-failure",
                "Surefire reported a failed test without a recognized diagnostic."),
        TEST_SUMMARY(DiagnosticCategory.UNKNOWN, "surefire-failure-summary",
                "The test summary indicates a failure without a recognized diagnostic."),
        BUILD_FAILURE(DiagnosticCategory.UNKNOWN, "build-failure-summary",
                "The run summary indicates a failure without a recognized diagnostic."),
        EXCEPTION(DiagnosticCategory.UNKNOWN, "unclassified-java-exception",
                "A Java exception or error was reported without a recognized diagnostic."),
        ERROR_LINE(DiagnosticCategory.UNKNOWN, "unclassified-error",
                "An error was logged without a recognized diagnostic.");

        private final DiagnosticCategory category;
        private final String id;
        private final String explanation;

        Rule(DiagnosticCategory category, String id, String explanation) {
            this.category = category;
            this.id = id;
            this.explanation = explanation;
        }
    }
}
