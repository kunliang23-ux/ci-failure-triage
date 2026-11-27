package org.citriage.github;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.citriage.github.GitHubClient.Artifact;
import org.citriage.github.GitHubClient.Job;
import org.citriage.github.GitHubClient.WorkflowRun;
import org.citriage.log.DiagnosticCategory;
import org.citriage.report.JUnitXmlParser;
import org.citriage.report.ReportSource;
import org.citriage.report.TestOutcome;
import org.citriage.storage.RunMetadata;
import org.citriage.storage.RunMetadata.Field;
import org.citriage.storage.SqliteRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubImporterTest {
    static final String REPOSITORY = "example/project";
    static final long REPOSITORY_ID = 101;
    static final String TESTED = "b".repeat(40);
    static final String TRIGGER = "a".repeat(40);
    static final String REPORT = "reports/surefire/TEST-example.CheckTest.xml";
    static final String PASS_LOG = "[INFO] BUILD SUCCESS\r\n";
    static final String FAIL_LOG = "[INFO] Running example.CheckTest\r\n"
            + "[ERROR] example.CheckTest.check -- Time elapsed: 0.001 s <<< FAILURE!\r\n"
            + "org.opentest4j.AssertionFailedError: expected: <200> but was: <503>\r\n"
            + "    at example.CheckTest.check(CheckTest.java:10)\r\n[INFO] BUILD FAILURE";
    static final String PASS_XML = "<testsuite name=\"example.CheckTest\">\r\n"
            + "<testcase classname=\"example.CheckTest\" name=\"check\" time=\"0.001\"/>\r\n</testsuite>";
    static final String FAIL_XML = PASS_XML.replace("time=\"0.001\"/>", "time=\"0.001\"><failure "
            + "type=\"org.opentest4j.AssertionFailedError\" message=\"expected 200, observed 503\"/></testcase>");
    private static final Instant START = Instant.parse("2025-04-01T10:00:00Z");
    private static final Instant END = Instant.parse("2025-04-01T10:01:00Z");

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesAndReopensOriginalEvidenceAndJobMetadata(boolean failed) throws Exception {
        String xml = failed ? FAIL_XML : PASS_XML;
        String log = failed ? FAIL_LOG : PASS_LOG;
        Scenario scenario = scenario(200, 2, 300, "build", failed ? "failure" : "success",
                evidence(log, xml));
        var result = save(scenario);
        assertTrue(result.saved().inserted());
        assertEquals("github-101-200-2-300", result.saved().runId());
        assertEquals(1, result.reports());
        assertFalse(Files.exists(scenario.zipPath.getParent()));

        var reopened = repository().findRun(result.saved().runId()).orElseThrow();
        var origin = reopened.githubOrigin().orElseThrow();
        assertEquals(200, origin.workflowRunId());
        assertEquals(300, origin.jobId());
        assertEquals(900, origin.artifactId());
        assertEquals(2, origin.attempt());
        assertEquals(TRIGGER, origin.triggeringHeadCommit().orElseThrow());
        assertEquals("manifest.json", origin.manifest().name());
        assertEquals(result.origin().manifest().sha256(), origin.manifest().sha256());
        assertArrayEquals(result.origin().manifest().bytes(), origin.manifest().bytes());
        assertEquals(TESTED, reopened.metadata().value(Field.COMMIT).orElseThrow());
        assertEquals(Optional.of(START), reopened.metadata().instant(Field.EXECUTED_AT));
        assertEquals(Optional.of(END), reopened.metadata().instant(Field.COMPLETED_AT));
        assertEquals(Optional.of(failed ? "FAILURE" : "SUCCESS"), reopened.metadata().value(Field.CONCLUSION));
        assertEquals("maven-verify", reopened.metadata().value(Field.TEST_CONFIGURATION).orElseThrow());
        assertEquals("Temurin 17", reopened.metadata().value(Field.TOOLCHAIN).orElseThrow());

        var report = reopened.reports().get(0).report();
        assertSource(REPORT, xml, report.source());
        assertEquals(1, report.count(failed ? TestOutcome.FAILED : TestOutcome.PASSED));
        var savedLog = reopened.logs().get(0);
        assertSource("build.log", log, savedLog.log().source());
        assertEquals(failed ? 1 : 0, savedLog.findings().size());
        if (failed) {
            var member = savedLog.groups().get(0).members().get(0);
            assertEquals(DiagnosticCategory.ASSERTION_ERROR, member.category());
            assertEquals("example.CheckTest#check", member.testIdentity().orElseThrow().displayName());
            assertEquals(2, member.evidence().firstLine());
            assertEquals(4, member.evidence().lastLine());
            assertTrue(member.evidence().originalText().endsWith("CheckTest.java:10)\r\n"));
        }
    }

    @Test
    void failedJobWithOnlyALogDoesNotInventTestResults() throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "failure", evidence(FAIL_LOG, null));
        var result = save(scenario);
        assertEquals(0, result.reports());
        assertEquals(Optional.of(1), result.findings());
        assertEquals(Optional.of(1), result.groups());
        var saved = repository().findRun(result.saved().runId()).orElseThrow();
        assertTrue(saved.reports().isEmpty());
        assertEquals(1, saved.logs().size());
    }

    @Test
    void unavailableFactsAndUnsupportedConclusionRemainUnknown() throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "action_required", evidence(PASS_LOG, null));
        scenario.jobs = List.of(new Job(300, 200, OptionalInt.empty(), "build", "completed",
                Optional.of("action_required"), Optional.empty(), Optional.empty()));
        scenario.zip = archive(scenario.run, "build", evidence(PASS_LOG, null), manifest -> {
            manifest.remove("tested_commit");
            manifest.remove("triggering_head_commit");
            manifest.remove("execution");
        });
        var result = save(scenario);
        assertTrue(result.metadata().value(Field.CONCLUSION).isEmpty());
        assertTrue(result.metadata().value(Field.COMPLETED_AT).isEmpty());
        assertTrue(result.metadata().value(Field.TEST_COMMAND).isEmpty());
        assertTrue(result.metadata().value(Field.COMMIT).isEmpty());
        assertTrue(result.origin().triggeringHeadCommit().isEmpty());
    }

    @Test
    void repeatsAreIdempotentAndJobsAttemptsAndRunsRemainDistinct() throws Exception {
        Map<String, byte[]> evidence = evidence(PASS_LOG, PASS_XML);
        Scenario first = scenario(200, 1, 300, "build", "success", evidence);
        String original = save(first).saved().runId();
        var importedAt = repository().findRun(original).orElseThrow().firstImportedAt();
        assertFalse(save(first).saved().inserted());
        assertEquals(importedAt, repository().findRun(original).orElseThrow().firstImportedAt());

        Scenario otherJob = scenario(200, 1, 301, "verify", "success", evidence);
        var jobList = new ArrayList<>(otherJob.jobs);
        jobList.add(first.jobs.get(0));
        otherJob.jobs = List.copyOf(jobList);
        var selected = new GitHubImporter.Request("Example/Project", 200, OptionalInt.of(1), OptionalLong.of(301));
        assertEquals("github-101-200-1-301", new GitHubImporter(otherJob).save(selected, repository()).saved().runId());
        assertNotEquals(original, save(scenario(200, 2, 302, "build", "success", evidence)).saved().runId());
        assertNotEquals(original, save(scenario(201, 1, 303, "build", "success", evidence)).saved().runId());
        assertEquals(4, repository().listRuns().size());
        for (var run : repository().listRuns()) {
            assertEquals(1, run.junitSources());
            assertEquals(1, run.logSources());
        }
    }

    @Test
    void duplicateBundleKeepsOneSourcePerKindAndHashWhileManifestKeepsBothEntryNames() throws Exception {
        var files = evidence(PASS_LOG, PASS_XML);
        String duplicateName = "reports/failsafe/TEST-example.CheckTest.xml";
        files.put(duplicateName, bytes(PASS_XML));
        var result = save(scenario(200, 1, 300, "build", "success", files));
        var saved = repository().findRun(result.saved().runId()).orElseThrow();
        assertEquals(1, saved.reports().size());
        assertTrue(new String(saved.githubOrigin().orElseThrow().manifest().bytes(), StandardCharsets.UTF_8)
                .contains(duplicateName));
    }

    @Test
    void rejectsAmbiguousJobsEvenBeforeLookingForArtifacts() throws Exception {
        Scenario scenario = scenario(200, 2, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        scenario.jobs = List.of(scenario.jobs.get(0), job(200, 2, 301, "verify", "success"));
        assertRejected(scenario, "Ambiguous job selection");
        assertEquals(0, scenario.artifactRequests);
        scenario.jobs = List.of(scenario.jobs.get(0), job(200, 2, 301, "build", "success"));
        var selected = new GitHubImporter.Request(REPOSITORY, 200, OptionalInt.of(2), OptionalLong.of(300));
        assertTrue(assertThrows(IOException.class, () -> new GitHubImporter(scenario).save(selected, repository()))
                .getMessage().contains("unique API name"));
    }

    @Test
    void rejectsAnotherAttemptOrAnIncompleteJob() throws Exception {
        Scenario scenario = scenario(200, 2, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        scenario.jobs = List.of(job(200, 1, 300, "build", "success"));
        assertRejected(scenario, "job provenance");
        scenario.jobs = List.of(new Job(300, 200, OptionalInt.of(2), "build", "in_progress", Optional.empty(),
                Optional.of(START), Optional.empty()));
        assertRejected(scenario, "job is not completed");
        scenario.run = new WorkflowRun(200, REPOSITORY, REPOSITORY_ID, 2, "Java CI", "in_progress",
                Optional.of(TRIGGER), true);
        assertRejected(scenario, "attempt is not completed");
    }

    @Test
    void rejectsMissingExpiredAndAmbiguousBundles() throws Exception {
        Scenario scenario = scenario(200, 2, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        var expected = scenario.artifacts.get(0);
        scenario.artifacts = List.of(artifact(200, 1, "build", 810, false));
        assertRejected(scenario, "Missing triage bundle");
        scenario.artifacts = List.of(artifact(200, 2, "build", 900, true));
        assertRejected(scenario, "has expired");
        scenario.artifacts = List.of(expected, artifact(200, 2, "build", 901, false));
        assertRejected(scenario, "Ambiguous triage bundle");
        assertNull(scenario.zipPath);
    }

    @Test
    void rejectsRepositoryAndTimestampConflictsInArtifactMetadata() throws Exception {
        Scenario scenario = scenario(200, 2, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        var artifact = scenario.artifacts.get(0);
        scenario.artifacts = List.of(new Artifact(artifact.id(), artifact.name(), false, 100, OptionalLong.of(200),
                OptionalLong.of(999), artifact.createdAt(), Optional.empty()));
        assertRejected(scenario, "artifact provenance");
        scenario.artifacts = List.of(new Artifact(artifact.id(), artifact.name(), false, 100, OptionalLong.of(200),
                OptionalLong.of(REPOSITORY_ID), Optional.of(END.plusSeconds(1)), Optional.empty()));
        assertRejected(scenario, "artifact provenance");
    }

    @Test
    void rejectsWrongJobAttemptAndTriggeringHeadInManifest() throws Exception {
        var files = evidence(FAIL_LOG, FAIL_XML);
        Scenario scenario = scenario(200, 2, 300, "build", "failure", files);
        scenario.zip = archive(scenario.run, "another-job", files, manifest -> {});
        assertRejected(scenario, "manifest provenance");
        scenario.zip = archive(scenario.run, "build", files, manifest -> manifest.addProperty("attempt", 1));
        assertRejected(scenario, "manifest provenance");
        scenario.zip = archive(scenario.run, "build", files, manifest -> manifest.addProperty("triggering_head_commit",
                "c".repeat(40)));
        assertRejected(scenario, "commit conflicts");
    }

    @Test
    void rejectsArchiveDigestAndInventoryMismatch() throws Exception {
        var files = evidence(FAIL_LOG, FAIL_XML);
        Scenario scenario = scenario(200, 2, 300, "build", "failure", files);
        Artifact original = scenario.artifacts.get(0);
        scenario.artifacts = List.of(new Artifact(original.id(), original.name(), false, 100, original.runId(),
                original.repositoryId(), original.createdAt(), Optional.of("sha256:" + "0".repeat(64))));
        assertRejected(scenario, "does not match the GitHub artifact digest");
        scenario.artifacts = List.of(original);
        scenario.zip = archive(scenario.run, "build", files, manifest -> manifest.getAsJsonArray("files").get(0)
                .getAsJsonObject().addProperty("sha256", "0".repeat(64)));
        assertRejected(scenario, "does not match the manifest inventory");
        scenario.zip = archive(scenario.run, "build", files, manifest -> manifest.getAsJsonArray("files").remove(0));
        assertRejected(scenario, "contents do not match");
    }

    @Test
    void rejectsMalformedManifestUnsupportedVersionAndMalformedXmlBeforeAnyDatabaseWrite() throws Exception {
        var files = evidence(FAIL_LOG, "<testsuite><testcase name=\"incomplete\">");
        Scenario scenario = scenario(200, 2, 300, "build", "failure", files);
        assertRejected(scenario, "Cannot parse JUnit evidence");
        scenario.zip = archive(scenario.run, "build", files, manifest -> manifest.addProperty("version", 2));
        assertRejected(scenario, "Unsupported triage bundle manifest version");
        files.put("manifest.json", bytes("{\"version\":1,\"version\":1}"));
        scenario.zip = zip(files, "");
        assertRejected(scenario, "strict UTF-8 JSON with unique field names");
        assertFalse(Files.exists(scenario.zipPath.getParent()));
    }

    @Test
    void rejectsEmptyEvidenceAndUndecodableLogs() throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "failure", evidence("\r\n", null));
        assertRejected(scenario, "Evidence unavailable");
        scenario.zip = archive(scenario.run, "build", Map.of("build.log", new byte[] {(byte) 0xff}), ignored -> {});
        assertRejected(scenario, "Expected valid UTF-8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../build.log", "/build.log", "C:/build.log", "reports\\surefire\\TEST-Check.xml",
            "reports/surefire/../../build.log", "unlisted.exe"})
    void rejectsUnsafeAndUnexpectedZipPaths(String path) throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "failure", evidence(FAIL_LOG, null));
        var output = new ByteArrayOutputStream();
        // The JDK writer keeps backslashes in the raw entry name for this rejection test.
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(bytes("unexpected"));
            zip.closeEntry();
        }
        scenario.zip = output.toByteArray();
        assertRejected(scenario, "unsafe or unexpected entry path");
        assertFalse(Files.exists(scenario.zipPath.getParent()));
    }

    @Test
    void rejectsSymlinksCaseCollisionsAndOversizedInflatedEntries() throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "failure", evidence(FAIL_LOG, null));
        scenario.zip = zip(Map.of("build.log", bytes("elsewhere")), "build.log");
        assertRejected(scenario, "unsupported entry type");
        scenario.zip = zip(Map.of(REPORT, bytes(PASS_XML), REPORT.replace("Check", "check"), bytes(PASS_XML)), "");
        assertRejected(scenario, "case-colliding entries");
        scenario.zip = zip(Map.of("build.log", new byte[BundleArchive.MAX_FILE_BYTES + 1]), "");
        assertRejected(scenario, "uncompressed size limit");
    }

    @Test
    void limitsCentralDirectoryRecordsBeforeExtractionEvenIfTheZipCountIsForged() throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        var files = new LinkedHashMap<String, byte[]>();
        for (int index = 0; index < 257; index++) {
            files.put("reports/surefire/TEST-example.Check" + index + ".xml", bytes(PASS_XML));
        }
        scenario.zip = zip(files, "");
        assertRejected(scenario, "256-entry limit");
        int end = scenario.zip.length - 22;
        scenario.zip[end + 8] = 1;
        scenario.zip[end + 9] = 0;
        scenario.zip[end + 10] = 1;
        scenario.zip[end + 11] = 0;
        assertRejected(scenario, "256-entry limit");
    }

    @Test
    void supportsOrdinaryZip64Archives() throws Exception {
        Scenario scenario = scenario(200, 1, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(output)) {
            zip.setUseZip64(Zip64Mode.Always);
            try (var original = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(scenario.zip))) {
                ZipEntry entry;
                while ((entry = original.getNextEntry()) != null) {
                    zip.putArchiveEntry(new ZipArchiveEntry(entry.getName()));
                    original.transferTo(zip);
                    zip.closeArchiveEntry();
                }
            }
        }
        scenario.zip = output.toByteArray();
        assertEquals(1, save(scenario).reports());
    }

    @Test
    void readsVersionTwoWithoutMigrationAndPreservesLocalEvidenceWhenGitHubImportUpgradesIt() throws Exception {
        var report = new JUnitXmlParser().parse(new ReportSource("local.xml", bytes(PASS_XML)));
        repository().saveReport(Optional.of("local"), RunMetadata.supplied("example-commit", null), report);
        execute("DROP TABLE github_origins");
        execute("PRAGMA user_version = 2");
        byte[] before = Files.readAllBytes(databasePath());
        assertTrue(repository().findRun("local").orElseThrow().githubOrigin().isEmpty());
        assertArrayEquals(before, Files.readAllBytes(databasePath()));
        var scenario = scenario(200, 1, 300, "build", "success", evidence(PASS_LOG, PASS_XML));
        assertTrue(save(scenario).saved().inserted());
        assertSource("local.xml", PASS_XML, repository().findRun("local").orElseThrow().reports().get(0).report().source());
        assertEquals(2, repository().listRuns().size());
    }

    @Test
    void rollsBackOriginMetadataAndAllSourcesOnALateWriteFailureAndCanRetry() throws Exception {
        SqliteRunRepository repository = repository();
        var localReport = new JUnitXmlParser().parse(new ReportSource("local.xml", bytes(PASS_XML)));
        repository.saveReport(Optional.of("local"), RunMetadata.supplied(null, null), localReport);
        execute("""
                CREATE TRIGGER reject_findings BEFORE INSERT ON log_findings BEGIN
                    SELECT RAISE(ABORT, 'simulated bundle write failure');
                END
                """);
        Scenario scenario = scenario(200, 1, 300, "build", "failure", evidence(FAIL_LOG, FAIL_XML));
        assertTrue(assertThrows(SQLException.class, () -> save(scenario)).getMessage().contains("bundle write failure"));
        assertFalse(Files.exists(scenario.zipPath.getParent()));
        assertTrue(repository.findRun("github-101-200-1-300").isEmpty());
        assertEquals(1, repository.listRuns().size());
        assertEquals(0, rowCount("github_origins"));
        assertEquals(1, rowCount("sources"));
        assertEquals(0, rowCount("run_metadata"));
        execute("DROP TRIGGER reject_findings");
        assertTrue(save(scenario).saved().inserted());
        assertEquals(2, repository.listRuns().size());
    }

    @Test
    void rejectsConflictingReimportsAndLocalEvidenceWithSameLocalId() throws Exception {
        var files = evidence(PASS_LOG, PASS_XML);
        Scenario scenario = scenario(200, 1, 300, "build", "success", files);
        String id = save(scenario).saved().runId();
        scenario.jobs = List.of(job(200, 1, 300, "build", "failure"));
        assertTrue(assertThrows(SQLException.class, () -> save(scenario)).getMessage().contains("Conflicting Run conclusion"));
        scenario.artifacts = List.of(artifact(200, 1, "build", 901, false));
        assertTrue(assertThrows(SQLException.class, () -> save(scenario)).getMessage().contains("Conflicting GitHub bundle"));
        var report = new JUnitXmlParser().parse(new ReportSource("local.xml", bytes(FAIL_XML)));
        assertThrows(SQLException.class, () -> repository().saveReport(Optional.of(id), RunMetadata.supplied(null, null), report));
        assertEquals(1, repository().findRun(id).orElseThrow().reports().size());
    }

    private GitHubImporter.Result save(Scenario scenario) throws IOException, SQLException {
        var request = new GitHubImporter.Request(REPOSITORY, scenario.run.id(), OptionalInt.empty(), OptionalLong.empty());
        return new GitHubImporter(scenario).save(request, repository());
    }

    private void assertRejected(Scenario scenario, String reason) {
        IOException failure = assertThrows(IOException.class, () -> save(scenario));
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
        assertFalse(Files.exists(databasePath()), "Validation should precede database creation.");
    }

    private SqliteRunRepository repository() {
        return new SqliteRunRepository(databasePath());
    }

    private Path databasePath() {
        return temp.resolve("triage.sqlite");
    }

    private void execute(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath().toUri());
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long rowCount(String table) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath().toUri());
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return row.getLong(1);
        }
    }

    static Scenario scenario(long runId, int attempt, long jobId, String name, String conclusion,
                             Map<String, byte[]> files) throws IOException {
        var run = new WorkflowRun(runId, REPOSITORY, REPOSITORY_ID, attempt, "Java CI", "completed",
                Optional.of(TRIGGER), true);
        return new Scenario(run, List.of(job(runId, attempt, jobId, name, conclusion)),
                List.of(artifact(runId, attempt, name, 900, false)), archive(run, name, files, ignored -> {}));
    }

    private static Job job(long runId, int attempt, long id, String name, String conclusion) {
        return new Job(id, runId, OptionalInt.of(attempt), name, "completed", Optional.of(conclusion),
                Optional.of(START), Optional.of(END));
    }

    private static Artifact artifact(long run, int attempt, String job, long id, boolean expired) {
        return new Artifact(id, "ci-triage-v1-" + run + "-" + attempt + "-" + job, expired, 100,
                OptionalLong.of(run), OptionalLong.of(REPOSITORY_ID), Optional.of(START.plusSeconds(45)), Optional.empty());
    }

    static Map<String, byte[]> evidence(String log, String xml) {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("build.log", bytes(log));
        if (xml != null) {
            files.put(REPORT, bytes(xml));
        }
        return files;
    }

    static byte[] archive(WorkflowRun run, String job, Map<String, byte[]> evidence, Consumer<JsonObject> change)
            throws IOException {
        var manifest = new JsonObject();
        manifest.addProperty("version", 1);
        manifest.addProperty("repository", run.repository());
        manifest.addProperty("repository_id", run.repositoryId());
        manifest.addProperty("run_id", run.id());
        manifest.addProperty("attempt", run.attempt());
        manifest.addProperty("workflow", run.workflow());
        manifest.addProperty("job", job);
        manifest.addProperty("tested_commit", TESTED);
        manifest.addProperty("triggering_head_commit", TRIGGER);
        var execution = new JsonObject();
        execution.addProperty("test_command", "mvn --batch-mode clean verify");
        execution.addProperty("test_configuration", "maven-verify");
        execution.addProperty("environment", "sample CI");
        execution.addProperty("os", "Linux");
        execution.addProperty("architecture", "x86_64");
        execution.addProperty("toolchain", "Temurin 17");
        execution.addProperty("dependency_fingerprint", "sample-dependencies-v1");
        manifest.add("execution", execution);
        var inventory = new JsonArray();
        for (var file : evidence.entrySet()) {
            var row = new JsonObject();
            row.addProperty("path", file.getKey());
            row.addProperty("kind", file.getKey().endsWith(".log") ? "log" : "junit");
            row.addProperty("size", file.getValue().length);
            row.addProperty("sha256", new ReportSource(file.getKey(), file.getValue()).sha256());
            inventory.add(row);
        }
        manifest.add("files", inventory);
        change.accept(manifest);
        var contents = new LinkedHashMap<>(evidence);
        contents.put("manifest.json", bytes(manifest.toString()));
        return zip(contents, "");
    }

    private static byte[] zip(Map<String, byte[]> files, String symlink) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipArchiveOutputStream(output)) {
            for (var file : files.entrySet()) {
                var entry = new ZipArchiveEntry(file.getKey());
                if (file.getKey().equals(symlink)) {
                    entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
                }
                zip.putArchiveEntry(entry);
                zip.write(file.getValue());
                zip.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static void assertSource(String name, String content, ReportSource saved) {
        assertEquals(name, saved.name());
        assertArrayEquals(bytes(content), saved.bytes());
        assertEquals(new ReportSource(name, bytes(content)).sha256(), saved.sha256());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static final class Scenario implements GitHubClient {
        WorkflowRun run;
        List<Job> jobs;
        List<Artifact> artifacts;
        byte[] zip;
        Path zipPath;
        int artifactRequests;

        private Scenario(WorkflowRun run, List<Job> jobs, List<Artifact> artifacts, byte[] zip) {
            this.run = run;
            this.jobs = jobs;
            this.artifacts = artifacts;
            this.zip = zip;
        }

        @Override
        public WorkflowRun run(GitHubImporter.Request request) {
            return run;
        }

        @Override
        public List<Job> jobs(WorkflowRun selected) {
            return jobs;
        }

        @Override
        public List<Artifact> artifacts(WorkflowRun selected, String name) {
            artifactRequests++;
            return artifacts;
        }

        @Override
        public void download(WorkflowRun selected, Artifact artifact, Path destination) throws IOException {
            zipPath = destination;
            Files.write(destination, zip);
        }
    }
}
