package org.citriage.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.citriage.log.BuildLog;
import org.citriage.log.FailureGroup;
import org.citriage.log.LogFinding;
import org.citriage.report.JUnitReport;

/** A saved execution and the source snapshots imported into it. */
public record SavedRun(String id, Instant firstImportedAt, RunMetadata metadata,
                       List<SavedReport> reports, List<SavedLog> logs, Optional<GitHubOrigin> githubOrigin) {
    public SavedRun {
        reports = List.copyOf(reports);
        logs = List.copyOf(logs);
    }

    public SavedRun(String id, Instant firstImportedAt, RunMetadata metadata,
                    List<SavedReport> reports, List<SavedLog> logs) {
        this(id, firstImportedAt, metadata, reports, logs, Optional.empty());
    }

    public record SavedReport(Instant importedAt, JUnitReport report) {
    }

    public record SavedLog(Instant importedAt, BuildLog log, List<LogFinding> findings, List<FailureGroup> groups) {
        public SavedLog {
            findings = List.copyOf(findings);
            groups = List.copyOf(groups);
        }
    }
}
