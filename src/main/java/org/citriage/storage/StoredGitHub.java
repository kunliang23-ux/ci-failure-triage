package org.citriage.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.citriage.report.ReportSource;

final class StoredGitHub {
    private StoredGitHub() {
    }

    static Optional<GitHubOrigin> read(Connection connection, String id) throws SQLException {
        if (SqliteDatabase.schemaVersion(connection) < 3) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT repository, repository_id, workflow_run_id, attempt, job_id, artifact_id, triggering_head,
                       manifest_name, manifest_sha256, manifest_bytes
                FROM github_origins WHERE run_id = ?
                """)) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                var manifest = new ReportSource(row.getString("manifest_name"), row.getBytes("manifest_bytes"));
                if (!manifest.sha256().equals(row.getString("manifest_sha256"))) {
                    throw new SQLException("Saved GitHub manifest bytes do not match SHA-256 for run '" + id + "'.");
                }
                return Optional.of(new GitHubOrigin(row.getString("repository"), row.getLong("repository_id"),
                        row.getLong("workflow_run_id"), row.getInt("attempt"), row.getLong("job_id"),
                        row.getLong("artifact_id"), Optional.ofNullable(row.getString("triggering_head")), manifest));
            }
        }
    }

    static void write(Connection connection, GitHubOrigin origin) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO github_origins (run_id, repository, repository_id, workflow_run_id, attempt, job_id,
                    artifact_id, triggering_head, manifest_name, manifest_sha256, manifest_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, origin.localRunId());
            statement.setString(2, origin.repository());
            statement.setLong(3, origin.repositoryId());
            statement.setLong(4, origin.workflowRunId());
            statement.setInt(5, origin.attempt());
            statement.setLong(6, origin.jobId());
            statement.setLong(7, origin.artifactId());
            statement.setString(8, origin.triggeringHeadCommit().orElse(null));
            statement.setString(9, origin.manifest().name());
            statement.setString(10, origin.manifest().sha256());
            statement.setBytes(11, origin.manifest().bytes());
            statement.executeUpdate();
        }
    }
}
