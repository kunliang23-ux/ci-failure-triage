# Import a GitHub Actions job bundle

Day 7 imports the evidence bundle from one completed GitHub.com Actions job and saves it in the local SQLite database. Build the Java 17 JAR with `mvn clean verify`. Use the numeric ID from the workflow run URL in `--run-id`.

```powershell
New-Item -ItemType Directory -Force -Path 'data' | Out-Null
java -jar target/ci-failure-triage.jar import-github OWNER/REPO --run-id RUN_ID --db data/triage.sqlite
java -jar target/ci-failure-triage.jar import-github OWNER/REPO --run-id RUN_ID --attempt ATTEMPT --job-id JOB_ID --db data/triage.sqlite
```

Replace the uppercase placeholders with your repository and positive decimal IDs. Omitting `--attempt` resolves the latest attempt once. Wait for it to complete or pick a completed earlier attempt. Omitting `--job-id` requires exactly one job in the selected attempt. When there are multiple jobs, find the numeric job ID in the job URL. The selected job must have a unique API name equal to its workflow job key. Matrix jobs, reused workflow job names, and legacy uploads outside the producer convention are outside this contract.

## Credentials and REST behavior

Set `GITHUB_TOKEN` in the shell that launches Java. Use a fine-grained personal access token restricted to the configured repository with **Actions: Read-only**. A GitHub App installation or user token with equivalent repository access also works. GitHub may return `404` for private resources when the token lacks access. [GitHub Actions artifact permissions](https://docs.github.com/en/rest/actions/artifacts?apiVersion=2026-03-10#download-an-artifact) · [GitHub REST troubleshooting](https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api)

The client sends `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2026-03-10`, a user agent, and a bearer token to `api.github.com`. It resolves the run or explicit attempt with the workflow-run API, lists jobs with the attempt-specific endpoint, then lists matching artifact names with the run-scoped endpoint. It reads paginated results before selecting a job and artifact. The client's bearer token, response error bodies, and signed artifact link are kept out of console output and SQLite. The documented artifact redirect is followed once over HTTPS without API headers. Import a bundle again to get a fresh link if the signed download expires. [REST API versions](https://docs.github.com/en/rest/about-the-rest-api/api-versions) · [Workflow runs](https://docs.github.com/en/rest/actions/workflow-runs#get-a-workflow-run-attempt) · [Attempt jobs](https://docs.github.com/en/rest/actions/workflow-jobs#list-jobs-for-a-workflow-run-attempt) · [Artifact downloads](https://docs.github.com/en/rest/actions/artifacts?apiVersion=2026-03-10#download-an-artifact)

Requests have a 10-second connection timeout and a 30-second response deadline. A request has up to three total attempts for connection failures, timeouts, selected server errors, and rate limits. The client uses `Retry-After` and the primary rate-limit reset, with the longer delay taking precedence. When GitHub gives no rate-limit timing, it reports a 60-second wait. For delays over 10 seconds it exits with guidance so the user can retry later. REST listings allow up to 100 pages and up to 8 MiB of JSON per response. [GitHub REST pagination](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api) · [GitHub REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)

## Producer and bundle format

The push/PR workflow runs `mvn --batch-mode clean verify`. The producer probes the checkout and Maven toolchain, then tees Maven's combined stdout and stderr as bytes to `.ci-triage-staging/build.log`. It uses Maven's exit status for the build step. The evidence collection step also runs after a failed Maven step, since Surefire may have written reports or the build log may be the only evidence. Both staging and upload directories are outside `target/` and ignored by Git. The upload step runs after collection succeeds, including on a failing build. Cancelled jobs or failures before the build starts may have no bundle.

The artifact name is `ci-triage-v1-RUN_ID-ATTEMPT-JOB_KEY`. The ZIP contains `manifest.json`, the available `build.log`, and available regular `target/surefire-reports/TEST-*.xml` and `target/failsafe-reports/TEST-*.xml` files copied under `reports/surefire/` and `reports/failsafe/`. Use portable Maven report names such as `TEST-org.example.SampleTest.xml`. The archive root is the contents of the upload directory. The manifest is strict UTF-8 JSON.

| Field | Required value or meaning |
| --- | --- |
| `version` | Integer `1`. |
| `repository`, `repository_id` | GitHub repository name and numeric identity. |
| `run_id`, `attempt`, `workflow`, `job` | Numeric external run ID, attempt number, workflow name, and logical job key. |
| `tested_commit` | Full SHA from `git rev-parse --verify HEAD` before the build, when available. |
| `triggering_head_commit` | PR head SHA for pull requests. For a push or dispatch, the event SHA. Omitted when unavailable. |
| `execution` | Known `environment`, `test_command`, `test_configuration`, `os`, `architecture`, `toolchain`, and `dependency_fingerprint`. |
| `files` | Each evidence file's archive `path`, `kind` (`junit` or `log`), byte `size`, and lowercase `sha256`. The manifest itself is outside this inventory. |

On a pull request, the tested SHA can be the checkout of GitHub's synthetic merge commit. The PR head SHA identifies the contributor's triggering commit. The manifest preserves both facts. The importer checks the triggering head against the run SHA when both are available. For a pull request, the API SHA may also match the tested merge SHA. It always takes the tested commit from the manifest. [GitHub pull request event and checkout SHAs](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request)

The producer records the declared runner label, detected OS and machine architecture, and the versions of Maven and Java when the version probe supplies them. The optional `maven-test-jars-v1:sha256:…` fingerprint hashes a compact ASCII JSON array of ordered pairs of Maven-repository-relative JAR paths and their content SHA-256 values. The order comes from Surefire's observed test classpath in the copied reports. The project class directories are excluded. The fingerprint is available when the reports agree on the classpath and its external JARs can be read inside the default local Maven repository. Missing reports, alternate cache paths, and unresolved or inconsistent classpaths leave the fingerprint unknown. It describes the observed test JARs. Maven plugin artifacts and the system image have separate roles in the execution.

## Import checks and saved evidence

- The importer checks the repository, run, attempt, workflow and unique logical job against the artifact name and manifest. When GitHub provides artifact origin IDs, they must identify the selected repository and run. A supplied artifact creation time must fit within the available job timestamps. Missing and expired bundles are reported explicitly. Multiple matching artifacts are rejected, including when one is expired.
- ZIPs are limited to 32 MiB downloaded, 256 entries, 64 MiB total uncompressed, 16 MiB per evidence file, and 256 KiB for the manifest. The importer rejects unsafe or unexpected paths, symlinks and other special files, encrypted or unsupported methods, case collisions, inconsistent sizes and CRCs, and inventory or SHA-256 mismatches. It verifies the GitHub artifact SHA-256 when supplied. Extraction uses a temporary directory that is removed before saving. The importer parses content as data.
- The selected **job's** `started_at` and `completed_at` map to `EXECUTED_AT` and `COMPLETED_AT`. Its `conclusion` maps to the existing `SUCCESS`, `FAILURE`, `CANCELLED`, `TIMED_OUT`, `SKIPPED`, or `NEUTRAL` values. Unsupported or missing values remain unknown. Timestamps are stored in UTC. The run's own conclusion and import time are separate. Tested commit and configuration come from the manifest. Conflicting known metadata aborts the import.
- The local run ID has the form `github-REPOSITORY_ID-RUN_ID-ATTEMPT-JOB_ID`. SQLite stores the GitHub origin identifiers, manifest bytes and hash, and original XML/log bytes and hashes under their archive entry names. It saves outcomes, findings, groups, and evidence in one transaction. Identical evidence is deduplicated by kind and hash within the job, and every declared entry name remains in the manifest. A different run, attempt or job keeps its own observations. Reimporting the same bundle keeps its first import time and reports `Storage: bundle already saved.`
- A log-only job has unavailable JUnit evidence. A valid empty XML suite has zero observed executions. A bundle with neither a report nor a nonblank log is rejected. The import command returns success for accepted evidence of a failed job. View the saved results with `show-run`. The console masks recognizable home-directory prefixes and displays evidence filenames. A local database contains the original evidence and can include data supplied by the workflow and tests. See the [storage contract](STORAGE.md), [XML contract](JUNIT_XML_IMPORT.md), and [log contract](LOG_IMPORT.md).

| Exit | Meaning |
| --- | --- |
| `0` | Saved or already saved the selected bundle. Stdout displays the local run ID and GitHub identifiers. |
| `1` | API, selection, download, evidence, parsing, or database failure. The diagnostic goes to stderr and no partial observations are saved. |
| `2` | Invalid arguments. The diagnostic and help guidance go to stderr. |

## Demonstrate with real runs after push

Wait for a passing **Java CI** run with an uploaded triage bundle. On the default branch, manually dispatch **Controlled CI failure** from the Actions tab and wait for its failing job and successful upload. That workflow adds one deliberately failing JUnit test to its disposable checkout and records `maven-verify-with-controlled-failure-v1` as its configuration. Its workflow and job keys also differ. See [baseline eligibility](COMPARISON.md#baseline-eligibility) and [manual workflow dispatch](https://docs.github.com/en/actions/managing-workflow-runs/manually-running-a-workflow).

Run these PowerShell commands from the repository after building the JAR. Enter the numeric workflow run IDs when prompted.

```powershell
$repo = 'OWNER/REPO'
$passingRun = Read-Host 'Passing workflow run ID'
$failingRun = Read-Host 'Controlled-failure workflow run ID'
$jar = 'target/ci-failure-triage.jar'
$db = 'data/triage.sqlite'
New-Item -ItemType Directory -Force -Path 'data' | Out-Null
$token = Read-Host 'GitHub Actions read token' -AsSecureString
$env:GITHUB_TOKEN = [System.Net.NetworkCredential]::new('', $token).Password
$token.Dispose()
try {
    $passingOutput = & java -jar $jar import-github $repo --run-id $passingRun --db $db
    if ($LASTEXITCODE -ne 0) { throw 'Passing run import failed.' }
    $passingOutput
    $passingLocal = ($passingOutput -match '^Run ID: ') -replace '^Run ID: '

    $failingOutput = & java -jar $jar import-github $repo --run-id $failingRun --db $db
    if ($LASTEXITCODE -ne 0) { throw 'Failing run import failed.' }
    $failingOutput
    $failingLocal = ($failingOutput -match '^Run ID: ') -replace '^Run ID: '

    & java -jar $jar import-github $repo --run-id $failingRun --db $db
} finally {
    Remove-Item Env:GITHUB_TOKEN -ErrorAction SilentlyContinue
}
& java -jar $jar runs --db $db
& java -jar $jar show-run $passingLocal --db $db --group
& java -jar $jar show-run $failingLocal --db $db --group
```

The repeated import should report `Storage: bundle already saved.` and retain the same local run ID. The reopened failing run should contain the controlled assertion and original numbered Maven evidence. If either run has been rerun, append its `--attempt` to both imports so the demonstration selects the same attempt. Ordinary tests use synthetic archives and mocked HTTP. This live demonstration requires a workflow run with the new producer and a repository-scoped token.
