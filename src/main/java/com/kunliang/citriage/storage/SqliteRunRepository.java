package com.kunliang.citriage.storage;

import java.nio.charset.CharacterCodingException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kunliang.citriage.log.BuildLog;
import com.kunliang.citriage.log.FailureGroup;
import com.kunliang.citriage.log.LogFinding;
import com.kunliang.citriage.log.LogReader;
import com.kunliang.citriage.report.JUnitReport;
import com.kunliang.citriage.report.ReportSource;
import com.kunliang.citriage.storage.RunMetadata.Field;
import com.kunliang.citriage.storage.SavedRun.SavedLog;
import com.kunliang.citriage.storage.SavedRun.SavedReport;

/** Stores and reopens runs without depending on the original files or running diagnostic rules. */
public final class SqliteRunRepository {
    private final SqliteDatabase database;

    public SqliteRunRepository(Path databasePath) {
        database = new SqliteDatabase(databasePath);
    }

    public SaveResult saveReport(Optional<String> runId, RunMetadata metadata, JUnitReport report) throws SQLException {
        return save(runId, metadata, Kind.JUNIT_XML, report.source(),
                (connection, sourceId) -> StoredJUnit.write(connection, sourceId, report));
    }

    public SaveResult saveLog(Optional<String> runId, RunMetadata metadata, BuildLog log, List<LogFinding> findings,
                              List<FailureGroup> groups) throws SQLException {
        return save(runId, metadata, Kind.LOG, log.source(),
                (connection, sourceId) -> StoredLog.write(connection, sourceId, log, findings, groups));
    }

    public List<RunSummary> listRuns() throws SQLException {
        return database.transaction(false, connection -> {
            List<RunSummary> runs = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT r.run_id, r.first_imported_at_ms, c.value AS commit_sha, e.value AS environment,
                        (SELECT count(*) FROM sources s WHERE s.run_id = r.run_id AND s.kind = 'JUNIT_XML') AS reports,
                        (SELECT count(*) FROM sources s WHERE s.run_id = r.run_id AND s.kind = 'LOG') AS logs,
                        (SELECT count(*) FROM sources s JOIN test_executions t USING (source_id)
                            WHERE s.run_id = r.run_id) AS executions,
                        (SELECT count(*) FROM sources s JOIN log_findings f USING (source_id)
                            WHERE s.run_id = r.run_id) AS findings,
                        (SELECT count(*) FROM sources s JOIN failure_groups g USING (source_id)
                            WHERE s.run_id = r.run_id) AS groups
                    FROM runs r
                    LEFT JOIN run_metadata c ON c.run_id = r.run_id AND c.field = 'COMMIT'
                    LEFT JOIN run_metadata e ON e.run_id = r.run_id AND e.field = 'ENVIRONMENT'
                    ORDER BY r.first_imported_at_ms, r.run_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    runs.add(new RunSummary(rows.getString("run_id"),
                            Instant.ofEpochMilli(rows.getLong("first_imported_at_ms")),
                            Optional.ofNullable(rows.getString("commit_sha")),
                            Optional.ofNullable(rows.getString("environment")),
                            rows.getLong("reports"), rows.getLong("logs"), rows.getLong("executions"),
                            rows.getLong("findings"), rows.getLong("groups")));
                }
            }
            return List.copyOf(runs);
        });
    }

    public Optional<SavedRun> findRun(String id) throws SQLException {
        return database.transaction(false, connection -> {
            Instant firstImportedAt;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT first_imported_at_ms FROM runs WHERE run_id = ?")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    firstImportedAt = Instant.ofEpochMilli(rows.getLong(1));
                }
            }
            return Optional.of(readRun(connection, id, firstImportedAt));
        });
    }

    private SaveResult save(Optional<String> requestedId, RunMetadata metadata, Kind kind, ReportSource source,
                            SourceWriter writer) throws SQLException {
        if (requestedId.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("A run ID must be nonblank.");
        }
        return database.transaction(true, connection -> {
            long importedAt = Instant.now().toEpochMilli();
            String runId = ensureRun(connection, requestedId, importedAt);
            storeMetadata(connection, runId, metadata);
            long sourceId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sources (run_id, kind, original_path, sha256, original_bytes, imported_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (run_id, kind, sha256) DO NOTHING RETURNING source_id
                    """)) {
                statement.setString(1, runId);
                statement.setString(2, kind.name());
                statement.setString(3, source.name());
                statement.setString(4, source.sha256());
                statement.setBytes(5, source.bytes());
                statement.setLong(6, importedAt);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return new SaveResult(runId, false);
                    }
                    sourceId = rows.getLong(1);
                }
            }
            writer.write(connection, sourceId);
            return new SaveResult(runId, true);
        });
    }

    private static String ensureRun(Connection connection, Optional<String> requestedId, long importedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runs (run_id, first_imported_at_ms) VALUES (?, ?) ON CONFLICT (run_id) DO NOTHING
                """)) {
            statement.setLong(2, importedAt);
            String id;
            int inserted;
            do {
                id = requestedId.orElseGet(() -> UUID.randomUUID().toString());
                statement.setString(1, id);
                inserted = statement.executeUpdate();
            } while (inserted == 0 && requestedId.isEmpty());
            return id;
        }
    }

    private static void storeMetadata(Connection connection, String runId, RunMetadata metadata) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO run_metadata (run_id, field, value) VALUES (?, ?, ?)
                ON CONFLICT (run_id, field) DO UPDATE SET value = excluded.value
                WHERE run_metadata.value = excluded.value
                """)) {
            statement.setString(1, runId);
            for (var entry : metadata.values().entrySet()) {
                statement.setString(2, entry.getKey().name());
                statement.setString(3, entry.getValue());
                if (statement.executeUpdate() == 0) {
                    throw new SQLException("Conflicting " + entry.getKey().label() + " metadata for run '" + runId + "'.");
                }
            }
        }
    }

    private static SavedRun readRun(Connection connection, String id, Instant firstImportedAt) throws SQLException {
        List<SavedReport> reports = new ArrayList<>();
        List<SavedLog> logs = new ArrayList<>();
        RunMetadata metadata = readMetadata(connection, id);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_id, kind, original_path, sha256, original_bytes, imported_at_ms
                FROM sources WHERE run_id = ? ORDER BY source_id
                """)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long sourceId = rows.getLong("source_id");
                    ReportSource source = new ReportSource(rows.getString("original_path"), rows.getBytes("original_bytes"));
                    if (!source.sha256().equals(rows.getString("sha256"))) {
                        throw new SQLException("Saved bytes do not match the SHA-256 for source '" + source.name() + "'.");
                    }
                    Instant importedAt = Instant.ofEpochMilli(rows.getLong("imported_at_ms"));
                    switch (Kind.valueOf(rows.getString("kind"))) {
                        case JUNIT_XML -> reports.add(new SavedReport(importedAt, StoredJUnit.read(connection, sourceId, source)));
                        case LOG -> logs.add(StoredLog.read(connection, sourceId, importedAt, new LogReader().decode(source)));
                    }
                }
            }
        } catch (CharacterCodingException | IllegalArgumentException exception) {
            throw new SQLException("Invalid saved observation for run '" + id + "'.", exception);
        }
        return new SavedRun(id, firstImportedAt, metadata, reports, logs);
    }

    private static RunMetadata readMetadata(Connection connection, String id) throws SQLException {
        var values = new EnumMap<Field, String>(Field.class);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT field, value FROM run_metadata WHERE run_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.put(Field.valueOf(rows.getString("field")), rows.getString("value"));
                }
            }
            return new RunMetadata(values);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Invalid saved metadata for run '" + id + "'.", exception);
        }
    }

    public record SaveResult(String runId, boolean inserted) {
    }

    public record RunSummary(String id, Instant firstImportedAt, Optional<String> commit, Optional<String> environment,
                             long junitSources, long logSources, long junitExecutions, long logFindings, long logGroups) {
    }

    private enum Kind { JUNIT_XML, LOG }

    @FunctionalInterface
    private interface SourceWriter {
        void write(Connection connection, long sourceId) throws SQLException;
    }
}
