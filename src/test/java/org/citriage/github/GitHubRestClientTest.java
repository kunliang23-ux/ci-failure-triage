package org.citriage.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.citriage.storage.SqliteRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.citriage.github.GitHubImporterTest.evidence;
import static org.citriage.github.GitHubImporterTest.scenario;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRestClientTest {
    private static final String TOKEN = "test-token-for-mocked-requests";
    private static final String API = "https://api.github.com/repos/example/project/actions/";
    private static final String SIGNED_DOWNLOAD = "https://storage.example.invalid/bundle.zip?signature=mock-secret";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-04-01T10:02:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    private final ArrayDeque<Step> requests = new ArrayDeque<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<String> credentialsSentTo = new ArrayList<>();

    @Test
    void paginatesWithinSelectedAttemptAndDownloadsWithNoCredentialsOnStorageRequest() throws Exception {
        var run = scenario(200, 2, 300, "build", "success",
                evidence(GitHubImporterTest.PASS_LOG, GitHubImporterTest.PASS_XML));
        String jobsPage = "runs/200/attempts/2/jobs?per_page=100&page=";
        String artifactsPage = "runs/200/artifacts?per_page=100&name=ci-triage-v1-200-2-build&page=";
        expect("runs/200/attempts/2", ok(runResponse(2)));
        expect(jobsPage + "1", json(200, Map.of("Link",
                        List.of("<https://api.github.com/repositories/101/actions/runs/200/attempts/2/jobs?page=2>; rel=\"next\"")),
                jobs(2, 300, "build")));
        expect(jobsPage + "2", ok(jobs(2, 301, "other")));
        expect(artifactsPage + "1", json(200, Map.of("Link",
                        List.of("<https://api.github.com/repositories/101/actions/runs/200/artifacts?page=2>; rel=\"next\"")),
                artifacts("ci-triage-v1-200-1-build", 899, false)));
        expect(artifactsPage + "2", ok(artifacts("ci-triage-v1-200-2-build", 900, false)));
        expect("artifacts/900/zip", redirect(SIGNED_DOWNLOAD));
        requests.add(new Step(SIGNED_DOWNLOAD, bytes(200, Map.of(), run.zip), null));

        var request = new GitHubImporter.Request("example/project", 200, OptionalInt.of(2), OptionalLong.of(300));
        var repository = new SqliteRunRepository(temp.resolve("triage.sqlite"));
        var result = new GitHubImporter(client()).save(request, repository);

        assertEquals("github-101-200-2-300", result.saved().runId());
        assertEquals(1, repository.findRun(result.saved().runId()).orElseThrow().reports().size());
        assertEquals(6, credentialsSentTo.size());
        assertTrue(credentialsSentTo.stream().noneMatch(uri -> uri.contains("signature")));
        assertTrue(requests.isEmpty(), "The attempt should be resolved exactly once.");
    }

    @Test
    void capturesLatestAttemptOnlyOnceAndRejectsMissingBundleWithoutDownloading() throws Exception {
        expect("runs/200", ok(runResponse(3)));
        expect("runs/200/attempts/3/jobs?per_page=100&page=1", ok(jobs(3, 300, "build")));
        expect("runs/200/artifacts?per_page=100&name=ci-triage-v1-200-3-build&page=1", ok("{\"artifacts\":[]}"));
        var repository = new SqliteRunRepository(temp.resolve("triage.sqlite"));
        assertTrue(assertThrows(IOException.class, () -> new GitHubImporter(client()).save(latest(), repository))
                .getMessage().contains("Missing triage bundle"));
        assertTrue(requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void authenticationPermissionAndMissingRunErrorsAreActionableAndSuppressTheResponseBody(int status) throws Exception {
        expect("runs/200", json(status, Map.of(), "{\"message\":\"" + TOKEN + "\"}"));
        IOException failure = assertThrows(IOException.class, () -> client().run(latest()));
        assertTrue(failure.getMessage().contains("HTTP " + status));
        assertTrue(failure.getMessage().contains(status == 404 ? "run ID or attempt" : "GITHUB_TOKEN"));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertTrue(delays.isEmpty());
    }

    @Test
    void honorsRetryAfterAndBoundsRetriesForTransientErrors() throws Exception {
        expect("runs/200", json(429, Map.of("Retry-After", List.of("3")), "{}"));
        expect("runs/200", json(503, Map.of("Retry-After", List.of("4")), "{}"));
        expect("runs/200", ok(runResponse(1)));
        assertEquals(1, client().run(latest()).attempt());
        assertEquals(List.of(3000L, 4000L), delays);
        assertTrue(requests.isEmpty());
    }

    @Test
    void rateLimitResetTakesPrecedenceOverShorterRetryAfter() throws Exception {
        expect("runs/200", json(403, Map.of("X-RateLimit-Remaining", List.of("0"), "Retry-After", List.of("1"),
                "X-RateLimit-Reset", List.of(Long.toString(CLOCK.instant().getEpochSecond() + 120))), "{}"));
        String message = assertThrows(IOException.class, () -> client().run(latest())).getMessage();
        assertTrue(message.contains("rate limit"));
        assertTrue(message.contains("120 seconds"));
        assertTrue(delays.isEmpty());
    }

    @Test
    void secondaryRateLimitWithoutHeadersStopsWithAtLeastAMinuteOfBackoff() throws Exception {
        expect("runs/200", json(403, Map.of(), "{\"message\":\"Secondary rate limit exceeded\"}"));
        assertTrue(assertThrows(IOException.class, () -> client().run(latest())).getMessage().contains("60 seconds"));
        assertTrue(delays.isEmpty());
    }

    @Test
    void timeoutsAreRetriedAndNeverEchoInputUrlOrCredential() throws Exception {
        for (int i = 0; i < 3; i++) {
            requests.add(new Step(API + "runs/200", null, new HttpTimeoutException(TOKEN + " " + SIGNED_DOWNLOAD)));
        }
        String message = assertThrows(IOException.class, () -> client().run(latest())).getMessage();
        assertTrue(message.contains("Timed out"));
        assertFalse(message.contains(TOKEN));
        assertFalse(message.contains("mock-secret"));
        assertEquals(List.of(1000L, 2000L), delays);
        assertTrue(requests.isEmpty());
    }

    @Test
    void expiredArtifactDownloadShowsRetentionGuidance() throws Exception {
        var run = scenario(200, 2, 300, "build", "success", evidence(GitHubImporterTest.PASS_LOG, null));
        expect("artifacts/900/zip", json(410, Map.of(), "{}"));
        assertTrue(assertThrows(IOException.class, () -> client().download(run.run, run.artifacts.get(0),
                temp.resolve("archive.zip"))).getMessage().contains("has expired"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://storage.example.invalid/bundle?signature=mock-secret",
            "https://name@storage.example.invalid/archive?signature=mock-secret", "//storage.example.invalid/zip",
            "https://storage.example.invalid:444/archive"})
    void rejectsInsecureOrMalformedArtifactRedirectsWithoutExposingThem(String location) throws Exception {
        var run = scenario(200, 2, 300, "build", "success", evidence(GitHubImporterTest.PASS_LOG, null));
        expect("artifacts/900/zip", redirect(location));
        String error = assertThrows(IOException.class, () -> client().download(run.run, run.artifacts.get(0),
                temp.resolve("archive.zip"))).getMessage();
        assertTrue(error.contains("HTTPS artifact download link"));
        assertFalse(error.contains(location));
        assertTrue(requests.isEmpty());
    }

    @Test
    void doesNotFollowFurtherRedirectsFromSignedStorage() throws Exception {
        var run = scenario(200, 2, 300, "build", "success", evidence(GitHubImporterTest.PASS_LOG, null));
        expect("artifacts/900/zip", redirect(SIGNED_DOWNLOAD));
        requests.add(new Step(SIGNED_DOWNLOAD, redirect("https://other.example.invalid/zip"), null));
        String error = assertThrows(IOException.class, () -> client().download(run.run, run.artifacts.get(0),
                temp.resolve("archive.zip"))).getMessage();
        assertTrue(error.contains("HTTP 302"));
        assertFalse(error.contains(SIGNED_DOWNLOAD));
        assertTrue(requests.isEmpty());
    }

    @Test
    void missingTokenFailsBeforeSendingAnyRequest() {
        assertTrue(assertThrows(IOException.class, () -> new GitHubRestClient(null)).getMessage().contains("GITHUB_TOKEN"));
        String error = assertThrows(IOException.class, () -> new GitHubRestClient("", this::exchange, CLOCK,
                delays::add)).getMessage();
        assertTrue(error.contains("GITHUB_TOKEN"));
        assertTrue(credentialsSentTo.isEmpty());
    }

    private GitHubRestClient client() throws IOException {
        return new GitHubRestClient(TOKEN, this::exchange, CLOCK, delays::add);
    }

    private GitHubRestClient.Reply exchange(URI uri, Map<String, String> headers, int limit) throws IOException {
        Step step = requests.removeFirst();
        assertEquals(step.uri(), uri.toString());
        if (uri.getHost().equals("api.github.com")) {
            assertEquals("Bearer " + TOKEN, headers.get("Authorization"));
            assertEquals("application/vnd.github+json", headers.get("Accept"));
            assertEquals("2026-03-10", headers.get("X-GitHub-Api-Version"));
            credentialsSentTo.add(uri.toString());
        } else {
            assertTrue(headers.isEmpty());
            assertEquals(BundleArchive.MAX_DOWNLOAD_BYTES, limit);
        }
        if (step.error() != null) {
            throw step.error();
        }
        return step.response();
    }

    private void expect(String path, GitHubRestClient.Reply response) {
        requests.add(new Step(API + path, response, null));
    }

    private static GitHubImporter.Request latest() {
        return new GitHubImporter.Request("example/project", 200, OptionalInt.empty(), OptionalLong.empty());
    }

    private static String runResponse(int attempt) {
        return """
                {"id":200,"repository":{"id":101,"full_name":"example/project"},
                 "run_attempt":%d,"name":"Java CI","status":"completed","event":"pull_request","head_sha":"%s"}
                """.formatted(attempt, GitHubImporterTest.TRIGGER);
    }

    private static String jobs(int attempt, long job, String name) {
        return """
                {"jobs":[{"id":%d,"run_id":200,"run_attempt":%d,"name":"%s","status":"completed",
                 "conclusion":"success","started_at":"2025-04-01T10:00:00Z","completed_at":"2025-04-01T10:01:00Z"}]}
                """.formatted(job, attempt, name);
    }

    private static String artifacts(String name, long id, boolean expired) {
        return """
                {"artifacts":[{"id":%d,"name":"%s","expired":%s,"size_in_bytes":1000,
                 "created_at":"2025-04-01T10:00:45Z","workflow_run":{"id":200,"repository_id":101}}]}
                """.formatted(id, name, expired);
    }

    private static GitHubRestClient.Reply ok(String body) {
        return json(200, Map.of(), body);
    }

    private static GitHubRestClient.Reply json(int status, Map<String, List<String>> headers, String body) {
        return bytes(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static GitHubRestClient.Reply redirect(String location) {
        return bytes(302, Map.of("Location", List.of(location)), new byte[0]);
    }

    private static GitHubRestClient.Reply bytes(int status, Map<String, List<String>> headers, byte[] body) {
        return new GitHubRestClient.Reply(status, HttpHeaders.of(headers, (key, value) -> true), body);
    }

    private record Step(String uri, GitHubRestClient.Reply response, IOException error) {
    }
}
