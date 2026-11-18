package com.kunliang.citriage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

import com.kunliang.citriage.report.JUnitReport;
import com.kunliang.citriage.report.JUnitXmlParser;
import com.kunliang.citriage.report.OutcomeDetail;
import com.kunliang.citriage.report.ReportParseException;
import com.kunliang.citriage.report.TestCaseResult;
import com.kunliang.citriage.report.TestOutcome;

/** The command-line entry point for local CI report investigation. */
public final class CiTriageCli {
    private static final String HELP = """
            CI Failure Triage
            Help developers answer: Why did my tests fail, and what should I investigate first?

            Import a local JUnit XML report and inspect reported test outcomes.

            Usage: java -jar target/ci-failure-triage.jar import-junit <report-path>
                   java -jar target/ci-failure-triage.jar [--help | --version | --report <path>]

            Arguments (choose one operation):
              import-junit <report-path>  Import one local JUnit XML report.
              --help                      Show this help message.
              --version                   Show the application version.
              --report <path>             Compatibility alias for import-junit.

            Quote report paths containing spaces.
            With no arguments, show help.
            Exit codes: 0 = success; 1 = unreadable/invalid report; 2 = invalid arguments.
            A successfully imported report returns 0 even when it contains failed tests.
            Log classification and AI explanations will be added later.
            """;

    private CiTriageCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Handles arguments using caller-owned streams and returns an exit code without exiting. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && ("import-junit".equals(args[0]) || "--report".equals(args[0]))) {
            if (args.length != 2 || args[1].isBlank() || args[1].startsWith("--")) {
                return argumentError(err, args[0] + " requires exactly one file path.");
            }
            return importReport(args[1], out, err);
        }

        // Validate every argument before producing successful output.
        for (String argument : args) {
            if (!"--help".equals(argument) && !"--version".equals(argument)
                    && !"--report".equals(argument) && !"import-junit".equals(argument)) {
                return argumentError(err, "Unknown argument: '" + argument + "'.");
            }
        }

        if (args.length > 1) {
            return argumentError(err,
                    "Choose only one argument: --help, --version, or import-junit <report-path> (--report <path> alias).");
        }

        if (args.length == 0 || "--help".equals(args[0])) {
            out.print(HELP);
        } else {
            out.println("CI Failure Triage " + applicationVersion());
        }
        return 0;
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
