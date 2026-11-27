package org.citriage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;

import org.citriage.comparison.PriorityService;
import org.citriage.comparison.RunComparison;
import org.citriage.github.GitHubImporter;
import org.citriage.log.BuildLog;
import org.citriage.log.FailureGroup;
import org.citriage.log.LogClassifier;
import org.citriage.log.LogEvidence;
import org.citriage.log.LogFinding;
import org.citriage.log.LogGrouper;
import org.citriage.log.LogLine;
import org.citriage.log.LogReader;
import org.citriage.report.JUnitReport;
import org.citriage.report.JUnitXmlParser;
import org.citriage.report.OutcomeDetail;
import org.citriage.report.ReportParseException;
import org.citriage.report.TestCaseResult;
import org.citriage.report.TestOutcome;
import org.citriage.storage.GitHubOrigin;
import org.citriage.storage.RunFacts;
import org.citriage.storage.RunMetadata;
import org.citriage.storage.RunMetadata.Field;
import org.citriage.storage.RunMetadataFile;
import org.citriage.storage.SavedRun;
import org.citriage.storage.SqliteRunRepository;
import org.citriage.storage.SqliteRunRepository.RunSummary;
import org.citriage.storage.SqliteRunRepository.SaveResult;

import static org.citriage.CliDisplay.ioReason;
import static org.citriage.CliDisplay.location;
import static org.citriage.CliDisplay.redactHomes;
import static org.citriage.CliDisplay.sourceName;
import static org.citriage.CliDisplay.text;

/** The command-line entry point for CI evidence. */
public final class CiTriageCli {
    private static final String HELP = """
            CI Failure Triage
            Help developers answer: Why did my tests fail, and what should I investigate first?

            Import a local JUnit XML report or inspect failure diagnostics in a local UTF-8 log.
            Import a GitHub Actions evidence bundle for one completed job.
            Save evidence to SQLite, reopen it by run ID, and compare compatible runs.

            Usage: java -jar target/ci-failure-triage.jar import-junit <report-path>
                         [--db <database-path> [--run-id <id>] [--metadata <path>] [--commit <sha>] [--environment <label>]]
                   java -jar target/ci-failure-triage.jar import-log <log-path> [--commit <sha>] [--environment <label>] [--group]
                         [--db <database-path> [--run-id <id>] [--metadata <path>]]
                   java -jar target/ci-failure-triage.jar import-github OWNER/REPO --run-id <id> --db <database-path>
                         [--attempt <number>] [--job-id <id>]
                   java -jar target/ci-failure-triage.jar runs --db <database-path>
                   java -jar target/ci-failure-triage.jar show-run <id> --db <database-path> [--group]
                   java -jar target/ci-failure-triage.jar compare-runs <current-id> --db <database-path> [--baseline <baseline-id>]
                   java -jar target/ci-failure-triage.jar [--help | --version | --report <path>]

            Arguments (choose one operation):
              import-junit <report-path>  Import one local JUnit XML report.
              import-log <log-path>       Import one local UTF-8 log.
              import-github OWNER/REPO    Save a GitHub Actions job bundle using GITHUB_TOKEN.
              runs                       List saved runs.
              show-run <id>               Show saved results and evidence for a run.
              compare-runs <current-id>   Compare with a compatible earlier run and prioritize investigation.
              --help                      Show this help message.
              --version                   Show the application version.
              --report <path>             Compatibility alias for import-junit.

            Supply each import option at most once after the source path.
            --db saves a local import. Omit --run-id on local imports to create a fresh run ID.
            import-github requires the external --run-id and uses the latest attempt unless --attempt is supplied.
            --metadata <path> reads explicit execution metadata for a saved import.
            Use --group to group similar diagnostics and show every member's evidence.
            Omitted commit and environment values are displayed as unknown.
            Quote paths and environment labels containing spaces.
            With no arguments, show help.
            Exit codes: 0 = success, 1 = API, file, parsing, database, or missing-run error, 2 = invalid arguments.
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
                    case "import-github": return importGitHub(args, out);
                    case "runs": return listRuns(args, out);
                    case "show-run": return showRun(args, out);
                    case "compare-runs": return compareRuns(args, out);
                    default: break;
                }
            } catch (UsageException exception) {
                return argumentError(err, exception.getMessage());
            } catch (IOException exception) {
                err.println("Error: " + text(exception.getMessage()));
                return 1;
            } catch (SQLException exception) {
                err.println("Error: Database operation failed: " + text(exception.getMessage()));
                return 1;
            }
        }

        // Validate every argument before producing successful output.
        for (String argument : args) {
            if (!"--help".equals(argument) && !"--version".equals(argument)
                    && !"--report".equals(argument) && !"import-junit".equals(argument)
                    && !"import-log".equals(argument) && !"runs".equals(argument) && !"show-run".equals(argument)
                    && !"compare-runs".equals(argument) && !"import-github".equals(argument)) {
                return argumentError(err, "Unknown argument: '" + argument + "'.");
            }
        }

        if (args.length > 1) {
            return argumentError(err,
                    "Choose only one argument: --help, --version, import-junit, --report, import-log, import-github, runs, show-run, or compare-runs.");
        }

        if (args.length == 0 || "--help".equals(args[0])) {
            out.print(HELP);
        } else {
            out.println("CI Failure Triage " + applicationVersion());
        }
        return 0;
    }

    private static int importLog(String[] args, PrintStream out, PrintStream err) throws SQLException, IOException {
        if (args.length < 2 || missingValue(args[1])) {
            return argumentError(err, "import-log requires a log file path.");
        }
        Map<String, String> options = parseOptions(args, 2, "--commit", "--environment", "--group", "--db", "--run-id",
                "--metadata");
        Path database = importDatabase(options);
        RunMetadata metadata = importMetadata(options);
        String commit = metadata.value(Field.COMMIT).orElse(null);
        String environment = metadata.value(Field.ENVIRONMENT).orElse(null);
        boolean grouped = options.containsKey("--group");

        String path = args[1];
        BuildLog log;
        try {
            log = new LogReader().read(Path.of(path));
        } catch (InvalidPathException exception) {
            return argumentError(err, "Invalid log path: " + exception.getReason());
        } catch (CharacterCodingException exception) {
            err.println("Error: Cannot decode log '" + sourceName(path) + "': expected valid UTF-8.");
            return 1;
        } catch (IOException exception) {
            err.println("Error: Cannot read log '" + sourceName(path) + "': " + text(ioReason(exception)));
            return 1;
        }

        List<LogFinding> findings = new LogClassifier().classify(log);
        List<FailureGroup> groups = grouped || database != null ? new LogGrouper().group(findings) : List.of();
        if (database != null) {
            SaveResult result = new SqliteRunRepository(database).saveLog(runId(options),
                    metadata, log, findings, groups);
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
            finding.testIdentity().ifPresent(test -> out.println("  Test: " + text(test.displayName())));
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
                out.println("    " + text(line));
            }
            for (LogFinding member : group.members()) {
                String testName = member.testIdentity().map(test -> text(test.displayName()))
                        .orElse("unknown");
                out.println("  Test: " + testName + " [" + member.ruleId() + "]");
                printEvidence(member.evidence(), out);
            }
        }
    }

    private static void printLogSummary(BuildLog log, int findingCount, String commit, String environment,
                                        PrintStream out) {
        out.println("Log: " + sourceName(log.source().name()));
        out.println("SHA-256: " + log.source().sha256());
        out.println("Commit: " + (commit == null ? "unknown" : text(commit)));
        out.println("Environment: " + (environment == null ? "unknown" : text(environment)));
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

    static void printEvidence(LogEvidence evidence, PrintStream out) {
        String range = evidence.firstLine() == evidence.lastLine() ? Integer.toString(evidence.firstLine())
                : evidence.firstLine() + "-" + evidence.lastLine();
        out.println("  Evidence: " + sourceName(evidence.source().name()) + ":" + range);
        for (LogLine line : evidence.lines()) {
            out.println("    " + line.number() + " | " + text(line.text()));
        }
    }

    private static boolean missingValue(String value) {
        return value.isBlank() || value.startsWith("--");
    }

    private static int importReport(String[] args, PrintStream out, PrintStream err) throws SQLException, IOException {
        if (args.length < 2 || missingValue(args[1]) || ("--report".equals(args[0]) && args.length != 2)) {
            return argumentError(err, args[0] + " requires exactly one file path.");
        }
        Map<String, String> options = parseOptions(args, 2, "--db", "--run-id", "--commit", "--environment", "--metadata");
        Path database = importDatabase(options);
        if (database == null && !options.isEmpty()) {
            return argumentError(err, "import-junit metadata options require --db.");
        }
        RunMetadata metadata = importMetadata(options);
        String path = args[1];
        JUnitReport report;
        try {
            report = new JUnitXmlParser().parse(Path.of(path));
        } catch (InvalidPathException exception) {
            return argumentError(err, "Invalid report path: " + exception.getReason());
        } catch (IOException exception) {
            err.println("Error: Cannot read report '" + sourceName(path) + "': " + text(ioReason(exception)));
            return 1;
        } catch (ReportParseException exception) {
            String source = exception.location().sourceName();
            err.println("Error: " + text(exception.getMessage().replace(source, sourceName(source))));
            return 1;
        }

        if (database != null) {
            SaveResult result = new SqliteRunRepository(database).saveReport(runId(options), metadata, report);
            printSaveResult(result, out);
        }
        printReport(report, out);
        return 0;
    }

    private static void printReport(JUnitReport report, PrintStream out) {
        out.println("Report: " + sourceName(report.source().name()));
        out.println("SHA-256: " + report.source().sha256());
        out.printf("Tests: %d | passed=%d failed=%d errors=%d skipped=%d%n", report.testCases().size(),
                report.count(TestOutcome.PASSED), report.count(TestOutcome.FAILED),
                report.count(TestOutcome.ERROR), report.count(TestOutcome.SKIPPED));
        for (TestCaseResult test : report.testCases()) {
            out.println(test.outcome() + " " + text(test.identity().displayName()));
            test.durationSeconds().ifPresent(seconds -> out.println("  Duration (seconds): " + seconds));
            for (OutcomeDetail detail : test.details()) {
                out.println("  " + detail.outcome() + ": " + redactHomes(detail.message().orElse("(no message supplied)")));
                detail.type().ifPresent(type -> out.println("  Type: " + text(type)));
                out.println("  XML diagnostic location: " + location(detail.location()));
                if (!detail.text().isEmpty()) {
                    out.print(redactHomes(detail.text()));
                    if (!detail.text().endsWith("\n") && !detail.text().endsWith("\r")) {
                        out.println();
                    }
                }
            }
        }
    }

    private static int importGitHub(String[] args, PrintStream out) throws IOException, SQLException {
        if (args.length < 2 || missingValue(args[1])) {
            throw new UsageException("import-github requires a repository in OWNER/REPO form.");
        }
        Map<String, String> options = parseOptions(args, 2, "--db", "--run-id", "--attempt", "--job-id");
        Path database = requiredDatabase(args[0], options);
        GitHubImporter.Request request;
        try {
            long runId = positiveId(options.get("--run-id"), "--run-id");
            String attempt = options.get("--attempt");
            String job = options.get("--job-id");
            request = new GitHubImporter.Request(args[1], runId,
                    attempt == null ? OptionalInt.empty() : OptionalInt.of(Math.toIntExact(positiveId(attempt, "--attempt"))),
                    job == null ? OptionalLong.empty() : OptionalLong.of(positiveId(job, "--job-id")));
        } catch (ArithmeticException exception) {
            throw new UsageException("--attempt requires a positive 32-bit integer.");
        } catch (IllegalArgumentException exception) {
            throw new UsageException(exception.getMessage());
        }
        GitHubImporter.Result result = GitHubImporter.importBundle(request, database, System.getenv("GITHUB_TOKEN"));
        out.println("Run ID: " + result.saved().runId());
        printGitHubOrigin(result.origin(), out);
        out.println(result.saved().inserted() ? "Storage: bundle saved." : "Storage: bundle already saved.");
        out.println("Job conclusion: " + displayMetadata(result.metadata().value(Field.CONCLUSION)));
        out.println("Tested commit: " + displayMetadata(result.metadata().value(Field.COMMIT)));
        printGitHubEvidenceSummary(result.reports(), result.findings(), result.groups(), out);
        return 0;
    }

    private static long positiveId(String value, String option) {
        if (value != null && value.matches("[0-9]{1,19}")) {
            try {
                long id = Long.parseLong(value);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // Return a usage error without echoing the argument.
            }
        }
        throw new UsageException("import-github requires a positive integer for " + option + ".");
    }

    private static void printGitHubOrigin(GitHubOrigin origin, PrintStream out) {
        out.println("GitHub repository: " + text(origin.repository()) + " (ID " + origin.repositoryId() + ")");
        out.printf("GitHub run ID: %d | Attempt: %d | Job ID: %d | Artifact ID: %d%n",
                origin.workflowRunId(), origin.attempt(), origin.jobId(), origin.artifactId());
        out.println("Triggering head commit: " + displayMetadata(origin.triggeringHeadCommit()));
    }

    private static void printGitHubEvidenceSummary(int reports, Optional<Integer> findings, Optional<Integer> groups,
                                                    PrintStream out) {
        out.println("JUnit reports in bundle: " + reports);
        if (reports == 0) {
            out.println("JUnit evidence unavailable: no JUnit report was included.");
        }
        if (findings.isPresent()) {
            out.printf("Build log findings: %d | Groups: %d%n", findings.get(), groups.orElseThrow());
        } else {
            out.println("Build log evidence unavailable: no build log was included.");
        }
    }

    private static int listRuns(String[] args, PrintStream out) throws SQLException {
        Map<String, String> options = parseOptions(args, 1, "--db");
        List<RunSummary> runs = new SqliteRunRepository(requiredDatabase(args[0], options)).listRuns();
        out.println("Runs: " + runs.size());
        for (RunSummary run : runs) {
            out.println("Run ID: " + text(run.id()) + " | First imported: " + run.firstImportedAt());
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
        out.println("Run ID: " + text(run.id()));
        out.println("First imported: " + run.firstImportedAt());
        run.githubOrigin().ifPresent(origin -> {
            printGitHubOrigin(origin, out);
            out.println("Manifest SHA-256: " + origin.manifest().sha256());
            if (run.reports().isEmpty()) {
                out.println("JUnit evidence unavailable: no JUnit report was included.");
            }
        });
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
        return value.map(CliDisplay::text).orElse("unknown");
    }

    private static int compareRuns(String[] args, PrintStream out) throws SQLException {
        if (args.length < 2 || missingValue(args[1])) {
            throw new UsageException("compare-runs requires a current run ID.");
        }
        String currentId = args[1];
        Map<String, String> options = parseOptions(args, 2, "--db", "--baseline");
        var repository = new SqliteRunRepository(requiredDatabase(args[0], options));
        Optional<String> requested = Optional.ofNullable(options.get("--baseline"));
        RunComparison service = new RunComparison();
        ComparisonOutput.Report report = repository.readSnapshot(snapshot -> {
            SavedRun current = snapshot.findRun(currentId)
                    .orElseThrow(() -> new SQLException("Run '" + currentId + "' does not exist."));
            List<RunFacts> candidates = snapshot.baselineCandidates(currentId, requested);
            if (requested.isPresent() && candidates.isEmpty()) {
                throw new SQLException("Baseline run '" + requested.get() + "' does not exist.");
            }
            RunComparison.Selection selection = service.select(current, candidates);
            Optional<SavedRun> baseline = Optional.empty();
            if (selection.baseline().isPresent()) {
                String baselineId = selection.baseline().get().run().id();
                baseline = Optional.of(snapshot.findRun(baselineId)
                        .orElseThrow(() -> new SQLException("Baseline run '" + baselineId + "' does not exist.")));
            }
            RunComparison.TestComparison tests = service.compareTests(current, baseline);
            return new ComparisonOutput.Report(current.id(), current.metadata().instant(Field.EXECUTED_AT), requested,
                    selection, tests, new PriorityService().prioritize(current, tests.failures()));
        });
        ComparisonOutput.print(report, out);
        return 0;
    }

    private static void printSaveResult(SaveResult result, PrintStream out) {
        out.println("Run ID: " + text(result.runId()));
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
        if (options.containsKey("--metadata")) {
            throw new UsageException("--metadata requires --db.");
        }
        return null;
    }

    private static RunMetadata importMetadata(Map<String, String> options) throws IOException {
        RunMetadata flags = RunMetadata.supplied(options.get("--commit"), options.get("--environment"));
        String value = options.get("--metadata");
        if (value == null) {
            return flags;
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException exception) {
            throw new UsageException("Invalid metadata path: " + exception.getReason());
        }
        try {
            return new RunMetadataFile().read(path).merge(flags);
        } catch (CharacterCodingException exception) {
            throw new IOException("Cannot read metadata '" + sourceName(value) + "': expected valid UTF-8.", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IOException("Cannot use metadata '" + sourceName(value) + "': " + ioReason(exception), exception);
        }
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
        err.println("Error: " + text(message));
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
