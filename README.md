# CI Failure Triage

A Java portfolio project to help developers answer:

> Why did my tests fail, and what should I investigate first?

**Current implementation: Day 1 and Day 2, local JUnit XML import plus basic CI.** The CLI shows help/version information and imports a report into explicit identities, optional durations, and passed, failed, error, and skipped outcomes while retaining original bytes and provenance. GitHub Actions builds and tests on pushes and pull requests. See the [XML input contract](docs/JUNIT_XML_IMPORT.md). Log classification, grouping, comparisons, SQLite storage, AI explanations, GitHub importing, the Spring Boot API, and the React/TypeScript dashboard remain in [ROADMAP.md](ROADMAP.md).

## Requirements and versions

- JDK 17, including `java` and `javac`.
- Apache Maven 3.9.x or a newer stable Maven 3 release; the local setup below uses 3.9.16.
- Git for source control and a GitHub account for publishing.
- Internet access for the first Maven dependency download. The built CLI itself works offline.

`pom.xml` targets Java 17 with `maven.compiler.release`. JUnit Jupiter 5.13.4 is test-only; there are no runtime dependencies. The active build plugins are pinned: Clean 3.4.1, Resources 3.3.1, Compiler 3.14.1, Surefire 3.5.4, and JAR 3.4.2. These are stable releases, rather than previews. See the [JUnit release notes](https://docs.junit.org/5.13.4/release-notes/), [Compiler requirements](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/plugin-info.html), [Surefire requirements](https://maven.apache.org/surefire-archives/surefire-3.5.4/maven-surefire-plugin/plugin-info.html), and [JAR requirements](https://maven.apache.org/plugins-archives/maven-jar-plugin-3.4.2/plugin-info.html).

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
java -jar target/ci-failure-triage.jar import-junit target/surefire-reports/TEST-com.kunliang.citriage.CiTriageCliTest.xml

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
| `mvn package` | Everything through `test`, then the JAR plugin packages compiled application classes and resources and writes the manifest entry point. | `target/ci-failure-triage.jar`. Test fixtures and JUnit dependencies are excluded. |
| `mvn verify` | Everything through `package`, then any checks bound to later verification phases. | The packaged JAR after the configured lifecycle checks succeed. |

This POM has no Failsafe integration-test or additional verification-plugin bindings yet. `verify` reaches the later phases, but does not invent extra tests, launch the CLI, or contact GitHub. That is why the packaged CLI is demonstrated separately. `clean` is a separate lifecycle: `mvn clean verify` removes old `target/` output first and rebuilds through `verify`. A plain `mvn test` may leave an older JAR in place; package again before running changed code.

The sequence is `compile -> test -> package -> verify`, with resource and test-compilation phases between them. Surefire tests call the handler/parser as ordinary Java methods. Neither building the JAR nor running these tests calls the application's `main()`. See the [official Maven lifecycle guide](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html).

A synthetic XML fixture can describe failed tests while our parser unit tests pass: the assertions verify that those failed outcomes are imported accurately.

## Day 1 validation results

Validated on November 17, 2026, with Java 17 and checksum-verified Apache Maven 3.9.16:

- Both isolated-cache Maven commands above completed with `BUILD SUCCESS`. Each ran 14 tests, with zero failures, errors, or skipped tests.
- The packaged JAR returned `0` for `--help`, `--version`, and no arguments. Version output was `CI Failure Triage 0.1.0`.
- It returned `2` for `--unknown`, `--help --unknown`, and `--help --version`, with guidance on standard error and no standard output.
- The JAR manifest names `com.kunliang.citriage.CiTriageCli`, its version resource contains `version=0.1.0`, and its class-file major version is `61` (Java 17).

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

## CLI behavior

| Arguments | Standard output | Standard error | Exit code |
| --- | --- | --- | --- |
| No arguments or `--help` | Purpose, usage, available options | Empty | `0` |
| `--version` | `CI Failure Triage 0.1.0` | Empty | `0` |
| `import-junit <report-path>` with a supported report | Source, SHA-256, total/passed/failed/errored/skipped counts, test names, available durations, and outcome details | Empty | `0`, even if reported tests failed |
| `import-junit <report-path>` with unreadable, malformed, or unsupported input | Empty; no partial import | Source/read/parse diagnostic | `1` |
| `--report <path>` | Compatibility alias for `import-junit`, with identical output and exit behavior | As above | As above |
| Missing/extra report arguments, or an invalid path syntax | Empty | Usage error and `--help` guidance | `2` |
| Any unknown argument, including a bare file path | Empty | Unknown argument and `--help` guidance | `2` |
| Multiple operations, including repeated flags | Empty | Choose one operation and `--help` guidance | `2` |

Arguments are checked before printing successful output, so `--help --unknown` still fails. Use one operation per invocation. Quote a report path containing spaces. A filename beginning with `--` must be supplied using an explicit relative or absolute path, such as `.\--example.xml`.

For `fixtures/mixed-results.xml`, the summary is `Tests: 5 | passed=2 failed=1 errors=1 skipped=1`. `Tests` is the total; `errors` means errored executions. Counts come from actual `<testcase>` elements, rather than declared suite totals. `FAILED` and `ERROR` describe XML outcomes; they do not claim a root cause. The earlier four-test `junit/mixed-suite.xml` fixture remains unchanged.

The parser supports a single `<testsuite>`, a `<testsuites>` container, nested suites, and namespace prefixes/default namespaces. Testcases must be direct suite children. `<failure>`, `<error>`, and `<skipped>` mark outcomes; no such marker means passed under this input contract. Empty suites are valid. Repeated names remain separate executions. Unsupported roots and malformed input are rejected clearly.

Optional testcase `time` is a nonnegative number of seconds, stored using `BigDecimal` without floating-point rounding. Missing duration stays unknown. Malformed/negative durations are rejected. Failure/error messages, types, and stack traces are retained as decoded fields; original report bytes, source path, and SHA-256 are kept separately. XML decoding may normalize entities/line endings. SAX line/column positions are diagnostic estimates, not exact XML spans or log citations. The [XML contract](docs/JUNIT_XML_IMPORT.md) describes the full supported subset and evidence limits.

## GitHub Actions CI

`.github/workflows/ci.yml` runs on pushes and pull requests, uses Temurin JDK 17 on `ubuntu-latest`, caches Maven dependencies, and runs exactly:

```text
mvn --batch-mode clean verify
```

Actions are pinned to the verified release commits of [Checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) and [Setup Java v6.3.0](https://github.com/actions/setup-java/releases/tag/v6.3.0), checked on November 17, 2026. The workflow has read-only repository contents permission. Report fixtures describe failures as data; their parser assertions keep normal CI passing. GitHub-hosted execution is available after the workflow is pushed.

## Files and execution flow

| File | Purpose |
| --- | --- |
| `pom.xml` | Java 17 target, application version, test dependency, pinned plugins, fixed JAR name, and manifest entry point. |
| `src/main/java/com/kunliang/citriage/CiTriageCli.java` | `main()`, argument handling, help/errors, report import/output, and version loading. |
| `src/main/java/com/kunliang/citriage/report/JUnitXmlParser.java` | Secure local XML parser and source-aware diagnostics. |
| `src/main/java/com/kunliang/citriage/report/TestIdentity.java` | Explicit reported class/test name pair and CLI display name. |
| `src/main/java/com/kunliang/citriage/report/` models | Immutable source snapshot, report, test result, outcome details, diagnostic position, and parse exception. |
| `src/main/resources/ci-triage.properties` | Version template; Maven replaces `${project.version}` with the POM version. |
| `src/test/java/com/kunliang/citriage/CiTriageCliTest.java` | JUnit tests for output streams, exit codes, no arguments, and invalid combinations. |
| `src/test/java/com/kunliang/citriage/report/JUnitXmlParserTest.java` | Tests counts, nesting, decoded evidence, immutability, malformed input, encodings, and blocked external resources. |
| `src/test/resources/fixtures/` | Requested Day 2 passing, five-test mixed, nested, malformed, and DOCTYPE fixtures, plus expected observations. |
| `src/test/resources/junit/` | Unchanged earlier fixtures retained for regression coverage. |
| `.github/workflows/ci.yml` | JDK 17 Maven checks on pushes and pull requests. |
| `docs/JUNIT_XML_IMPORT.md` | The supported input format, outcome models, evidence semantics, and API example. |
| `.gitignore` | Excludes build output, local tools, credentials, SQLite files, and editor/OS temporary files. Deliberately allows sanitized `.log` fixtures. |
| `AGENTS.md` | Future Codex instructions about scope, existing work, evidence, checks, and handoffs. |
| `ROADMAP.md` | Later implementation stages and their boundaries. |
| `README.md` | Project explanation, setup, commands, and handoff guidance. |

When you run `java -jar`, Java reads `META-INF/MANIFEST.MF` inside the JAR. Maven's JAR plugin writes `Main-Class: com.kunliang.citriage.CiTriageCli` there. Java calls `public static void main(String[] args)` on that class. `main()` passes the arguments and console streams to `run()`, then uses its return value as the process exit code.

`run()` returns an integer and never exits the JVM. Tests call it with in-memory streams, so argument errors cannot terminate JUnit. Maven filters the version resource before tests and packaging, keeping the application's version in `pom.xml`; the version test checks the expected release string. The plain JAR is sufficient because the CLI has no runtime dependencies.

For `import-junit` (or its `--report` alias), the call path continues from `run()` to `importReport()`, then `JUnitXmlParser.parse(Path)`. The parser reads a byte snapshot, builds the outcome models, and returns `JUnitReport`. The CLI prints the accepted report and returns `0`; read/parse errors return `1`, and usage errors return `2`. `main()` turns that integer into the process exit status. A failed test inside the report does not itself mean the import failed.

`.tools/` contains ignored local tooling/cache files when the portable setup is used. `target/` contains generated files, including the executable JAR. Neither belongs in the source commit.

## Commit and push to GitHub

Use the checkout's existing `origin` remote. Verify its URL with `git remote -v` before pushing. After reviewing and validating changes, these are commands for you to run:

```powershell
# Review the files and stage only project sources/documentation.
git status --short
git add src docs .github/workflows/ci.yml README.md AGENTS.md ROADMAP.md
git diff --cached --check
git diff --cached
git commit -m "Complete Day 2 JUnit XML import and CI"

# Verify the existing destination.
git remote -v
git push -u origin HEAD
```

Do not overwrite an existing remote. If a separate checkout uses another remote name, use that name in the push command. GitHub authentication must already be configured. If Git asks for an author identity, set your chosen `user.name` and `user.email` before committing.

## After Day 2

Trace the Maven lifecycle and JAR entry point above, run the five-test mixed fixture, then read the parser tests and XML contract. Extend the input contract with new sanitized fixtures before adding another producer's dialect. The next stage is local log evidence and deterministic classification; keep storage and AI in their later roadmap stages.
