package com.kunliang.citriage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import com.kunliang.citriage.log.BuildLog;
import com.kunliang.citriage.log.LogClassifier;
import com.kunliang.citriage.log.LogEvidence;
import com.kunliang.citriage.log.LogFinding;
import com.kunliang.citriage.log.LogLine;
import com.kunliang.citriage.log.LogReader;
import com.kunliang.citriage.report.JUnitReport;
import com.kunliang.citriage.report.JUnitXmlParser;
import com.kunliang.citriage.report.OutcomeDetail;
import com.kunliang.citriage.report.ReportParseException;
import com.kunliang.citriage.report.TestCaseResult;
import com.kunliang.citriage.report.TestOutcome;

/** The command-line entry point for local CI evidence. */
public final class CiTriageCli {
    private static final String HELP = """
            CI Failure Triage
            Help developers answer: Why did my tests fail, and what should I investigate first?

            Import a local JUnit XML report or inspect failure diagnostics in a local UTF-8 log.

            Usage: java -jar target/ci-failure-triage.jar import-junit <report-path>
                   java -jar target/ci-failure-triage.jar import-log <log-path> [--commit <sha>] [--environment <label>]
                   java -jar target/ci-failure-triage.jar [--help | --version | --report <path>]

            Arguments (choose one operation):
              import-junit <report-path>  Import one local JUnit XML report.
              import-log <log-path>       Import one local UTF-8 log.
              --help                      Show this help message.
              --version                   Show the application version.
              --report <path>             Compatibility alias for import-junit.

            For import-log, each optional metadata flag may appear once after the path.
            Omitted commit and environment values are displayed as unknown.
            Quote paths and environment labels containing spaces.
            With no arguments, show help.
            Exit codes: 0 = success, 1 = unreadable or invalid input, 2 = invalid arguments.
            Successful imports return 0 even when they contain failure diagnostics.
            """;

    private CiTriageCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Handles arguments using caller-owned streams and returns an exit code without exiting. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && "import-log".equals(args[0])) {
            return importLog(args, out, err);
        }
        if (args.length > 0 && ("import-junit".equals(args[0]) || "--report".equals(args[0]))) {
            if (args.length != 2 || missingValue(args[1])) {
                return argumentError(err, args[0] + " requires exactly one file path.");
            }
            return importReport(args[1], out, err);
        }

        // Validate every argument before producing successful output.
        for (String argument : args) {
            if (!"--help".equals(argument) && !"--version".equals(argument)
                    && !"--report".equals(argument) && !"import-junit".equals(argument)
                    && !"import-log".equals(argument)) {
                return argumentError(err, "Unknown argument: '" + argument + "'.");
            }
        }

        if (args.length > 1) {
            return argumentError(err,
                    "Choose only one argument: --help, --version, import-junit, --report, or import-log.");
        }

        if (args.length == 0 || "--help".equals(args[0])) {
            out.print(HELP);
        } else {
            out.println("CI Failure Triage " + applicationVersion());
        }
        return 0;
    }

    private static int importLog(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || missingValue(args[1])) {
            return argumentError(err, "import-log requires a log file path.");
        }
        String commit = null;
        String environment = null;
        for (int i = 2; i < args.length; i += 2) {
            String option = args[i];
            if (!"--commit".equals(option) && !"--environment".equals(option)) {
                return argumentError(err, "Unknown import-log option: '" + option + "'.");
            }
            if (i + 1 == args.length || missingValue(args[i + 1])) {
                return argumentError(err, option + " requires a value.");
            }
            if ("--commit".equals(option)) {
                if (commit != null) {
                    return argumentError(err, "--commit may be specified only once.");
                }
                commit = args[i + 1];
            } else {
                if (environment != null) {
                    return argumentError(err, "--environment may be specified only once.");
                }
                environment = args[i + 1];
            }
        }

        String path = args[1];
        BuildLog log;
        try {
            log = new LogReader().read(Path.of(path));
        } catch (InvalidPathException exception) {
            return argumentError(err, "Invalid log path: " + exception.getReason());
        } catch (NoSuchFileException exception) {
            err.println("Error: Cannot read log '" + printableLogText(path) + "': file does not exist.");
            return 1;
        } catch (AccessDeniedException exception) {
            err.println("Error: Cannot read log '" + printableLogText(path) + "': access denied.");
            return 1;
        } catch (CharacterCodingException exception) {
            err.println("Error: Cannot decode log '" + printableLogText(path) + "': expected valid UTF-8.");
            return 1;
        } catch (IOException exception) {
            String reason = exception.getMessage();
            err.println("Error: Cannot read log '" + printableLogText(path) + "': "
                    + (reason == null ? "file I/O failure." : printableLogText(reason)));
            return 1;
        }

        printLog(log, new LogClassifier().classify(log), commit, environment, out);
        return 0;
    }

    private static void printLog(BuildLog log, List<LogFinding> findings, String commit, String environment,
                                 PrintStream out) {
        out.println("Log: " + printableLogText(log.source().name()));
        out.println("SHA-256: " + log.source().sha256());
        out.println("Commit: " + (commit == null ? "unknown" : printableLogText(commit)));
        out.println("Environment: " + (environment == null ? "unknown" : printableLogText(environment)));
        out.println("Lines: " + log.lines().size());
        out.println("Findings: " + findings.size());
        if (log.isBlank()) {
            out.println("Evidence unavailable: the log is empty or blank.");
        } else if (findings.isEmpty()) {
            out.println("No failure diagnostics matched.");
        } else {
            out.println("Findings describe diagnostics observed in this log.");
        }
        for (LogFinding finding : findings) {
            LogEvidence evidence = finding.evidence();
            out.println();
            out.println(finding.category() + " [" + finding.ruleId() + "]");
            out.println("  " + finding.explanation());
            finding.testIdentity().ifPresent(test -> out.println("  Test: " + printableLogText(test.displayName())));
            String range = evidence.firstLine() == evidence.lastLine() ? Integer.toString(evidence.firstLine())
                    : evidence.firstLine() + "-" + evidence.lastLine();
            out.println("  Evidence: " + printableLogText(evidence.source().name()) + ":" + range);
            for (LogLine line : evidence.lines()) {
                out.println("    " + line.number() + " | " + printableLogText(line.text()));
            }
        }
    }

    private static String printableLogText(String text) {
        StringBuilder display = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isISOControl(character) && character != '\t') {
                display.append("\\u00").append(Character.forDigit(character >> 4, 16))
                        .append(Character.forDigit(character & 0xf, 16));
            } else {
                display.append(character);
            }
        }
        return display.toString();
    }

    private static boolean missingValue(String value) {
        return value.isBlank() || value.startsWith("--");
    }

    private static int importReport(String path, PrintStream out, PrintStream err) {
        JUnitReport report;
        try {
            report = new JUnitXmlParser().parse(Path.of(path));
        } catch (InvalidPathException exception) {
            return argumentError(err, "Invalid report path: " + exception.getReason());
        } catch (IOException exception) {
            err.println("Error: Cannot read report '" + path + "': " + exception.getMessage());
            return 1;
        } catch (ReportParseException exception) {
            err.println("Error: " + exception.getMessage());
            return 1;
        }

        // Only emit a result after the entire report has been accepted.
        out.println("Report: " + report.source().name());
        out.println("SHA-256: " + report.source().sha256());
        out.printf("Tests: %d | passed=%d failed=%d errors=%d skipped=%d%n", report.testCases().size(),
                report.count(TestOutcome.PASSED), report.count(TestOutcome.FAILED),
                report.count(TestOutcome.ERROR), report.count(TestOutcome.SKIPPED));
        for (TestCaseResult test : report.testCases()) {
            out.println(test.outcome() + " " + test.identity().displayName());
            test.durationSeconds().ifPresent(seconds -> out.println("  Duration (seconds): " + seconds));
            for (OutcomeDetail detail : test.details()) {
                out.println("  " + detail.outcome() + ": " + detail.message().orElse("(no message supplied)"));
                detail.type().ifPresent(type -> out.println("  Type: " + type));
                out.println("  XML diagnostic location: " + detail.location().display());
                if (!detail.text().isEmpty()) {
                    out.print(detail.text());
                    if (!detail.text().endsWith("\n") && !detail.text().endsWith("\r")) {
                        out.println();
                    }
                }
            }
        }
        return 0;
    }

    private static int argumentError(PrintStream err, String message) {
        err.println("Error: " + message);
        err.println("Run with --help for usage.");
        return 2;
    }

    private static String applicationVersion() {
        Properties properties = new Properties();
        try (InputStream input = CiTriageCli.class.getResourceAsStream("/ci-triage.properties")) {
            if (input == null) {
                throw new IllegalStateException("Application version resource is missing.");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read application version.", exception);
        }
        String version = properties.getProperty("version");
        if (version == null || version.isBlank() || version.contains("${")) {
            throw new IllegalStateException("Application version was not supplied by Maven.");
        }
        return version;
    }
}
