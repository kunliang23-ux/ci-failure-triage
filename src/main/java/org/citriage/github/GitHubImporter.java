package org.citriage.github;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import org.citriage.github.GitHubClient.Artifact;
import org.citriage.github.GitHubClient.Job;
import org.citriage.github.GitHubClient.WorkflowRun;
import org.citriage.log.BuildLog;
import org.citriage.log.LogClassifier;
import org.citriage.log.LogGrouper;
import org.citriage.log.LogReader;
import org.citriage.report.JUnitReport;
import org.citriage.report.JUnitXmlParser;
import org.citriage.report.ReportParseException;
import org.citriage.report.ReportSource;
import org.citriage.storage.GitHubOrigin;
import org.citriage.storage.RunMetadata;
import org.citriage.storage.RunMetadata.Field;
import org.citriage.storage.SqliteRunRepository;
import org.citriage.storage.SqliteRunRepository.LogObservations;
import org.citriage.storage.SqliteRunRepository.SaveResult;

/** Selects one completed execution and validates all its evidence before opening the database. */
public final class GitHubImporter {
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern JOB_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,99}");

    private final GitHubClient client;

    GitHubImporter(GitHubClient client) {
        this.client = client;
    }

    public static Result importBundle(Request request, Path database, String token) throws IOException, SQLException {
        return new GitHubImporter(new GitHubRestClient(token)).save(request, new SqliteRunRepository(database));
    }

    Result save(Request request, SqliteRunRepository repository) throws IOException, SQLException {
        WorkflowRun run = client.run(request);
        if (run.id() != request.workflowRunId() || !request.repository().equalsIgnoreCase(run.repository())
                || request.attempt().isPresent() && request.attempt().getAsInt() != run.attempt()) {
            throw new IOException("GitHub workflow run provenance does not match the requested repository, run, or attempt.");
        }
        if (!"completed".equals(run.status())) {
            throw new IOException("The selected workflow run attempt is not completed. Wait for it or choose an earlier attempt.");
        }
        Job job = selectJob(request, run, client.jobs(run));
        String name = "ci-triage-v1-" + run.id() + "-" + run.attempt() + "-" + job.name();
        Artifact artifact = selectArtifact(run, job, name, client.artifacts(run, name));
        ValidatedBundle bundle;
        try (var archive = new BundleArchive()) {
            client.download(run, artifact, archive.zipPath());
            bundle = validate(run, job, artifact, archive.extract(artifact.digest()));
        }
        SaveResult saved = repository.saveGitHubBundle(bundle.origin(), bundle.metadata(), bundle.reports(), bundle.log());
        return new Result(saved, bundle.origin(), bundle.metadata(), bundle.reports().size(),
                bundle.log().map(log -> log.findings().size()), bundle.log().map(log -> log.groups().size()));
    }

    private static Job selectJob(Request request, WorkflowRun run, List<Job> jobs) throws IOException {
        List<Job> matches = request.jobId().isEmpty() ? jobs
                : jobs.stream().filter(job -> job.id() == request.jobId().getAsLong()).toList();
        if (matches.isEmpty()) {
            throw new IOException("No matching job exists in the selected attempt. Check --attempt and --job-id.");
        }
        if (matches.size() != 1) {
            throw new IOException("Ambiguous job selection. Supply --job-id from the selected attempt.");
        }
        Job job = matches.get(0);
        if (job.runId() != run.id() || job.attempt().isPresent() && job.attempt().getAsInt() != run.attempt()) {
            throw new IOException("GitHub job provenance does not match the selected run attempt.");
        }
        if (!"completed".equals(job.status())) {
            throw new IOException("The selected GitHub job is not completed. Wait for it to finish.");
        }
        if (!JOB_NAME.matcher(job.name()).matches()
                || jobs.stream().filter(item -> item.name().equals(job.name())).count() != 1) {
            throw new IOException("The selected job needs a unique API name equal to its logical workflow job ID.");
        }
        if (job.startedAt().isPresent() && job.completedAt().isPresent()
                && job.completedAt().get().isBefore(job.startedAt().get())) {
            throw new IOException("GitHub job completion precedes its start time.");
        }
        return job;
    }

    private static Artifact selectArtifact(WorkflowRun run, Job job, String name, List<Artifact> artifacts)
            throws IOException {
        List<Artifact> matches = artifacts.stream().filter(artifact -> name.equals(artifact.name())).toList();
        if (matches.isEmpty()) {
            throw new IOException("Missing triage bundle '" + name
                    + "'. Check the workflow upload step, artifact retention, and the selected attempt and job.");
        }
        if (matches.size() > 1) {
            throw new IOException("Ambiguous triage bundle selection for the job. Multiple artifacts have the expected name.");
        }
        Artifact artifact = matches.get(0);
        if (artifact.expired()) {
            throw new IOException("The selected triage bundle has expired. Select another run or rerun the workflow.");
        }
        if (artifact.runId().orElse(run.id()) != run.id()
                || artifact.repositoryId().orElse(run.repositoryId()) != run.repositoryId()
                || artifact.createdAt().filter(time -> job.startedAt().filter(time::isBefore).isPresent()
                        || job.completedAt().filter(time::isAfter).isPresent()).isPresent()) {
            throw new IOException("GitHub artifact provenance does not match the selected job execution.");
        }
        if (artifact.size() > BundleArchive.MAX_DOWNLOAD_BYTES) {
            throw new IOException("The selected artifact exceeds the 32 MiB download limit.");
        }
        return artifact;
    }

    private static ValidatedBundle validate(WorkflowRun run, Job job, Artifact artifact, Map<String, Path> files)
            throws IOException {
        Path manifestFile = files.get(BundleArchive.MANIFEST);
        if (manifestFile == null) {
            throw new IOException("The triage bundle is missing manifest.json.");
        }
        var manifestSource = new ReportSource(BundleArchive.MANIFEST, Files.readAllBytes(manifestFile));
        BundleManifest manifest = BundleManifest.read(manifestSource);
        verifyManifest(run, job, manifest);
        if (files.size() != manifest.files().size() + 1) {
            throw new IOException("The ZIP contents do not match the manifest file inventory.");
        }
        var reports = new ArrayList<JUnitReport>();
        Optional<LogObservations> log = Optional.empty();
        for (BundleManifest.Entry entry : manifest.files()) {
            Path file = files.get(entry.name());
            if (file == null || Files.size(file) != entry.size()) {
                throw new IOException("The ZIP contents do not match the manifest file inventory.");
            }
            var source = new ReportSource(entry.name(), Files.readAllBytes(file));
            if (!entry.sha256().equals(source.sha256())) {
                throw new IOException("An evidence file SHA-256 does not match the manifest inventory.");
            }
            if (BundleArchive.BUILD_LOG.equals(entry.name())) {
                log = Optional.of(readLog(source));
            } else {
                reports.add(readReport(source));
            }
        }
        if (reports.isEmpty() && log.map(evidence -> evidence.log().isBlank()).orElse(true)) {
            throw new IOException("Evidence unavailable. The bundle contains neither a JUnit report nor a nonblank build log.");
        }
        var origin = new GitHubOrigin(run.repository().toLowerCase(Locale.ROOT), run.repositoryId(), run.id(),
                run.attempt(), job.id(), artifact.id(), manifest.triggeringHead(), manifestSource);
        return new ValidatedBundle(origin, metadata(origin, run, job, manifest), List.copyOf(reports), log);
    }

    private static void verifyManifest(WorkflowRun run, Job job, BundleManifest manifest) throws IOException {
        if (!run.repository().equalsIgnoreCase(manifest.repository()) || run.repositoryId() != manifest.repositoryId()
                || run.id() != manifest.runId() || run.attempt() != manifest.attempt()
                || !run.workflow().equals(manifest.workflow()) || !job.name().equals(manifest.job())) {
            throw new IOException("Bundle manifest provenance conflicts with the selected repository, run, attempt, or job.");
        }
        if (manifest.triggeringHead().isPresent() && run.headSha().isPresent()) {
            String apiHead = run.headSha().get();
            // For pull requests the API SHA can describe a different ref from the checked-out merge.
            boolean matchesTrigger = apiHead.equalsIgnoreCase(manifest.triggeringHead().get());
            boolean matchesTestedMerge = run.pullRequest()
                    && manifest.testedCommit().filter(apiHead::equalsIgnoreCase).isPresent();
            if (!matchesTrigger && !matchesTestedMerge) {
                throw new IOException("Bundle triggering head commit conflicts with the GitHub workflow run.");
            }
        }
    }

    private static RunMetadata metadata(GitHubOrigin origin, WorkflowRun run, Job job, BundleManifest manifest) {
        var fields = new EnumMap<Field, String>(Field.class);
        fields.put(Field.REPOSITORY, origin.repository());
        fields.put(Field.WORKFLOW, run.workflow());
        fields.put(Field.JOB, job.name());
        fields.put(Field.ATTEMPT, Integer.toString(run.attempt()));
        job.startedAt().ifPresent(value -> fields.put(Field.EXECUTED_AT, value.toString()));
        job.completedAt().ifPresent(value -> fields.put(Field.COMPLETED_AT, value.toString()));
        job.conclusion().flatMap(GitHubImporter::knownConclusion)
                .ifPresent(value -> fields.put(Field.CONCLUSION, value.name()));
        manifest.testedCommit().ifPresent(value -> fields.put(Field.COMMIT, value));
        return new RunMetadata(fields).merge(manifest.execution());
    }

    private static Optional<RunMetadata.Conclusion> knownConclusion(String conclusion) {
        try {
            return Optional.of(RunMetadata.Conclusion.valueOf(conclusion.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static JUnitReport readReport(ReportSource source) throws IOException {
        try {
            return new JUnitXmlParser().parse(source);
        } catch (ReportParseException exception) {
            String filename = source.name().substring(source.name().lastIndexOf('/') + 1);
            throw new IOException("Cannot parse JUnit evidence at " + exception.location().display(filename) + ".", exception);
        }
    }

    private static LogObservations readLog(ReportSource source) throws IOException {
        try {
            BuildLog log = new LogReader().decode(source);
            var findings = new LogClassifier().classify(log);
            return new LogObservations(log, findings, new LogGrouper().group(findings));
        } catch (CharacterCodingException exception) {
            throw new IOException("Cannot decode build.log. Expected valid UTF-8.", exception);
        }
    }

    private record ValidatedBundle(GitHubOrigin origin, RunMetadata metadata, List<JUnitReport> reports,
                                   Optional<LogObservations> log) {
    }

    public record Result(SaveResult saved, GitHubOrigin origin, RunMetadata metadata, int reports,
                         Optional<Integer> findings, Optional<Integer> groups) {
    }

    public record Request(String repository, long workflowRunId, OptionalInt attempt, OptionalLong jobId) {
        public Request {
            if (!REPOSITORY.matcher(repository).matches() || repository.endsWith("/.") || repository.endsWith("/..")
                    || repository.toLowerCase(Locale.ROOT).endsWith(".git")) {
                throw new IllegalArgumentException("import-github requires a repository in OWNER/REPO form.");
            }
            if (workflowRunId < 1 || attempt.orElse(1) < 1 || jobId.orElse(1) < 1) {
                throw new IllegalArgumentException("Run ID, attempt, and job ID must be positive integers.");
            }
        }
    }
}
