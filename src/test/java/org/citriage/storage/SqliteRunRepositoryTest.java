package org.citriage.storage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.citriage.log.BuildLog;
import org.citriage.log.FailureGroup;
import org.citriage.log.LogClassifier;
import org.citriage.log.LogFinding;
import org.citriage.log.LogGrouper;
import org.citriage.log.LogReader;
import org.citriage.report.JUnitReport;
import org.citriage.report.JUnitXmlParser;
import org.citriage.report.ReportSource;
import org.citriage.report.TestOutcome;
import org.citriage.storage.RunMetadata.Field;
import org.citriage.storage.SavedRun.SavedLog;
import org.citriage.storage.SqliteRunRepository.RunSummary;
import org.citriage.storage.SqliteRunRepository.SaveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRunRepositoryTest {
    private static final Optional<String> RUN_ID = Optional.of("synthetic 'ci' run");
    private static final RunMetadata UNKNOWN = new RunMetadata(Map.of());

    @TempDir
    Path directory;

    @Test
    void reopensCompleteXmlAndGroupedLogAfterOriginalCopiesAreRemoved() throws Exception {
        Path database = directory.resolve("triage #1 100%.sqlite");
        Path reportFile = directory.resolve("report.xml");
        String xml = Files.readString(fixture("mixed-results.xml"))
                .replace("0.1250", "0.1250000000000000000000000000012300");
        JUnitReport report = new JUnitXmlParser().parse(Files.writeString(reportFile, xml));
        Path logFile = directory.resolve("fixture.log");
        String text = "\uFEFF" + Files.readString(fixture("logs/grouping/grouped-failures.log"))
                .replace("\r\n", "\n").stripTrailing().replace("\n", "\r\n");
        BuildLog log = new LogReader().read(Files.writeString(logFile, text));
        List<LogFinding> findings = new LogClassifier().classify(log);
        List<FailureGroup> groups = new LogGrouper().group(findings);

        SqliteRunRepository writer = new SqliteRunRepository(database);
        RunMetadata originalMetadata = new RunMetadata(Map.of(Field.COMMIT, "abc1234", Field.REPOSITORY, "sample/repo",
                Field.EXECUTED_AT, "2021-01-02T03:04:05Z", Field.TOOLCHAIN, "Java 17"));
        assertTrue(writer.saveReport(RUN_ID, originalMetadata, report).inserted());
        assertTrue(writer.saveLog(RUN_ID, RunMetadata.supplied(null, "sample CI"), log, findings, groups).inserted());
        Files.move(logFile, directory.resolve("moved.log"));
        Files.delete(reportFile);

        SqliteRunRepository reader = new SqliteRunRepository(database);
        SavedRun loaded = reader.findRun(RUN_ID.orElseThrow()).orElseThrow();
        JUnitReport saved = loaded.reports().get(0).report();
        assertSource(report.source(), saved.source());
        assertEquals(report.testCases(), saved.testCases());
        assertTrue(saved.testCases().stream().anyMatch(test -> test.durationSeconds()
                .equals(Optional.of(new BigDecimal("0.1250000000000000000000000000012300")))));
        assertEquals(2, saved.count(TestOutcome.PASSED));
        assertEquals(1, saved.count(TestOutcome.FAILED));
        assertEquals(1, saved.count(TestOutcome.ERROR));
        assertEquals(1, saved.count(TestOutcome.SKIPPED));
        assertEquals(activeMetadata(originalMetadata).values(), loaded.metadata().values());
        assertTrue(loaded.metadata().value(Field.OS).isEmpty());
        assertNotEquals(originalMetadata.value(Field.EXECUTED_AT).orElseThrow(), loaded.firstImportedAt().toString());
        assertEquals(loaded.firstImportedAt(), loaded.reports().get(0).importedAt());

        SavedLog storedLog = loaded.logs().get(0);
        assertSource(log.source(), storedLog.log().source());
        assertEquals(text, storedLog.log().text());
        assertEquals(log.lines(), storedLog.log().lines());
        assertEquals(findings.size(), storedLog.findings().size());
        for (int i = 0; i < findings.size(); i++) {
            LogFinding original = findings.get(i);
            LogFinding reopened = storedLog.findings().get(i);
            assertEquals(original.category(), reopened.category());
            assertEquals(original.ruleId(), reopened.ruleId());
            assertEquals(original.explanation(), reopened.explanation());
            assertEquals(original.testIdentity(), reopened.testIdentity());
            assertEquals(original.evidence().lines(), reopened.evidence().lines());
            assertEquals(original.evidence().originalText(), reopened.evidence().originalText());
            assertSame(storedLog.log().source(), reopened.evidence().source());
        }
        assertEquals(groups.size(), storedLog.groups().size());
        for (int i = 0; i < groups.size(); i++) {
            FailureGroup original = groups.get(i);
            FailureGroup reopened = storedLog.groups().get(i);
            assertEquals(original.id(), reopened.id());
            assertEquals(original.category(), reopened.category());
            assertEquals(original.signature(), reopened.signature());
            assertEquals(original.occurrenceCount(), reopened.occurrenceCount());
            assertEquals(original.distinctKnownTestCount(), reopened.distinctKnownTestCount());
            assertEquals(original.unknownTestOccurrenceCount(), reopened.unknownTestOccurrenceCount());
            assertEquals(original.members().stream().map(findings::indexOf).toList(),
                    reopened.members().stream().map(storedLog.findings()::indexOf).toList());
        }
        RunSummary summary = reader.listRuns().get(0);
        assertEquals(RUN_ID.orElseThrow(), summary.id());
        assertEquals(1, summary.junitSources());
        assertEquals(1, summary.logSources());
        assertEquals(5, summary.junitExecutions());
        assertEquals(6, summary.logFindings());
        assertEquals(3, summary.logGroups());
    }

    @Test
    void duplicateKeyUsesRunKindAndHashAndKeepsFirstSourcePath() throws Exception {
        SqliteRunRepository repository = repository();
        Path original = fixture("mixed-results.xml");
        JUnitReport report = new JUnitXmlParser().parse(original);
        Path copy = Files.copy(original, directory.resolve("duplicate.xml"));
        JUnitReport duplicate = new JUnitXmlParser().parse(copy);

        assertTrue(repository.saveReport(RUN_ID, UNKNOWN, report).inserted());
        assertFalse(repository.saveReport(RUN_ID, RunMetadata.supplied("abc1234", null), duplicate).inserted());
        assertTrue(repository.saveReport(Optional.of("different execution"), UNKNOWN, duplicate).inserted());
        BuildLog sameBytes = new LogReader().read(original);
        repository.saveLog(RUN_ID, UNKNOWN, sameBytes, List.of(), List.of());

        SavedRun firstRun = repository.findRun(RUN_ID.orElseThrow()).orElseThrow();
        assertEquals(1, firstRun.reports().size());
        assertSource(report.source(), firstRun.reports().get(0).report().source());
        assertEquals(Optional.of("abc1234"), firstRun.metadata().value(Field.COMMIT));
        assertEquals(report.source().sha256(), firstRun.logs().get(0).log().source().sha256());
        SavedRun secondRun = repository.findRun("different execution").orElseThrow();
        assertSource(duplicate.source(), secondRun.reports().get(0).report().source());
        assertEquals(2, repository.listRuns().size());
    }

    @Test
    void displayIdsCanRepeatAcrossSourcesInTheSameRun() throws Exception {
        SqliteRunRepository repository = repository();
        Path fixture = fixture("logs/grouping/grouped-failures.log");
        String original = Files.readString(fixture);
        BuildLog first = new LogReader().read(fixture);
        BuildLog second = new LogReader().read(Files.writeString(directory.resolve("second.log"),
                original + "\n[INFO] End of synthetic log\n"));
        for (BuildLog log : List.of(first, second)) {
            List<LogFinding> findings = new LogClassifier().classify(log);
            assertTrue(repository.saveLog(RUN_ID, UNKNOWN, log, findings, new LogGrouper().group(findings)).inserted());
        }

        List<SavedLog> logs = repository.findRun(RUN_ID.orElseThrow()).orElseThrow().logs();
        assertEquals(2, logs.size());
        assertEquals(logs.get(0).groups().stream().map(FailureGroup::id).toList(),
                logs.get(1).groups().stream().map(FailureGroup::id).toList());
        assertEquals(12, repository.listRuns().get(0).logFindings());
    }

    @Test
    void conflictingKnownMetadataRollsBackAnEarlierFieldUpdate() throws Exception {
        SqliteRunRepository repository = repository();
        JUnitReport first = report();
        repository.saveReport(RUN_ID, RunMetadata.supplied(null, "sample CI"), first);
        JUnitReport other = new JUnitXmlParser().parse(fixture("passing.xml"));

        SQLException error = assertThrows(SQLException.class,
                () -> repository.saveReport(RUN_ID, RunMetadata.supplied("would be new", "different CI"), other));
        assertTrue(error.getMessage().contains("Conflicting Environment metadata"));
        SavedRun run = repository.findRun(RUN_ID.orElseThrow()).orElseThrow();
        assertEquals(Optional.empty(), run.metadata().value(Field.COMMIT));
        assertEquals(Optional.of("sample CI"), run.metadata().value(Field.ENVIRONMENT));
        assertEquals(1, run.reports().size());
        assertSource(first.source(), run.reports().get(0).report().source());
    }

    @Test
    void databaseErrorWritingOutcomeDetailsRollsBackRunAndAllArtifactRows() throws Exception {
        SqliteRunRepository repository = repository();
        repository.saveReport(RUN_ID, UNKNOWN, report());
        execute("""
                CREATE TRIGGER reject_test_details BEFORE INSERT ON outcome_details BEGIN
                    SELECT RAISE(ABORT, 'simulated detail write failure');
                END
                """);

        SQLException error = assertThrows(SQLException.class, () -> repository.saveReport(Optional.of("rolled back"),
                RunMetadata.supplied("not committed", null), report()));
        assertTrue(error.getMessage().contains("simulated detail write failure"));
        assertTrue(repository.findRun("rolled back").isEmpty());
        assertEquals(1, repository.listRuns().size());
        assertEquals(5, repository.listRuns().get(0).junitExecutions());
    }

    @Test
    void freshSchemaHasForeignKeysOnBothReadAndWriteConnections() throws Exception {
        SqliteDatabase database = new SqliteDatabase(databasePath());
        database.transaction(true, connection -> {
            assertEquals(2, pragma(connection, "PRAGMA user_version"));
            assertEquals(1, pragma(connection, "PRAGMA foreign_keys"));
            try (var insert = connection.prepareStatement("""
                    INSERT INTO run_metadata (run_id, field, value) VALUES ('missing', 'COMMIT', 'abc1234')
                    """)) {
                assertTrue(assertThrows(SQLException.class, insert::executeUpdate).getMessage().contains("FOREIGN KEY"));
            }
            return null;
        });
        database.transaction(false, connection -> {
            assertEquals(2, pragma(connection, "PRAGMA user_version"));
            assertEquals(1, pragma(connection, "PRAGMA foreign_keys"));
            assertTrue(connection.isReadOnly());
            return null;
        });
    }

    @Test
    void failedInitialTransactionCanRetryTheMigration() throws Exception {
        SqliteDatabase database = new SqliteDatabase(databasePath());
        assertThrows(SQLException.class, () -> database.transaction(true, connection -> {
            assertEquals(2, pragma(connection, "PRAGMA user_version"));
            throw new SQLException("simulated failure after migration");
        }));
        assertTrue(repository().saveReport(RUN_ID, UNKNOWN, report()).inserted());
        assertEquals(1, repository().listRuns().size());
    }

    @Test
    void newerSchemaIsRejectedByBothQueryAndImport() throws Exception {
        SqliteRunRepository repository = repository();
        JUnitReport report = report();
        repository.saveReport(RUN_ID, UNKNOWN, report);
        execute("PRAGMA user_version = 42");

        assertTrue(assertThrows(SQLException.class, repository::listRuns).getMessage().contains("schema version 42"));
        assertTrue(assertThrows(SQLException.class, () -> repository.saveReport(RUN_ID, UNKNOWN, report))
                .getMessage().contains("schema version 42"));
    }

    @Test
    void readsVersionOneAndMigratesExistingMetadataAndObservationsOnWrite() throws Exception {
        JUnitReport report = report();
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath().toUri(), config.toProperties());
             var statement = connection.createStatement();
             var migration = getClass().getResourceAsStream("/db/migrations/V001__create_storage.sql")) {
            statement.executeUpdate(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
            statement.executeUpdate("""
                    PRAGMA application_id = 0x43495452;
                    PRAGMA user_version = 1;
                    INSERT INTO runs VALUES ('legacy', 0);
                    INSERT INTO run_metadata VALUES ('legacy', 'COMMIT', 'abc1234');
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO sources (source_id, run_id, kind, original_path, sha256, original_bytes, imported_at_ms)
                    VALUES (1, 'legacy', 'JUNIT_XML', ?, ?, ?, 0)
                    """)) {
                insert.setString(1, report.source().name());
                insert.setString(2, report.source().sha256());
                insert.setBytes(3, report.source().bytes());
                insert.executeUpdate();
            }
            StoredJUnit.write(connection, 1, report);
        }

        byte[] before = Files.readAllBytes(databasePath());
        SavedRun old = repository().findRun("legacy").orElseThrow();
        assertArrayEquals(before, Files.readAllBytes(databasePath()));
        assertEquals(Optional.of("abc1234"), old.metadata().value(Field.COMMIT));
        assertTrue(old.metadata().value(Field.CONCLUSION).isEmpty());
        assertEquals(report.testCases(), old.reports().get(0).report().testCases());

        assertThrows(SQLException.class, () -> repository().saveReport(Optional.of("legacy"),
                RunMetadata.supplied("conflicting-commit", null), report));
        int stillVersionOne = new SqliteDatabase(databasePath()).transaction(false,
                connection -> pragma(connection, "PRAGMA user_version"));
        assertEquals(1, stillVersionOne);

        RunMetadata completion = new RunMetadata(Map.of(Field.CONCLUSION, "SUCCESS", Field.COMPLETED_AT, "2025-01-02T09:00:00Z"));
        assertFalse(repository().saveReport(Optional.of("legacy"), completion, report).inserted());
        SavedRun migrated = repository().findRun("legacy").orElseThrow();
        assertEquals(old.firstImportedAt(), migrated.firstImportedAt());
        assertEquals(old.metadata().merge(completion), migrated.metadata());
        assertEquals(1, migrated.reports().size());
        assertSource(report.source(), migrated.reports().get(0).report().source());
        assertEquals(report.testCases(), migrated.reports().get(0).report().testCases());
        int version = new SqliteDatabase(databasePath()).transaction(false, connection -> pragma(connection, "PRAGMA user_version"));
        assertEquals(2, version);
    }

    @Test
    void concurrentFirstImportsOfTheSameArtifactInsertOnlyOnce() throws Exception {
        SqliteRunRepository repository = repository();
        JUnitReport report = report();
        CountDownLatch start = new CountDownLatch(1);
        Callable<SaveResult> task = () -> {
            start.await();
            return repository.saveReport(RUN_ID, UNKNOWN, report);
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS).inserted(), second.get(15, TimeUnit.SECONDS).inserted());
            assertEquals(1, repository.listRuns().get(0).junitSources());
        } finally {
            pool.shutdownNow();
        }
    }

    private static RunMetadata activeMetadata(RunMetadata original) {
        var values = new java.util.EnumMap<Field, String>(original.values());
        values.put(Field.ENVIRONMENT, "sample CI");
        return new RunMetadata(values);
    }

    private static int pragma(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        new SqliteDatabase(databasePath()).transaction(true, connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                statement.execute();
            }
            return null;
        });
    }

    private SqliteRunRepository repository() {
        return new SqliteRunRepository(databasePath());
    }

    private Path databasePath() {
        return directory.resolve("triage.sqlite");
    }

    private static JUnitReport report() throws Exception {
        return new JUnitXmlParser().parse(fixture("mixed-results.xml"));
    }

    private static Path fixture(String filename) throws Exception {
        return Path.of(SqliteRunRepositoryTest.class.getResource("/fixtures/" + filename).toURI());
    }

    private static void assertSource(ReportSource expected, ReportSource actual) {
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.sha256(), actual.sha256());
        assertArrayEquals(expected.bytes(), actual.bytes());
    }
}
