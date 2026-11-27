package org.citriage.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

/** REST transport for GitHub.com. Only requests constructed for the API origin carry the token. */
final class GitHubRestClient implements GitHubClient {
    private static final String API = "https://api.github.com/repos/";
    private static final int MAX_API_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PAGES = 100;
    private static final int MAX_TRIES = 3;
    private static final long MAX_RETRY_SECONDS = 10;
    private static final Pattern NEXT_PAGE = Pattern.compile("<[^>]+>\\s*;\\s*rel=\"next\"");

    // Request-local only. Neither headers nor response bodies are included in errors.
    private final Map<String, String> apiHeaders;
    private final Transport transport;
    private final Clock clock;
    private final Pause pause;

    GitHubRestClient(String token) throws IOException {
        this(headers(token), new JdkHttpTransport(), Clock.systemUTC(), Thread::sleep);
    }

    GitHubRestClient(String token, Transport transport, Clock clock, Pause pause) throws IOException {
        this(headers(token), transport, clock, pause);
    }

    private GitHubRestClient(Map<String, String> apiHeaders, Transport transport, Clock clock, Pause pause) {
        this.apiHeaders = apiHeaders;
        this.transport = transport;
        this.clock = clock;
        this.pause = pause;
    }

    private static Map<String, String> headers(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IOException("Set GITHUB_TOKEN to a GitHub token with Actions read access to OWNER/REPO.");
        }
        if (!token.matches("[!-~]+")) {
            throw new IOException("GITHUB_TOKEN must contain a single token without whitespace or control characters.");
        }
        return Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2026-03-10",
                "User-Agent", "ci-failure-triage", "Authorization", "Bearer " + token);
    }

    @Override
    public WorkflowRun run(GitHubImporter.Request request) throws IOException {
        String endpoint = runPath(request.workflowRunId());
        if (request.attempt().isPresent()) {
            endpoint += "/attempts/" + request.attempt().getAsInt();
        }
        JsonObject run = json(apiGet(request.repository(), endpoint, "resolving the workflow run or attempt"));
        try {
            JsonObject repository = JsonFields.object(run.get("repository"));
            return new WorkflowRun(JsonFields.number(run, "id", 1), JsonFields.text(repository, "full_name"),
                    JsonFields.number(repository, "id", 1), JsonFields.attempt(run, "run_attempt"),
                    JsonFields.text(run, "name"), JsonFields.text(run, "status"), JsonFields.optionalText(run, "head_sha"),
                    JsonFields.optionalText(run, "event").filter("pull_request"::equals).isPresent());
        } catch (IOException exception) {
            throw invalidResponse("workflow run", exception);
        }
    }

    @Override
    public List<Job> jobs(WorkflowRun run) throws IOException {
        String endpoint = runPath(run.id()) + "/attempts/" + run.attempt() + "/jobs?per_page=100";
        return pages(run.repository(), endpoint, "jobs", item -> {
            OptionalInt attempt = JsonFields.absent(item.get("run_attempt")) ? OptionalInt.empty()
                    : OptionalInt.of(JsonFields.attempt(item, "run_attempt"));
            return new Job(JsonFields.number(item, "id", 1), JsonFields.number(item, "run_id", 1), attempt,
                    JsonFields.text(item, "name"), JsonFields.text(item, "status"),
                    JsonFields.optionalText(item, "conclusion"), JsonFields.timestamp(item, "started_at"),
                    JsonFields.timestamp(item, "completed_at"));
        });
    }

    @Override
    public List<Artifact> artifacts(WorkflowRun run, String name) throws IOException {
        // The importer constructs a portable artifact name. It cannot introduce query delimiters.
        String endpoint = runPath(run.id()) + "/artifacts?per_page=100&name=" + name;
        return pages(run.repository(), endpoint, "artifacts", item -> {
            JsonObject origin = JsonFields.absent(item.get("workflow_run")) ? new JsonObject()
                    : JsonFields.object(item.get("workflow_run"));
            return new Artifact(JsonFields.number(item, "id", 1), JsonFields.text(item, "name"),
                    JsonFields.bool(item, "expired"), JsonFields.number(item, "size_in_bytes", 0),
                    JsonFields.optionalId(origin, "id"), JsonFields.optionalId(origin, "repository_id"),
                    JsonFields.timestamp(item, "created_at"), JsonFields.optionalText(item, "digest"));
        });
    }

    @Override
    public void download(WorkflowRun run, Artifact artifact, Path destination) throws IOException {
        String operation = "requesting the artifact ZIP";
        URI endpoint = apiUri(run.repository(), "/actions/artifacts/" + artifact.id() + "/zip");
        Reply response = get(endpoint, apiHeaders, MAX_API_BYTES, operation, true);
        requireStatus(response, 302, operation, true);
        URI download = secureDownload(response.headers().firstValue("Location").orElse(""));

        // GitHub.com hands off to signed storage. Never send the API headers or follow its redirects.
        operation = "downloading the artifact ZIP";
        Reply zip = get(download, Map.of(), BundleArchive.MAX_DOWNLOAD_BYTES, operation, false);
        requireStatus(zip, 200, operation, true);
        try {
            Files.write(destination, zip.body(), StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new IOException("Cannot write the downloaded artifact to temporary storage.", exception);
        }
    }

    private <T> List<T> pages(String repository, String endpoint, String field, ItemReader<T> reader) throws IOException {
        var items = new ArrayList<T>();
        var ids = new HashSet<Long>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Reply response = apiGet(repository, endpoint + "&page=" + page, "listing workflow " + field);
            try {
                for (var value : JsonFields.array(json(response), field)) {
                    JsonObject item = JsonFields.object(value);
                    if (!ids.add(JsonFields.number(item, "id", 1))) {
                        throw new IOException("Repeated ID across pages. Retry the import after the run settles.");
                    }
                    items.add(reader.read(item));
                }
            } catch (IOException exception) {
                throw invalidResponse(field, exception);
            }
            // These endpoints use page numbers. Rebuild the same scoped request rather than follow arbitrary URLs.
            if (response.headers().allValues("Link").stream().noneMatch(link -> NEXT_PAGE.matcher(link).find())) {
                return List.copyOf(items);
            }
        }
        throw new IOException("Too many pages of GitHub workflow " + field + " to select a bundle.");
    }

    private Reply apiGet(String repository, String endpoint, String operation) throws IOException {
        Reply response = get(apiUri(repository, endpoint), apiHeaders, MAX_API_BYTES, operation, true);
        requireStatus(response, 200, operation, false);
        return response;
    }

    private Reply get(URI uri, Map<String, String> headers, int limit, String operation, boolean api) throws IOException {
        for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
            Reply response;
            try {
                response = transport.get(uri, headers, limit);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while " + operation + ".");
            } catch (JdkHttpTransport.BodyTooLarge exception) {
                throw new IOException("Response is too large while " + operation + ".");
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid HTTP request while " + operation + ".");
            } catch (IOException exception) {
                if (attempt == MAX_TRIES) {
                    String reason = exception instanceof HttpTimeoutException ? "Timed out" : "Connection failed";
                    throw new IOException(reason + " while " + operation + ". Check your connection and retry.");
                }
                waitSeconds(attempt, operation);
                continue;
            }

            boolean limited = rateLimited(response, api);
            if (!limited && !retryable(response.status())) {
                return response;
            }
            long delay = retryAfter(response.headers(), limited, attempt);
            if (attempt == MAX_TRIES || delay > MAX_RETRY_SECONDS) {
                String reason = limited ? "GitHub rate limit reached" : "Service temporarily unavailable";
                throw new IOException(reason + " while " + operation + ". Retry after " + delay + " seconds.");
            }
            waitSeconds(delay, operation);
        }
        throw new AssertionError("HTTP retry loop did not return.");
    }

    private static URI apiUri(String repository, String endpoint) {
        return URI.create(API + repository + endpoint);
    }

    private static URI secureDownload(String location) throws IOException {
        try {
            URI uri = URI.create(location);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Never include a Location header or URI exception in output.
        }
        throw new IOException("GitHub did not provide a usable HTTPS artifact download link.");
    }

    private static void requireStatus(Reply response, int expected, String operation, boolean artifact) throws IOException {
        int status = response.status();
        if (status == expected) {
            return;
        }
        String action = switch (status) {
            case 401 -> "Check that GITHUB_TOKEN is valid.";
            case 403 -> operation.startsWith("downloading")
                    ? "The download link may have expired. Run the import again for a fresh link."
                    : "Check that GITHUB_TOKEN has Actions read access to the configured repository.";
            case 404 -> artifact ? "The artifact is missing or no longer accessible. Check its retention and token access."
                    : "Check OWNER/REPO, the run ID or attempt, and the token's repository access.";
            case 410 -> artifact ? "The artifact has expired. Select an available bundle or rerun the workflow."
                    : "Check whether the requested GitHub REST API version is still supported.";
            default -> "Check the workflow run and try again.";
        };
        throw new IOException("HTTP " + status + " while " + operation + ". " + action);
    }

    private static boolean rateLimited(Reply response, boolean api) {
        return response.status() == 429 || response.status() == 403 && api
                && (response.headers().firstValue("Retry-After").isPresent()
                || response.headers().firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent()
                || new String(response.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("rate limit"));
    }

    private long retryAfter(HttpHeaders headers, boolean limited, int attempt) {
        long delay = headers.firstValue("Retry-After").map(this::headerDelay).orElse(-1L);
        if (limited && headers.firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent()) {
            long reset = headers.firstValue("X-RateLimit-Reset").map(GitHubRestClient::secondsValue).orElse(-1L);
            if (reset >= 0) {
                delay = Math.max(delay, Math.max(0, reset - clock.instant().getEpochSecond()));
            }
        }
        return delay >= 0 ? Math.max(attempt, delay) : limited ? 60 : attempt;
    }

    private long headerDelay(String value) {
        long seconds = secondsValue(value);
        if (seconds >= 0) {
            return seconds;
        }
        try {
            Instant when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), when);
            return delay.isNegative() ? 0 : delay.toSeconds() + (delay.toNanosPart() > 0 ? 1 : 0);
        } catch (java.time.DateTimeException exception) {
            return -1;
        }
    }

    private static long secondsValue(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void waitSeconds(long seconds, String operation) throws IOException {
        try {
            pause.sleep(seconds * 1000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while " + operation + ".");
        }
    }

    private static boolean retryable(int status) {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static JsonObject json(Reply response) throws IOException {
        try {
            return JsonFields.read(response.body());
        } catch (IOException exception) {
            throw new IOException("GitHub returned malformed JSON.", exception);
        }
    }

    private static IOException invalidResponse(String subject, IOException exception) {
        return new IOException("Invalid GitHub " + subject + " response. " + exception.getMessage(), exception);
    }

    private static String runPath(long id) {
        return "/actions/runs/" + id;
    }

    record Reply(int status, HttpHeaders headers, byte[] body) {
    }

    @FunctionalInterface
    interface Transport {
        Reply get(URI uri, Map<String, String> headers, int limit) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    private interface ItemReader<T> {
        T read(JsonObject object) throws IOException;
    }
}
