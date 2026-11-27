package org.citriage.github;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** The GitHub operations needed to resolve and download one job bundle. */
interface GitHubClient {
    WorkflowRun run(GitHubImporter.Request request) throws IOException;

    List<Job> jobs(WorkflowRun run) throws IOException;

    List<Artifact> artifacts(WorkflowRun run, String name) throws IOException;

    void download(WorkflowRun run, Artifact artifact, Path destination) throws IOException;

    record WorkflowRun(long id, String repository, long repositoryId, int attempt, String workflow,
                       String status, Optional<String> headSha, boolean pullRequest) {
    }

    record Job(long id, long runId, OptionalInt attempt, String name, String status, Optional<String> conclusion,
               Optional<Instant> startedAt, Optional<Instant> completedAt) {
    }

    record Artifact(long id, String name, boolean expired, long size, OptionalLong runId, OptionalLong repositoryId,
                    Optional<Instant> createdAt, Optional<String> digest) {
    }
}
