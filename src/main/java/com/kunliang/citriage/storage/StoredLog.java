package com.kunliang.citriage.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kunliang.citriage.log.BuildLog;
import com.kunliang.citriage.log.DiagnosticCategory;
import com.kunliang.citriage.log.FailureGroup;
import com.kunliang.citriage.log.LogEvidence;
import com.kunliang.citriage.log.LogFinding;
import com.kunliang.citriage.report.TestIdentity;
import com.kunliang.citriage.storage.SavedRun.SavedLog;

/** Saves findings and group membership, with evidence ranges into the source byte snapshot. */
final class StoredLog {
    private StoredLog() {
    }

    static void write(Connection connection, long sourceId, BuildLog log, List<LogFinding> findings,
                      List<FailureGroup> groups) throws SQLException {
        Map<LogFinding, Integer> indices = new IdentityHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO log_findings
                    (source_id, finding_index, category, rule_id, explanation, class_name, test_name, first_line, last_line)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, sourceId);
            for (int i = 0; i < findings.size(); i++) {
                LogFinding finding = findings.get(i);
                LogEvidence evidence = finding.evidence();
                if (evidence.source() != log.source() || evidence.lastLine() > log.lines().size()
                        || !evidence.lines().equals(log.lines().subList(evidence.firstLine() - 1, evidence.lastLine()))) {
                    throw new SQLException("A finding's evidence must come from the saved log snapshot.");
                }
                indices.put(finding, i);
                statement.setInt(2, i);
                statement.setString(3, finding.category().name());
                statement.setString(4, finding.ruleId());
                statement.setString(5, finding.explanation());
                statement.setString(6, finding.testIdentity().flatMap(TestIdentity::className).orElse(null));
                statement.setString(7, finding.testIdentity().map(TestIdentity::testName).orElse(null));
                statement.setInt(8, evidence.firstLine());
                statement.setInt(9, evidence.lastLine());
                statement.executeUpdate();
            }
        }
        if (groups.stream().mapToLong(FailureGroup::occurrenceCount).sum() != findings.size()) {
            throw new SQLException("Saved groups must account for every log finding.");
        }
        writeGroups(connection, sourceId, groups, indices);
    }

    private static void writeGroups(Connection connection, long sourceId, List<FailureGroup> groups,
                                    Map<LogFinding, Integer> indices) throws SQLException {
        try (PreparedStatement group = connection.prepareStatement("""
                     INSERT INTO failure_groups (source_id, group_index, display_id, category, signature)
                     VALUES (?, ?, ?, ?, ?)
                     """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO group_members (source_id, group_index, member_index, finding_index) VALUES (?, ?, ?, ?)
                     """)) {
            group.setLong(1, sourceId);
            member.setLong(1, sourceId);
            for (int i = 0; i < groups.size(); i++) {
                FailureGroup findingGroup = groups.get(i);
                group.setInt(2, i);
                group.setString(3, findingGroup.id());
                group.setString(4, findingGroup.category().name());
                group.setString(5, findingGroup.signature());
                group.executeUpdate();

                member.setInt(2, i);
                for (int j = 0; j < findingGroup.members().size(); j++) {
                    Integer index = indices.get(findingGroup.members().get(j));
                    if (index == null) {
                        throw new SQLException("A group member must be one of the saved log findings.");
                    }
                    member.setInt(3, j);
                    member.setInt(4, index);
                    member.executeUpdate();
                }
            }
        }
    }

    static SavedLog read(Connection connection, long sourceId, Instant importedAt, BuildLog log) throws SQLException {
        List<LogFinding> findings = readFindings(connection, sourceId, log);
        Map<Integer, List<LogFinding>> members = readMembers(connection, sourceId, findings);
        List<FailureGroup> groups = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT group_index, display_id, category, signature FROM failure_groups
                WHERE source_id = ? ORDER BY group_index
                """)) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    groups.add(new FailureGroup(rows.getString("display_id"),
                            DiagnosticCategory.valueOf(rows.getString("category")), rows.getString("signature"),
                            members.getOrDefault(rows.getInt("group_index"), List.of())));
                }
            }
        }
        if (groups.stream().mapToLong(FailureGroup::occurrenceCount).sum() != findings.size()) {
            throw new SQLException("Saved groups do not account for every log finding.");
        }
        return new SavedLog(importedAt, log, findings, groups);
    }

    private static List<LogFinding> readFindings(Connection connection, long sourceId, BuildLog log)
            throws SQLException {
        List<LogFinding> findings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT finding_index, category, rule_id, explanation, class_name, test_name, first_line, last_line
                FROM log_findings WHERE source_id = ? ORDER BY finding_index
                """)) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int start = rows.getInt("first_line");
                    int end = rows.getInt("last_line");
                    if (rows.getInt("finding_index") != findings.size() || start < 1 || end < start
                            || end > log.lines().size()) {
                        throw new SQLException("Invalid finding index or evidence range in saved log.");
                    }
                    LogEvidence evidence = new LogEvidence(log.source(), log.lines().subList(start - 1, end));
                    Optional<String> className = Optional.ofNullable(rows.getString("class_name"));
                    Optional<TestIdentity> test = Optional.ofNullable(rows.getString("test_name"))
                            .map(name -> new TestIdentity(className, name));
                    findings.add(new LogFinding(DiagnosticCategory.valueOf(rows.getString("category")),
                            rows.getString("rule_id"), rows.getString("explanation"), evidence, test));
                }
            }
        }
        return findings;
    }

    private static Map<Integer, List<LogFinding>> readMembers(Connection connection, long sourceId,
                                                               List<LogFinding> findings) throws SQLException {
        Map<Integer, List<LogFinding>> members = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT group_index, finding_index FROM group_members WHERE source_id = ? ORDER BY group_index, member_index
                """)) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int index = rows.getInt("finding_index");
                    if (index < 0 || index >= findings.size()) {
                        throw new SQLException("A saved group refers to a missing log finding.");
                    }
                    members.computeIfAbsent(rows.getInt("group_index"), ignored -> new ArrayList<>())
                            .add(findings.get(index));
                }
            }
        }
        return members;
    }
}
