package com.kunliang.citriage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import com.kunliang.citriage.log.BuildLog;
import com.kunliang.citriage.log.FailureGroup;
import com.kunliang.citriage.log.LogClassifier;
import com.kunliang.citriage.log.LogEvidence;
import com.kunliang.citriage.log.LogFinding;
import com.kunliang.citriage.log.LogGrouper;
import com.kunliang.citriage.log.LogLine;
import com.kunliang.citriage.log.LogReader;
import com.kunliang.citriage.report.JUnitReport;
import com.kunliang.citriage.report.JUnitXmlParser;
import com.kunliang.citriage.report.OutcomeDetail;
import com.kunliang.citriage.report.ReportParseException;
import com.kunliang.citriage.report.TestCaseResult;
import com.kunliang.citriage.report.TestOutcome;
import com.kunliang.citriage.storage.RunMetadata;
import com.kunliang.citriage.storage.RunMetadata.Field;
import com.kunliang.citriage.storage.SavedRun;
import com.kunliang.citriage.storage.SqliteRunRepository;
import com.kunliang.citriage.storage.SqliteRunRepository.RunSummary;
import com.kunliang.citriage.storage.SqliteRunRepository.SaveResult;

/** The command-line entry point for local CI evidence. */
public final class CiTriageCli {
    private static final String HELP = """
            CI Failure Triage
            Help developers answer: Why did my tests fail, and what should I investigate first?

            Import a local JUnit XML report or inspect failure diagnostics in a local UTF-8 log.
            Save evidence to SQLite and reopen it by run ID.

            Usage: java -jar target/ci-failure-triage.jar import-junit <report-path> [--db <database-path> [--run-id <id>] [--commit <sha>] [--environment <label>]]
                   java -jar target/ci-failure-triage.jar import-log <log-path> [--commit <sha>] [--environment <label>] [--group]
                         [--db <database-path> [--run-id <id>]]
                   java -jar target/ci-failure-triage.jar runs --db <database-path>
                   java -jar target/ci-failure-triage.jar show-run <id> --db <database-path> [--group]
                   java -jar target/ci-failure-triage.jar [--help | --version | --report <path>]

            Arguments (choose one operation):
              import-junit <report-path>  Import one local JUnit XML report.
              import-log <log-path>       Import one local UTF-8 log.
              runs                       List saved runs.
              show-run <id>               Show saved results and evidence for a run.
              --help                      Show this help message.
              --version                   Show the application version.
              --report <path>             Compatibility alias for import-junit.

            Supply each import option at most once after the source path.
            --db saves an import. Omit --run-id to create a fresh run ID.
            Use --group to group similar diagnostics and show every member's evidence.
            Omitted commit and environment values are displayed as unknown.
            Quote paths and environment labels containing spaces.
            With no arguments, show help.
            Exit codes: 0 = success, 1 = file, parsing, database, or missing-run error, 2 = invalid arguments.
            Successful imports return 0 even when they contain failure diagnostics.
            """;

    private CiTriageCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Handles arguments using caller-owned streams and returns an exit code without exiting. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0) {
            try {
                switch (args[0]) {
                    case "import-log": return importLog(args, out, err);
                    case "import-junit", "--report": return importReport(args, out, err);
                    case "runs": return listRuns(args, out);
                    case "show-run": return showRun(args, out);
                    default: break;
                }
            } catch (UsageException exception) {
                return argumentError(err, exception.getMessage());
            } catch (SQLException exception) {
                err.println("Error: Database operation failed: " + printableLogText(exception.getMessage()));
                return 1;
            }
        }

        // Validate every argument before producing successful output.
        for (String argument : args) {
            if (!"--help".equals(argument) && !"--version".equals(argument)
                    && !"--report".equals(argument) && !"import-junit".equals(argument)
                    && !"import-log".equals(argument) && !"runs".equals(argument) && !"show-run".equals(argument)) {
                return argumentError(err, "Unknown argument: '" + argument + "'.");
            }
        }

        if (args.length > 1) {
            return argumentError(err,
                    "Choose only one argument: --help, --version, import-junit, --report, import-log, runs, or show-run.");
        }

        if (args.length == 0 || "--help".equals(args[0])) {
            out.print(HELP);
        } else {
            out.println("CI Failure Triage " + applicationVersion());
        }
        return 0;
    }

    private static int importLog(String[] args, PrintStream out, PrintStream err) throws SQLException {
        if (args.length < 2 || missingValue(args[1])) {
            return argumentError(err, "import-log requires a log file path.");
        }
        Map<String, String> options = parseOptions(args, 2, "--commit", "--environment", "--group", "--db", "--run-id");
        Path database = importDatabase(options);
        String commit = options.get("--commit");
        String environment = options.get("--environment");
        boolean grouped = options.containsKey("--group");

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

        List<LogFinding> findings = new LogClassifier().classify(log);
        List<FailureGroup> groups = grouped || database != null ? new LogGrouper().group(findings) : List.of();
        if (database != null) {
            SaveResult result = new SqliteRunRepository(database).saveLog(runId(options),
                    RunMetadata.supplied(commit, environment), log, findings, groups);
            printSaveResult(result, out);
        }
        if (grouped) {
            printGroupedLog(log, findings.size(), groups, commit, environment, out);
        } else {
            printLog(log, findings, commit, environment, out);
        }
        return 0;
    }

    private static void printLog(BuildLog log, List<LogFinding> findings, String commit, String environment,
                                 PrintStream out) {
        printLogSummary(log, findings.size(), commit, environment, out);
        printLogStatus(log, findings.size(), "Findings describe diagnostics observed in this log.", out);
        for (LogFinding finding : findings) {
            out.println();
            out.println(finding.category() + " [" + finding.ruleId() + "]");
            out.println("  " + finding.explanation());
            finding.testIdentity().ifPresent(test -> out.println("  Test: " + printableLogText(test.displayName())));
            printEvidence(finding.evidence(), out);
        }
    }

    private static void printGroupedLog(BuildLog log, int findingCount, List<FailureGroup> groups, String commit,
                                        String environment, PrintStream out) {
        printLogSummary(log, findingCount, commit, environment, out);
        out.println("Groups: " + groups.size());
        printLogStatus(log, findingCount,
                "Groups indicate diagnostic similarity. A shared root cause requires further evidence.", out);
        for (FailureGroup group : groups) {
            out.println();
            out.println("Group " + group.id() + " | " + group.category() + " | occurrences=" + group.occurrenceCount());
            out.println("  Distinct known tests: " + group.distinctKnownTestCount()
                    + " | Unknown test occurrences: " + group.unknownTestOccurrenceCount());
            out.println("  Representative diagnostic:");
            for (String line : group.signature().split("\n", -1)) {
                out.println("    " + printableLogText(line));
            }
            for (LogFinding member : group.members()) {
                String testName = member.testIdentity().map(test -> printableLogText(test.displayName()))
                        .orElse("unknown");
                out.println("  Test: " + testName + " [" + member.ruleId() + "]");
                printEvidence(member.evidence(), out);
            }
        }
    }

    private static void printLogSummary(BuildLog log, int findingCount, String commit, String environment,
                                        PrintStream out) {
        out.println("Log: " + printableLogText(log.source().name()));
        out.println("SHA-256: " + log.source().sha256());
        out.println("Commit: " + (commit == null ? "unknown" : printableLogText(commit)));
        out.println("Environment: " + (environment == null ? "unknown" : printableLogText(environment)));
        out.println("Lines: " + log.lines().size());
        out.println("Findings: " + findingCount);
    }

    private static void printLogStatus(BuildLog log, int findingCount, String description, PrintStream out) {
        if (log.isBlank()) {
            out.println("Evidence unavailable: the log is empty or blank.");
        } else if (findingCount == 0) {
            out.println("No failure diagnostics matched.");
        } else {
            out.println(description);
        }
    }

    private static void printEvidence(LogEvidence evidence, PrintStream out) {
        String range = evidence.firstLine() == evidence.lastLine() ? Integer.toString(evidence.firstLine())
                : evidence.firstLine() + "-" + evidence.lastLine();
        out.println("  Evidence: " + printableLogText(evidence.source().name()) + ":" + range);
        for (LogLine line : evidence.lines()) {
            out.println("    " + line.number() + " | " + printableLogText(line.text()));
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

    private static int importReport(String[] args, PrintStream out, PrintStream err) throws SQLException {
        if (args.length < 2 || missingValue(args[1]) || ("--report".equals(args[0]) && args.length != 2)) {
            return argumentError(err, args[0] + " requires exactly one file path.");
        }
        Map<String, String> options = parseOptions(args, 2, "--db", "--run-id", "--commit", "--environment");
        Path database = importDatabase(options);
        if (database == null && !options.isEmpty()) {
            return argumentError(err, "import-junit metadata options require --db.");
        }
        String path = args[1];
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

        if (database != null) {
            SaveResult result = new SqliteRunRepository(database).saveReport(runId(options),
                    RunMetadata.supplied(options.get("--commit"), options.get("--environment")), report);
            printSaveResult(result, out);
        }
        printReport(report, out);
        return 0;
    }

    private static void printReport(JUnitReport report, PrintStream out) {
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
    }

    private static int listRuns(String[] args, PrintStream out) throws SQLException {
        Map<String, String> options = parseOptions(args, 1, "--db");
        List<RunSummary> runs = new SqliteRunRepository(requiredDatabase(args[0], options)).listRuns();
        out.println("Runs: " + runs.size());
        for (RunSummary run : runs) {
            out.println("Run ID: " + printableLogText(run.id()) + " | First imported: " + run.firstImportedAt());
            out.println("  Commit: " + displayMetadata(run.commit()) + " | Environment: " + displayMetadata(run.environment()));
            out.printf("  JUnit reports: %d | JUnit executions: %d | Logs: %d | Log findings: %d | Log groups: %d%n",
                    run.junitSources(), run.junitExecutions(), run.logSources(), run.logFindings(), run.logGroups());
        }
        return 0;
    }

    private static int showRun(String[] args, PrintStream out) throws SQLException {
        if (args.length < 2 || missingValue(args[1])) {
            throw new UsageException("show-run requires a run ID.");
        }
        String id = args[1];
        Map<String, String> options = parseOptions(args, 2, "--db", "--group");
        SavedRun run = new SqliteRunRepository(requiredDatabase(args[0], options)).findRun(id)
                .orElseThrow(() -> new SQLException("Run '" + id + "' does not exist."));
        out.println("Run ID: " + printableLogText(run.id()));
        out.println("First imported: " + run.firstImportedAt());
        for (Field field : Field.values()) {
            out.println(field.label() + ": " + displayMetadata(run.metadata().value(field)));
        }
        out.printf("JUnit executions: %d | Log findings: %d%n",
                run.reports().stream().mapToLong(saved -> saved.report().testCases().size()).sum(),
                run.logs().stream().mapToLong(saved -> saved.findings().size()).sum());
        for (SavedRun.SavedReport saved : run.reports()) {
            out.println();
            out.println("Artifact imported: " + saved.importedAt());
            printReport(saved.report(), out);
        }
        String commit = run.metadata().value(Field.COMMIT).orElse(null);
        String environment = run.metadata().value(Field.ENVIRONMENT).orElse(null);
        for (SavedRun.SavedLog saved : run.logs()) {
            out.println();
            out.println("Artifact imported: " + saved.importedAt());
            if (options.containsKey("--group")) {
                printGroupedLog(saved.log(), saved.findings().size(), saved.groups(), commit, environment, out);
            } else {
                printLog(saved.log(), saved.findings(), commit, environment, out);
            }
        }
        return 0;
    }

    private static String displayMetadata(Optional<String> value) {
        return value.map(CiTriageCli::printableLogText).orElse("unknown");
    }

    private static void printSaveResult(SaveResult result, PrintStream out) {
        out.println("Run ID: " + printableLogText(result.runId()));
        out.println(result.inserted() ? "Storage: artifact saved." : "Storage: artifact already saved.");
    }

    private static Map<String, String> parseOptions(String[] args, int start, String... allowed) {
        Set<String> allowedOptions = Set.of(allowed);
        Map<String, String> options = new HashMap<>();
        for (int i = start; i < args.length;) {
            String option = args[i++];
            if (!allowedOptions.contains(option)) {
                throw new UsageException("import-junit".equals(args[0]) ? "import-junit requires exactly one file path."
                        : "Unknown " + args[0] + " option: '" + option + "'.");
            }
            String value = "";
            if (!"--group".equals(option)) {
                if (i == args.length || missingValue(args[i])) {
                    throw new UsageException(option + " requires a value.");
                }
                value = args[i++];
            }
            if (options.putIfAbsent(option, value) != null) {
                throw new UsageException(option + " may be specified only once.");
            }
        }
        return options;
    }

    private static Path importDatabase(Map<String, String> options) {
        if (options.containsKey("--db")) {
            return databasePath(options.get("--db"));
        }
        if (options.containsKey("--run-id")) {
            throw new UsageException("--run-id requires --db.");
        }
        return null;
    }

    private static Optional<String> runId(Map<String, String> options) {
        return Optional.ofNullable(options.get("--run-id"));
    }

    private static Path requiredDatabase(String operation, Map<String, String> options) {
        String value = options.get("--db");
        if (value == null) {
            throw new UsageException(operation + " requires --db <database-path>.");
        }
        return databasePath(value);
    }

    private static Path databasePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException exception) {
            throw new UsageException("Invalid database path: " + exception.getReason());
        }
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
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
