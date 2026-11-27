package org.citriage.storage;

import java.util.Optional;

import org.citriage.report.ReportSource;

/** External execution identity and the original producer manifest, separate from local import time. */
public record GitHubOrigin(String repository, long repositoryId, long workflowRunId, int attempt, long jobId,
                           long artifactId, Optional<String> triggeringHeadCommit, ReportSource manifest) {
    public String localRunId() {
        return "github-" + repositoryId + "-" + workflowRunId + "-" + attempt + "-" + jobId;
    }

    boolean matches(GitHubOrigin other) {
        return localRunId().equals(other.localRunId()) && repository.equals(other.repository)
                && artifactId == other.artifactId && triggeringHeadCommit.equals(other.triggeringHeadCommit)
                && manifest.sha256().equals(other.manifest.sha256());
    }
}
