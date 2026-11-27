# Compare runs and prioritize investigation

Build the JAR with `mvn clean verify` and run these commands from the project root. The example creates a separate database under `target` for each invocation of the setup.

```powershell
$fixtures = 'src/test/resources/fixtures/comparison'
$demo = New-Item -ItemType Directory -Path (Join-Path 'target' ('comparison-demo-' + [guid]::NewGuid()))
$db = Join-Path $demo.FullName 'triage.sqlite'

java -jar target/ci-failure-triage.jar import-junit "$fixtures/baseline.xml" --db $db --run-id baseline --metadata "$fixtures/baseline.properties"
java -jar target/ci-failure-triage.jar import-junit "$fixtures/current.xml" --db $db --run-id current --metadata "$fixtures/current.properties"
java -jar target/ci-failure-triage.jar import-log "$fixtures/current.log" --db $db --run-id current --group

java -jar target/ci-failure-triage.jar compare-runs current --db $db
java -jar target/ci-failure-triage.jar compare-runs current --baseline baseline --db $db

$incompatible = Join-Path $demo.FullName 'incompatible.properties'
(Get-Content -LiteralPath "$fixtures/baseline.properties" -Raw).Replace('OS=Linux', 'OS=Windows') |
    Set-Content -LiteralPath $incompatible -Encoding UTF8
java -jar target/ci-failure-triage.jar import-junit "$fixtures/baseline.xml" --db $db --run-id incompatible --metadata $incompatible
java -jar target/ci-failure-triage.jar compare-runs current --baseline incompatible --db $db

java -jar target/ci-failure-triage.jar import-junit "$fixtures/current.xml" --db $db --run-id unknown-metadata
java -jar target/ci-failure-triage.jar import-log "$fixtures/current.log" --db $db --run-id unknown-metadata
java -jar target/ci-failure-triage.jar compare-runs unknown-metadata --db $db
```

The baseline records passes for A, B, and C. In the current report, A is `NEW_FAILURE`, B passes, D is `UNSEEN_TEST_FAILURE`, and C is `NOT_OBSERVED_NOW`. The commits differ while the execution configuration matches. The saved log puts a Maven dependency-resolution error first and shows three assertion occurrences across two named tests. The requested `incompatible` baseline is excluded for its OS. Both unavailable comparisons still report priorities.

| Exit | Meaning |
| --- | --- |
| `0` | Analysis completed. An unavailable comparison or ineligible explicit baseline is a valid result. |
| `1` | Database error, missing database, or missing current or explicitly requested baseline run. |
| `2` | Invalid arguments. |

## Execution metadata

Use `--metadata <path>` with `--db` on either importer. A metadata file uses UTF-8 and one `KEY=value` per line. The first `=` separates the key and value. Surrounding space is trimmed. Blank lines and full-line `#` or `!` comments are accepted. A leading UTF-8 BOM is optional. Backslashes, internal whitespace, and inline `#` characters are literal. Use the exact uppercase keys below and omit unknown fields. Unknown keys, duplicates, blank values, and the placeholder `unknown` are errors.

| Keys | Values |
| --- | --- |
| `REPOSITORY`, `WORKFLOW`, `JOB`, `ENVIRONMENT`, `OS`, `ARCHITECTURE`, `TOOLCHAIN` | Explicit identifiers or labels. |
| `DEPENDENCY_FINGERPRINT`, `TEST_COMMAND`, `TEST_CONFIGURATION` | Explicit dependency fingerprint, command, and configuration identifier. Include suite selection, shards, and other relevant inputs in the configuration identifier. |
| `EXECUTED_AT`, `COMPLETED_AT` | Execution start and completion as ISO-8601 timestamps with offsets, for example `2025-01-02T09:00:00Z`. Completion may equal or follow start. |
| `CONCLUSION` | `SUCCESS`, `FAILURE`, `CANCELLED`, `TIMED_OUT`, `SKIPPED`, or `NEUTRAL`. |
| `COMMIT`, `ATTEMPT` | Commit identifier and positive 32-bit attempt number. |

Metadata can be filled across imports for the same run. A value shared by the file, a CLI flag, or saved metadata must agree exactly as text. Conflicts and impossible merged timestamps abort the import. GitHub bundle imports use the producer manifest for the tested configuration and the selected job's API timestamps and conclusion. Import time records when the artifact was saved and plays no part in execution chronology. See [SQLite storage](STORAGE.md) and [GitHub import](GITHUB_IMPORT.md#import-checks-and-saved-evidence).

## Baseline eligibility

An eligible baseline has an explicit `SUCCESS` conclusion and completion strictly before the current `EXECUTED_AT`. It has at least one passed JUnit execution, zero failed or errored JUnit executions, and zero saved log failure findings. Skipped tests may coexist with passes. The policy treats every saved finding, including `UNKNOWN`, as contradictory evidence for baseline eligibility.

Both runs need known, exact matches for repository, workflow, job, environment, OS, architecture, toolchain, dependency fingerprint, test command, and test configuration. Missing or `unknown` values disqualify the candidate. Commit and attempt are informational. Even when their values differ, the execution configuration can match.

Automatic selection chooses the latest eligible completion instant. Equal instants are ordered by case-sensitive run ID using Java string order. The report lists exclusions by run ID, including eligible candidates that rank behind the selected one. `--baseline` evaluates just the requested candidate and displays its eligibility reasons. An explicit request for the current run is ineligible.

## Test matching

The matcher uses the reported class and test name, with the full suite path when both reports supply it. An incomplete suite path permits a match only when the known class and name identify one possible execution in each direction. An incomplete class identity, repeated execution, or other ambiguity can prevent a reliable match. Each execution is retained and evaluated individually. Evidence references display the source filename and its approximate XML position or exact log line range.

| Status | Observation |
| --- | --- |
| `NEW_FAILURE` | One matching execution passed in the baseline and reports failure or error now. |
| `UNSEEN_TEST_FAILURE` | The current failing identity is sufficiently known and has no possible baseline match. |
| `NO_PRIOR_PASS` | The matching baseline execution was skipped or otherwise supplies no pass. |
| `AMBIGUOUS` | Incomplete or repeated identities prevent a unique match. |
| `NOT_OBSERVED_NOW` | A uniquely identifiable baseline execution has no possible current observation. Its current outcome is unknown. |
| `UNAVAILABLE` | A current failure is shown without a compatible baseline. |

## Investigation priorities

The CLI ranks the following tiers in order. Ties retain the saved artifact and observation order within the tier.

1. Build or setup failures supported by `maven-dependency-resolution`, `java-compiler`, or `maven-compilation` findings.
2. Confirmed new JUnit failures.
3. Diagnostic groups covering multiple distinct reported test identities.
4. Other current JUnit failures or errors.
5. Other saved log groups.

Each item includes a reason and references to stored evidence. Groups show total occurrences, distinct named tests, and occurrences with unknown test identity. An exact class and test name can link a log finding to an unambiguous saved JUnit identity. The absence of a similar diagnostic in a prior log supplies no JUnit failure status. A shared cause requires additional evidence beyond diagnostic similarity or a changed commit.

Comparison uses one read-only database snapshot. It reads eligibility facts for candidates and loads evidence for the current and selected runs from SQLite, including after removal of the original source files. Output preserves original log line numbers and masks recognized home-directory prefixes. XML locations remain approximate SAX diagnostic positions. Baseline selection is limited to imported evidence and explicit metadata.
