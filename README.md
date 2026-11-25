# CI Failure Triage

A Java portfolio project to help developers answer:

> Why did my tests fail, and what should I investigate first?

**Days 1–6 of the 15-day plan are implemented.** The CLI saves local JUnit and log evidence in SQLite, compares runs with eligible earlier baselines, and explains which current failures to investigate first. Saved runs retain original source bytes and numbered log evidence. GitHub Actions builds and tests on pushes and pull requests. See the [XML input contract](docs/JUNIT_XML_IMPORT.md), [log import contract](docs/LOG_IMPORT.md), [grouping rules](docs/GROUPING.md), [storage contract](docs/STORAGE.md), [baseline comparison](docs/COMPARISON.md), and [roadmap](ROADMAP.md).

## Requirements and versions

- JDK 17, including `java` and `javac`.
- Apache Maven 3.9.x or a newer stable Maven 3 release; the local setup below uses 3.9.16.
- Git for source control and a GitHub account for publishing.
- Internet access for the first Maven dependency download. The built CLI itself works offline.

`pom.xml` targets Java 17 with `maven.compiler.release`. JUnit Jupiter 5.13.4 is test-only. Xerial SQLite JDBC 3.54.1.0 supplies the runtime driver. Maven Shade 3.6.2 bundles it in the executable JAR. The other build plugins are pinned to Clean 3.4.1, Resources 3.3.1, Compiler 3.14.1, Surefire 3.5.4, and JAR 3.4.2. See the [SQLite JDBC release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0), [Maven Shade requirements](https://maven.apache.org/plugins-archives/maven-shade-plugin-3.6.2/plugin-info.html), and [JUnit release notes](https://docs.junit.org/5.13.4/release-notes/).

## Windows PowerShell setup

Open PowerShell and replace the two example paths below with your local repository and JDK 17 directories. These are placeholders, not installation paths. The environment settings apply **to this terminal session**:

```powershell
Set-Location 'C:\path\to\ci-failure-triage'
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
```

If JDK 17 is not installed, follow the [Microsoft OpenJDK installation instructions](https://learn.microsoft.com/en-us/java/openjdk/install), then set `JAVA_HOME` to its installation directory and add its `bin` directory to `PATH`. For a permanent setup, use Windows **Edit environment variables for your account**, then open a new terminal. Use a JDK rather than a JRE because Maven needs a compiler.

If Maven is not installed, this downloads a portable copy into ignored `.tools/`, checks its SHA-512 checksum, and extracts it. It does not change system settings or require an administrator terminal. The version comes from the [official Maven download page](https://maven.apache.org/download.cgi).

```powershell
$ErrorActionPreference = 'Stop'
$toolDirectory = Join-Path (Get-Location) '.tools'
$mavenVersion = '3.9.16'
$mavenDirectory = Join-Path $toolDirectory "apache-maven-$mavenVersion"
if (-not (Test-Path -LiteralPath "$mavenDirectory\bin\mvn.cmd")) {
    New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
    $archiveName = "apache-maven-$mavenVersion-bin.zip"
    $archivePath = Join-Path $toolDirectory $archiveName
    $checksumPath = "$archivePath.sha512"
    $baseUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries"
    Invoke-WebRequest -Uri "$baseUrl/$archiveName" -OutFile $archivePath
    Invoke-WebRequest -Uri "$baseUrl/$archiveName.sha512" -OutFile $checksumPath
    $expectedHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash
    if ($actualHash -ine $expectedHash) { throw 'Maven archive SHA-512 verification failed.' }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolDirectory
}
$env:Path = "$mavenDirectory\bin;$env:Path"
mvn --version
```

Confirm that `mvn --version` reports the JDK 17 you selected. Repeat the session environment commands in a new terminal, or add Maven's `bin` directory to your user `PATH` permanently. An existing Maven installation can be used instead.

## Build, test, and run

From the project root, after setting up `PATH`:

```powershell
# Run all JUnit tests.
mvn test

# Clean, compile, run tests, package the JAR, and complete verification.
mvn clean verify

# Run the packaged CLI.
java -jar target/ci-failure-triage.jar --help
java -jar target/ci-failure-triage.jar --version

# Import an invented example containing all four outcomes.
java -jar target/ci-failure-triage.jar import-junit src/test/resources/fixtures/mixed-results.xml
$LASTEXITCODE # 0: imported, even though some reported tests failed.

# Inspect one real report emitted by this project's own JUnit tests.
java -jar target/ci-failure-triage.jar import-junit target/surefire-reports/TEST-org.citriage.CiTriageCliTest.xml

# A malformed report fails without printing a partial summary.
java -jar target/ci-failure-triage.jar import-junit src/test/resources/fixtures/malformed.xml
$LASTEXITCODE # 1

# A nonexistent report produces a read error.
java -jar target/ci-failure-triage.jar import-junit target/missing-day2.xml
$LASTEXITCODE # 1

# Quote paths containing spaces.
# java -jar target/ci-failure-triage.jar import-junit 'C:\reports\test results.xml'

# Demonstrate an invalid argument and inspect its process exit code.
java -jar target/ci-failure-triage.jar --unknown
$LASTEXITCODE
```

`mvn package` also builds the JAR and runs tests. `mvn test` by itself does not package it. Build output is in `target/`; JUnit text/XML reports are in `target/surefire-reports/`.

For an isolated local Maven cache, use the following commands instead. This is the cache location used for validation in the managed development environment:

```powershell
mvn '-Dmaven.repo.local=.tools/maven-repository' test
mvn '-Dmaven.repo.local=.tools/maven-repository' clean verify
```

In the managed Windows sandbox, validation also sets `$env:MAVEN_BASEDIR = (Get-Location).Path` so Maven's launcher uses this project root directly. This avoids its parent-directory search hitting sandbox restrictions. This session-only setting is optional in an ordinary terminal.

## Understand the Maven phases

These are ordered phases in Maven's default build lifecycle. Calling a later phase runs the earlier phases too; you do not need to invoke all three in sequence.

| Command | Work performed in this project | Output |
| --- | --- | --- |
| `mvn test` | Copy/filter application resources, compile Java, copy test fixtures without filtering, compile tests, and run JUnit through Surefire. | `target/classes/`, `target/test-classes/`, and `target/surefire-reports/`. A new JAR is not created. |
| `mvn package` | Everything through `test`, then packages the application, runtime driver, JDBC services, native resources, and migrations in a runnable JAR. | `target/ci-failure-triage.jar`. Test fixtures and JUnit dependencies are excluded. |
| `mvn verify` | Everything through `package`, then any checks bound to later verification phases. | The packaged JAR after the configured lifecycle checks succeed. |

This POM has no Failsafe integration-test or additional verification-plugin bindings yet. `verify` reaches the later phases, but does not invent extra tests, launch the CLI, or contact GitHub. That is why the packaged CLI is demonstrated separately. `clean` is a separate lifecycle: `mvn clean verify` removes old `target/` output first and rebuilds through `verify`. A plain `mvn test` may leave an older JAR in place; package again before running changed code.

The sequence is `compile -> test -> package -> verify`, with resource and test-compilation phases between them. Surefire tests call the handler/parser as ordinary Java methods. Neither building the JAR nor running these tests calls the application's `main()`. See the [official Maven lifecycle guide](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html).

A synthetic XML fixture can describe failed tests while our parser unit tests pass: the assertions verify that those failed outcomes are imported accurately.

## Day 1 validation results

Validated on November 17, 2026, with Java 17 and checksum-verified Apache Maven 3.9.16:

- Both isolated-cache Maven commands above completed with `BUILD SUCCESS`. Each ran 14 tests, with zero failures, errors, or skipped tests.
- The packaged JAR returned `0` for `--help`, `--version`, and no arguments. Version output was `CI Failure Triage 0.1.0`.
- It returned `2` for `--unknown`, `--help --unknown`, and `--help --version`, with guidance on standard error and no standard output.
- The JAR manifest identifies the CLI entry point. The version resource contains `version=0.1.0`, and the class-file major version is `61` (Java 17).

JUnit results are retained in `target/surefire-reports/`. Packaged process-check results and captured streams are in `target/cli-smoke-results.json` and `target/demo-*.txt`. These are generated validation artifacts, so a subsequent `clean` removes them.

## Earlier partial-importer validation (historical)

On November 17, 2026, with the same JDK/Maven versions and project-local cache, both `mvn --offline test` and `mvn --offline clean verify` passed **49 tests**: 24 CLI cases and 25 parser cases, with zero failures, errors, or skips. The actual invocations also used `--batch-mode --no-transfer-progress` and `-Dmaven.repo.local=.tools/maven-repository`.

Nine separate packaged process checks passed: help, version, no arguments, unknown arguments, the mixed report, malformed XML, a forbidden DOCTYPE, and both real Surefire reports produced by this build. The real reports imported as 24 and 25 passed executions. The malformed/DOCTYPE inputs returned `1` with no standard output; invalid arguments returned `2`.

All five source XML fixtures retained their original file hashes after the process checks, and the mixed report's printed hash matched its original file hash. Current process outputs are retained under `target/` as described above. No additional runtime dependencies were added.

## Completed Day 2 validation results

On November 17, 2026, using Java 17 and Apache Maven 3.9.16, both commands below passed **75 tests** (38 CLI cases and 37 parser cases), with zero failures, errors, or skips. Run them after the Windows JDK setup above:

```powershell
$env:MAVEN_BASEDIR = (Get-Location).Path
& '.\.tools\apache-maven-3.9.16\bin\mvn.cmd' --offline --batch-mode --no-transfer-progress '-Dmaven.repo.local=.tools/maven-repository' test
& '.\.tools\apache-maven-3.9.16\bin\mvn.cmd' --offline --batch-mode --no-transfer-progress '-Dmaven.repo.local=.tools/maven-repository' clean verify
```

The offline flag uses the already populated local dependency cache. A fresh checkout needs the first online dependency download before offline builds can succeed.

Fourteen packaged process checks passed, including the required demonstrations:

| Command arguments after the JAR path | Actual result | Exit |
| --- | --- | --- |
| `import-junit src/test/resources/fixtures/mixed-results.xml` | `Tests: 5 \| passed=2 failed=1 errors=1 skipped=1`, identities, durations, and original failure/error details | `0` |
| `import-junit src/test/resources/fixtures/malformed.xml` | Source-aware XML error on stderr, no stdout | `1` |
| `import-junit target/missing-day2.xml` | `Cannot read report` on stderr, no stdout | `1` |
| A temporarily locked synthetic XML file | Windows read/sharing error on stderr, no stdout | `1` |

Other checks covered passing/nested reports, DOCTYPE rejection, the existing alias, help/version, invalid arguments, and this build's real Surefire reports. All ten XML fixture hashes remained unchanged; earlier fixtures and their README also matched the starting baseline. The mixed report's displayed snapshot hash matched its original file hash.

Captured process results are in `target/cli-smoke-results.json` and `target/day2-*.txt`. The ignored `.tools/day2-validation/` directory retains a copy of the process results and `clean-verify.log` across a later Maven `clean`. GitHub-hosted CI has not been executed from these local changes; it will be triggered by a push or pull request. No local validation checks were blocked.

## Day 4 validation results

`mvn test` and `mvn clean verify` each passed 153 tests with Java 17 and Maven 3.9.16, using the existing local cache in offline mode. The 27 packaged CLI checks covered the grouped fixture, repeatable IDs and ordering, byte-for-byte Day 3 compatibility for eight ungrouped log runs, JUnit import and its `--report` alias, and stdout, stderr, and exit codes. The Maven logs and packaged process captures are in ignored `.tools/day4-validation/`.

## Day 5 validation results

With Java 17 and Maven 3.9.16, `mvn test` and `mvn clean verify` each passed 183 tests. The final builds used the project-local cache in offline mode after the initial dependency download. Twenty-four packaged command checks passed, including saving XML and a grouped log into one run, byte-identical run summaries after repeat imports, and reopening numbered evidence after deleting temporary source copies. Seven compatibility checks matched the Day 4 JAR's exit codes and output streams byte for byte. All 18 checked-in XML and log fixture hashes were verified after the demonstrations. Build logs and captures are in ignored `.tools/day5-validation/`.

## Day 6 validation results

With Java 17 and Maven 3.9.16, `mvn test` and `mvn clean verify` each passed 230 tests. The clean build used an isolated copy of the source to preserve the main checkout's existing build-directory data. Twenty-six packaged command checks covered automatic and explicit selection, incompatible and missing metadata, priorities without a baseline, read-only comparison after removing temporary sources, and version 1 reads and migration. Five compatibility checks matched the Day 5 JAR's output and exit codes byte for byte. Build logs and captures are in ignored `.tools/day6-validation/`.

## CLI behavior

| Arguments | Standard output | Standard error | Exit code |
| --- | --- | --- | --- |
| No arguments or `--help` | Purpose, usage, available options | Empty | `0` |
| `--version` | `CI Failure Triage 0.1.0` | Empty | `0` |
| `import-junit <report-path>` with a supported report | Filename, SHA-256, total/passed/failed/errored/skipped counts, test names, available durations, and outcome details | Empty | `0`, even if reported tests failed |
| `import-junit <report-path>` with unreadable, malformed, or unsupported input | Empty; no partial import | Source/read/parse diagnostic | `1` |
| `--report <path>` | Compatibility alias for `import-junit`, with identical output and exit behavior | As above | As above |
| `import-log <log-path> [--commit <sha>] [--environment <label>]` | Filename, SHA-256, metadata, line count, findings and numbered evidence when available | Empty | `0`, even with failure findings |
| `import-log <log-path> --group` with optional metadata flags | Source and metadata, finding and group totals, representative diagnostics, test identities, and all numbered member evidence | Empty | `0`, even with failure findings |
| `import-log <log-path>` with an unreadable file or invalid UTF-8 | Empty | Read or decoding diagnostic | `1` |
| `import-junit <path> --db <database-path>` or `import-log <path> --db <database-path>` | Run ID, save status, and the imported results. Supply `--run-id <id>` to attach to a specific execution. | Empty | `0` |
| `runs --db <database-path>` | Saved run IDs, import times, metadata, and separate XML and log totals | Empty | `0` |
| `show-run <id> --db <database-path> [--group]` | Stored metadata, JUnit outcomes, and original log evidence | Empty | `0` |
| `compare-runs <current-id> --db <database-path> [--baseline <baseline-id>]` | Baseline selection or exclusion reasons, JUnit comparisons, and priorities with evidence. | Empty | `0`, including comparison unavailable |
| Saved import with an invalid or unreadable `--metadata <path>` | Empty | Metadata file, value, or flag conflict diagnostic | `1` |
| Database error, conflicting metadata, missing database on a query, or missing run | Empty | Database or missing-run diagnostic | `1` |
| Missing or extra arguments, duplicate log flags, or invalid path syntax | Empty | Usage error and `--help` guidance | `2` |
| Any unknown argument, including a bare file path | Empty | Unknown argument and `--help` guidance | `2` |
| Multiple operations, including repeated flags | Empty | Choose one operation and `--help` guidance | `2` |

Arguments are checked before printing successful output, so `--help --unknown` still fails. Use one operation per invocation. Quote paths and environment labels containing spaces. A filename beginning with `--` must be supplied using an explicit relative or absolute path, such as `.\--example.xml`.

Evidence references display source filenames and line positions. The console masks recognized home-directory prefixes in diagnostic text. Saved runs retain full paths and original bytes for local investigation. Test names and other text supplied by the inputs can carry personal details. See [SQLite storage](docs/STORAGE.md#schema-and-evidence).

For `fixtures/mixed-results.xml`, the summary is `Tests: 5 | passed=2 failed=1 errors=1 skipped=1`. `Tests` is the total; `errors` means errored executions. Counts come from actual `<testcase>` elements, rather than declared suite totals. `FAILED` and `ERROR` describe XML outcomes; they do not claim a root cause. The earlier four-test `junit/mixed-suite.xml` fixture remains unchanged.

The parser supports a single `<testsuite>`, a `<testsuites>` container, nested suites, and namespace prefixes/default namespaces. Testcases must be direct suite children. `<failure>`, `<error>`, and `<skipped>` mark outcomes; no such marker means passed under this input contract. Empty suites are valid. Repeated names remain separate executions. Unsupported roots and malformed input are rejected clearly.

Optional testcase `time` is a nonnegative number of seconds, stored using `BigDecimal` without floating-point rounding. Missing duration stays unknown. Malformed/negative durations are rejected. Failure/error messages, types, and stack traces are retained as decoded fields; original report bytes, source path, and SHA-256 are kept separately. XML decoding may normalize entities/line endings. SAX line/column positions are diagnostic estimates, not exact XML spans or log citations. The [XML contract](docs/JUNIT_XML_IMPORT.md) describes the full supported subset and evidence limits.

## Import a local log

From the project root after building the JAR, try the sanitized fixtures in PowerShell.

```powershell
java -jar target/ci-failure-triage.jar import-log src/test/resources/fixtures/logs/timeout.log --commit abc1234 --environment 'sample CI'
java -jar target/ci-failure-triage.jar import-log src/test/resources/fixtures/logs/mixed.log
java -jar target/ci-failure-triage.jar import-log src/test/resources/fixtures/logs/passing.log
java -jar target/ci-failure-triage.jar import-log src/test/resources/fixtures/logs/unknown.log
java -jar target/ci-failure-triage.jar import-log target/nonexistent.log
$LASTEXITCODE # 1 for the nonexistent file
```

Put metadata flags after the path in either order. The CLI uses only the supplied values and shows `unknown` when a value is omitted. The timeout example reports `TIMEOUT [java-timeout]` and cites lines 3–5. The mixed example reports four independent findings. Passing input yields zero findings, and an empty log reports unavailable evidence.

The reader preserves UTF-8 bytes and decoded text. LF and CRLF delimit physical lines, blank lines count, and a final line without a newline keeps its number. The rules match explicit Java, JUnit, and Maven diagnostics, with generic summaries as fallback evidence. Each finding describes what the log shows. See the [supported rules, precedence, and evidence details](docs/LOG_IMPORT.md).

## Group similar findings

Supply the `--group` switch once after the log path, in any order with the metadata flags. Omitting it prints the individual findings.

```powershell
$sample = 'src/test/resources/fixtures/logs/grouping/grouped-failures.log'
java -jar target/ci-failure-triage.jar import-log $sample --group --commit abc1234 --environment 'sample CI'
java -jar target/ci-failure-triage.jar import-log $sample --commit abc1234 --environment 'sample CI'
```

The fixture yields six findings. The grouped view shows three groups with three assertion, two dependency, and one compilation occurrence. It includes the distinct known test count and marks members with unknown identities. Groups indicate diagnostic similarity. A shared root cause requires further evidence. See the [normalization policy, ID rules, and fixture ranges](docs/GROUPING.md).

## Save and reopen a run

Use the same run ID when the XML report and log belong to one execution. Both commands also accept `--commit`, `--environment`, and a UTF-8 `--metadata <path>` file when saving.

```powershell
$db = 'target/triage-demo.sqlite'
java -jar target/ci-failure-triage.jar import-junit src/test/resources/fixtures/mixed-results.xml --db $db --run-id demo
java -jar target/ci-failure-triage.jar import-log src/test/resources/fixtures/logs/grouping/grouped-failures.log --db $db --run-id demo --group
java -jar target/ci-failure-triage.jar runs --db $db
java -jar target/ci-failure-triage.jar show-run demo --db $db --group
```

The database stores each source's bytes, SHA-256, and parsed results. Repeating the same source kind and hash within `demo` keeps a single copy of that artifact. A different run ID preserves a separate execution. Imports with `--db` and an omitted run ID generate a fresh UUID. For schema details and a demonstration using removable input copies, see [SQLite storage](docs/STORAGE.md).

## Compare with an earlier run

Save each execution with explicit metadata and its own run ID. The sample fixtures use matching execution configurations and distinct commits. Use a fresh database for the demonstration after building the JAR.

```powershell
$fixtures = 'src/test/resources/fixtures/comparison'
$db = Join-Path 'target' ('comparison-' + [guid]::NewGuid() + '.sqlite')
java -jar target/ci-failure-triage.jar import-junit "$fixtures/baseline.xml" --db $db --run-id baseline --metadata "$fixtures/baseline.properties"
java -jar target/ci-failure-triage.jar import-junit "$fixtures/current.xml" --db $db --run-id current --metadata "$fixtures/current.properties"
java -jar target/ci-failure-triage.jar import-log "$fixtures/current.log" --db $db --run-id current
java -jar target/ci-failure-triage.jar compare-runs current --db $db
```

The comparison selects `baseline` and shows A as a new failure, D as an unseen failing test, and C as unobserved now. Supply `--baseline baseline` to request that run explicitly. The report explains any incompatibility and still lists current evidence priorities when comparison is unavailable. See the [metadata format, eligibility rules, and additional demos](docs/COMPARISON.md).

## GitHub Actions CI

`.github/workflows/ci.yml` runs on pushes and pull requests, uses Temurin JDK 17 on `ubuntu-latest`, caches Maven dependencies, and runs exactly:

```text
mvn --batch-mode clean verify
```

Actions are pinned to the verified release commits of [Checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) and [Setup Java v6.3.0](https://github.com/actions/setup-java/releases/tag/v6.3.0), checked on November 17, 2026. The workflow has read-only repository contents permission. Report fixtures describe failures as data; their parser assertions keep normal CI passing. GitHub-hosted execution is available after the workflow is pushed.

## Files and execution flow

| File | Purpose |
| --- | --- |
| `pom.xml` | Java 17 target, dependencies, pinned plugins, and standalone JAR packaging. |
| `src/main/java/org/citriage/CiTriageCli.java` | Entry point, options, import and query output, and version loading. |
| `src/main/java/org/citriage/report/JUnitXmlParser.java` | Secure local XML parser and source-aware diagnostics. |
| `src/main/java/org/citriage/report/TestIdentity.java` | Explicit reported class/test name pair and CLI display name. |
| `src/main/java/org/citriage/report/` models | Immutable source snapshot, report, test result, outcome details, diagnostic position, and parse exception. |
| `src/main/java/org/citriage/log/` | UTF-8 reader, numbered evidence, classifier, and immutable diagnostic groups. |
| `src/main/java/org/citriage/storage/` | SQLite repository, schema migration loader, saved run models, and observation mapping. |
| `src/main/java/org/citriage/comparison/` | Baseline selection, test matching, and investigation priority rules. |
| `src/main/resources/db/migrations/` | Ordered SQLite schema migrations. |
| `src/main/resources/ci-triage.properties` | Version template; Maven replaces `${project.version}` with the POM version. |
| `src/test/java/org/citriage/CiTriageCliTest.java` | JUnit tests for output streams, exit codes, no arguments, and invalid combinations. |
| `src/test/java/org/citriage/report/JUnitXmlParserTest.java` | Tests counts, nesting, decoded evidence, immutability, malformed input, encodings, and blocked external resources. |
| `src/test/java/org/citriage/log/` | Tests physical lines, snapshots, classification, signature matching, and exact evidence ranges. |
| `src/test/java/org/citriage/storage/` and `CiTriageStorageCliTest.java` | File-backed repository and CLI storage tests. |
| `src/test/resources/fixtures/` | Requested Day 2 passing, five-test mixed, nested, malformed, and DOCTYPE fixtures, plus expected observations. |
| `src/test/resources/fixtures/logs/` | Sanitized build and test logs with expected findings and groups. |
| `src/test/resources/fixtures/comparison/` | Example baseline and current executions, explicit metadata, and current log evidence. |
| `src/test/resources/junit/` | Unchanged earlier fixtures retained for regression coverage. |
| `.github/workflows/ci.yml` | JDK 17 Maven checks on pushes and pull requests. |
| `docs/JUNIT_XML_IMPORT.md` | The supported input format, outcome models, evidence semantics, and API example. |
| `docs/LOG_IMPORT.md` | Log import options, evidence conventions, and classification rules. |
| `docs/GROUPING.md` | Grouping options, signature normalization, and fixture expectations. |
| `docs/STORAGE.md` | Storage commands, run identity, migrations, and evidence semantics. |
| `docs/COMPARISON.md` | Metadata format, compatible baseline policy, comparison outcomes, and priorities. |
| `.gitignore` | Excludes build output, local tools, credentials, SQLite files, and editor/OS temporary files. Deliberately allows sanitized `.log` fixtures. |
| `AGENTS.md` | Future Codex instructions about scope, existing work, evidence, checks, and handoffs. |
| `ROADMAP.md` | Later implementation stages and their boundaries. |
| `README.md` | Project explanation, setup, commands, and handoff guidance. |

When you run `java -jar`, Java reads `META-INF/MANIFEST.MF` inside the JAR. Maven Shade writes `Main-Class: org.citriage.CiTriageCli` there and preserves the JDBC driver service entry. Java calls `public static void main(String[] args)` on that class. `main()` passes the arguments and console streams to `run()`, then uses its return value as the process exit code.

`run()` returns an integer and leaves the JVM running for its caller. Tests call it with in-memory streams. Maven filters the version resource before tests and packaging, keeping the application's version in `pom.xml`. The version test checks the expected release string.

For `import-junit` (or its `--report` alias), the call path continues from `run()` to `importReport()`, then `JUnitXmlParser.parse(Path)`. The parser reads a byte snapshot, builds the outcome models, and returns `JUnitReport`. The CLI prints the accepted report and returns `0`; read/parse errors return `1`, and usage errors return `2`. `main()` turns that integer into the process exit status. A failed test inside the report does not itself mean the import failed.

For `import-log`, `run()` validates the path and flags. `LogReader.read(Path)` creates a `BuildLog` snapshot and `LogClassifier.classify()` returns findings with original evidence. With `--group`, the CLI passes those findings to `LogGrouper.group()` before formatting. The grouping service uses the Java standard library.

With `--db`, the CLI saves the accepted import through `SqliteRunRepository` before printing success. The repository stores groups for every saved log. `runs` queries summaries without reading the source BLOBs. `show-run` loads saved observations and reconstructs numbered excerpts from the stored bytes.

`compare-runs` evaluates candidate metadata and stored outcome counts in one read-only snapshot. It loads the current and selected runs for evidence-backed comparison and ranking.

`.tools/` contains ignored local tooling/cache files when the portable setup is used. `target/` contains generated files, including the executable JAR. Neither belongs in the source commit.

## Commit and push to GitHub

Use the checkout's existing `origin` remote. Verify its URL with `git remote -v` before pushing. After reviewing and validating changes, these are commands for you to run:

```powershell
# Review the files and stage only project sources/documentation.
git status --short
git add src docs README.md AGENTS.md ROADMAP.md pom.xml
git diff --cached --check
git diff --cached
git commit -m "Compare compatible CI runs and prioritize investigations"

# Verify the existing destination.
git remote -v
git push -u origin HEAD
```

Do not overwrite an existing remote. If a separate checkout uses another remote name, use that name in the push command. GitHub authentication must already be configured. If Git asks for an author identity, set your chosen `user.name` and `user.email` before committing.
