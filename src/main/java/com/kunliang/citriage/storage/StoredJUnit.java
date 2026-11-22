package com.kunliang.citriage.storage;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kunliang.citriage.report.JUnitReport;
import com.kunliang.citriage.report.OutcomeDetail;
import com.kunliang.citriage.report.ReportSource;
import com.kunliang.citriage.report.SourceLocation;
import com.kunliang.citriage.report.TestCaseResult;
import com.kunliang.citriage.report.TestIdentity;
import com.kunliang.citriage.report.TestOutcome;

/** Persists the parsed XML view separately from the original source bytes. */
final class StoredJUnit {
    private StoredJUnit() {
    }

    static void write(Connection connection, long sourceId, JUnitReport report) throws SQLException {
        try (PreparedStatement execution = connection.prepareStatement("""
                     INSERT INTO test_executions
                         (source_id, test_index, class_name, test_name, duration_seconds, outcome, xml_line, xml_column)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement suite = connection.prepareStatement("""
                     INSERT INTO suite_path_elements (source_id, test_index, depth, suite_name) VALUES (?, ?, ?, ?)
                     """);
             PreparedStatement detail = connection.prepareStatement("""
                     INSERT INTO outcome_details
                         (source_id, test_index, detail_index, outcome, type, message, body, xml_line, xml_column)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            execution.setLong(1, sourceId);
            suite.setLong(1, sourceId);
            detail.setLong(1, sourceId);
            for (int i = 0; i < report.testCases().size(); i++) {
                TestCaseResult test = report.testCases().get(i);
                execution.setInt(2, i);
                execution.setString(3, test.className().orElse(null));
                execution.setString(4, test.name());
                execution.setString(5, test.durationSeconds().map(BigDecimal::toString).orElse(null));
                execution.setString(6, test.outcome().name());
                execution.setInt(7, test.location().line());
                execution.setInt(8, test.location().column());
                execution.executeUpdate();

                suite.setInt(2, i);
                for (int depth = 0; depth < test.suitePath().size(); depth++) {
                    suite.setInt(3, depth);
                    suite.setString(4, test.suitePath().get(depth));
                    suite.executeUpdate();
                }

                detail.setInt(2, i);
                for (int j = 0; j < test.details().size(); j++) {
                    OutcomeDetail marker = test.details().get(j);
                    detail.setInt(3, j);
                    detail.setString(4, marker.outcome().name());
                    detail.setString(5, marker.type().orElse(null));
                    detail.setString(6, marker.message().orElse(null));
                    detail.setString(7, marker.text());
                    detail.setInt(8, marker.location().line());
                    detail.setInt(9, marker.location().column());
                    detail.executeUpdate();
                }
            }
        }
    }

    static JUnitReport read(Connection connection, long sourceId, ReportSource source) throws SQLException {
        Map<Integer, List<String>> suites = readSuites(connection, sourceId);
        Map<Integer, List<OutcomeDetail>> details = readDetails(connection, sourceId, source);
        List<TestCaseResult> tests = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_index, class_name, test_name, duration_seconds, outcome, xml_line, xml_column
                FROM test_executions WHERE source_id = ? ORDER BY test_index
                """)) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int index = rows.getInt("test_index");
                    TestIdentity identity = new TestIdentity(Optional.ofNullable(rows.getString("class_name")),
                            rows.getString("test_name"));
                    Optional<BigDecimal> duration = Optional.ofNullable(rows.getString("duration_seconds"))
                            .map(BigDecimal::new);
                    TestCaseResult test = new TestCaseResult(suites.getOrDefault(index, List.of()), identity, duration,
                            details.getOrDefault(index, List.of()), location(rows, source));
                    if (!test.outcome().name().equals(rows.getString("outcome"))) {
                        throw new SQLException("Saved JUnit execution disagrees with its outcome details.");
                    }
                    tests.add(test);
                }
            }
        }
        return new JUnitReport(source, tests);
    }

    private static Map<Integer, List<String>> readSuites(Connection connection, long sourceId) throws SQLException {
        Map<Integer, List<String>> suites = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_index, suite_name FROM suite_path_elements WHERE source_id = ? ORDER BY test_index, depth
                """)) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    suites.computeIfAbsent(rows.getInt("test_index"), ignored -> new ArrayList<>())
                            .add(rows.getString("suite_name"));
                }
            }
        }
        return suites;
    }

    private static Map<Integer, List<OutcomeDetail>> readDetails(Connection connection, long sourceId,
                                                                  ReportSource source) throws SQLException {
        Map<Integer, List<OutcomeDetail>> details = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT test_index, outcome, type, message, body, xml_line, xml_column
                FROM outcome_details WHERE source_id = ? ORDER BY test_index, detail_index
                """)) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    OutcomeDetail detail = new OutcomeDetail(TestOutcome.valueOf(rows.getString("outcome")),
                            Optional.ofNullable(rows.getString("type")), Optional.ofNullable(rows.getString("message")),
                            rows.getString("body"), location(rows, source));
                    details.computeIfAbsent(rows.getInt("test_index"), ignored -> new ArrayList<>()).add(detail);
                }
            }
        }
        return details;
    }

    private static SourceLocation location(ResultSet rows, ReportSource source) throws SQLException {
        return new SourceLocation(source.name(), rows.getInt("xml_line"), rows.getInt("xml_column"));
    }
}
