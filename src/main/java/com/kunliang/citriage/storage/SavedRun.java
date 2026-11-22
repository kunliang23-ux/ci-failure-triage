package com.kunliang.citriage.storage;

import java.time.Instant;
import java.util.List;

import com.kunliang.citriage.log.BuildLog;
import com.kunliang.citriage.log.FailureGroup;
import com.kunliang.citriage.log.LogFinding;
import com.kunliang.citriage.report.JUnitReport;

/** A saved execution and the source snapshots imported into it. */
public record SavedRun(String id, Instant firstImportedAt, RunMetadata metadata,
                       List<SavedReport> reports, List<SavedLog> logs) {
    public SavedRun {
        reports = List.copyOf(reports);
        logs = List.copyOf(logs);
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
