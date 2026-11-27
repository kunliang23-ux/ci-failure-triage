# CI Failure Triage roadmap

The product question is: **Why did my tests fail, and what should I investigate first?**
The 15-day plan advances in small, testable stages. Java 17 and Maven remain the backend foundation, and JUnit supplies automated tests. Days 1–7 are implemented. See [JUnit XML import](docs/JUNIT_XML_IMPORT.md), [log import](docs/LOG_IMPORT.md), [failure grouping](docs/GROUPING.md), [SQLite storage](docs/STORAGE.md), [baseline comparison](docs/COMPARISON.md), and [GitHub import](docs/GITHUB_IMPORT.md).

## Day 1: scaffold and CLI

- Standard Maven layout, Java 17 compilation, and a runnable JAR.
- Help, version, explicit argument errors, and tests of output and exit codes.
- Setup instructions, ignore rules, and contributor guidance.

## Day 2: import JUnit XML locally and add basic CI (implemented)

- [x] Introduce small immutable models for a report, explicit test identities/suite paths, outcomes, optional decimal durations in seconds, and original outcome details.
- [x] Add `import-junit <report-path>` and preserve the earlier `--report <path>` compatibility alias, help, version, and argument behavior.
- [x] Parse local JUnit XML using Java XML APIs with external entities and external DTD access disabled.
- [x] Handle single suites, nested suites, failures, errors, skipped tests, and malformed input explicitly.
- [x] Preserve original bytes, source names, SHA-256 hashes, decoded messages, and stack traces. Label SAX positions as diagnostics rather than exact source spans.
- [x] Test sanitized fixtures in `src/test/resources/fixtures`, including a mixed report with 5 executions (2 passed, 1 failed, 1 errored, 1 skipped), nested suites, malformed XML, and a rejected DOCTYPE. Retain earlier fixtures as regressions.
- [x] Add `.github/workflows/ci.yml` to run `mvn --batch-mode clean verify` on JDK 17 for pushes and pull requests, using verified supported Action releases pinned to commit SHAs. GitHub-hosted execution starts after the workflow is pushed.

Acceptance: a local report produces accurate test outcomes and useful diagnostics from the CLI.

## Day 3: logs and deterministic classification (implemented)

- [x] Add `import-log <log-path>` for one local UTF-8 file. Preserve source path, original bytes, decoded text, SHA-256, and exact one-based line numbers.
- [x] Classify assertion, dependency, compiler, timeout, and unknown diagnostics with deterministic rules. Print each finding's rule and evidence excerpt.
- [x] Recognize explicitly named Surefire tests and retain separate findings for independent diagnostics. Use build/test summaries as fallback evidence.
- [x] Display supplied commit and environment metadata, with `unknown` for omitted values.
- [x] Cover the rules, line semantics, and CLI with sanitized fixtures and tests.

## Day 4: group similar failures (implemented)

- [x] Add an optional `import-log <log-path> --group` view for one local log while preserving individual output as the default.
- [x] Match exact category and diagnostic signatures with conservative normalization. Keep unknowns as singletons and order groups by their first evidence line.
- [x] Show stable IDs, occurrence counts, distinct known tests, unknown identities, and every member's original numbered evidence.
- [x] Verify grouping and meaningful differences with a six-finding, three-group fixture and Java 17 tests.

## Day 5: SQLite persistence (implemented)

- [x] Add versioned SQLite migrations, foreign keys, prepared statements, and transactional imports behind a repository.
- [x] Save source snapshots, JUnit executions, log findings, groups, and explicit metadata. Deduplicate an artifact within its run while preserving separate executions.
- [x] List saved runs and read stored results and numbered evidence from the packaged JAR.

## Day 6: compatible baselines and investigation priorities (implemented)

- [x] Accept explicit execution metadata from a UTF-8 properties file with transactional validation and conflict handling.
- [x] Select or validate a compatible completed passing baseline from stored observations. Report selection and exclusion reasons using execution chronology and exact known configuration matches.
- [x] Identify new, unseen, skipped-baseline, ambiguous, and missing test observations. Keep repeated executions and evidence available.
- [x] Rank investigation priorities using supported build diagnostics, confirmed new failures, and distinct named tests. Keep priorities available without a compatible baseline.

## Day 7: GitHub Actions job bundle import (implemented)

- [x] Capture Maven output outside the clean directory and upload available JUnit XML with a versioned execution manifest. Preserve the original build status.
- [x] Resolve a completed job in one explicit repository and one workflow attempt. Use read-only authentication, paginated results, and a scoped artifact download.
- [x] Validate the archive and provenance before saving the original files, observations, and execution metadata transactionally. Preserve a stable run ID across reimports.
- [x] Add mocked HTTP and archive tests and a manual controlled-failure workflow for post-push demonstration.

## Later: inconsistent repeated outcomes

- Link repeated executions by test identity and compatible execution context.
- Show observed pass/fail changes with execution counts and evidence.
- Describe the observation as an inconsistent outcome. A single failure or an incompatible environment does not establish flakiness or a root cause.

## Later: AI explanations and evaluation

- Place optional AI integration behind an interface; obtain credentials from the environment.
- Give explanations only from supplied evidence and require citations to exact log lines.
- Validate citation ranges against stored source text and return **insufficient evidence** when support is inadequate.
- Treat imported logs as untrusted data. Keep prompts, model versions, and explanation provenance available for evaluation.
- Build a labeled, sanitized evaluation set with a held-out portion. Compare rules-only and AI-assisted classification, grouping, unsupported claims, citation accuracy, and abstention behavior.
- Record costs and latency alongside accuracy; document limits before claiming improvements.

## Later: web API

- Add Spring Boot around established services for import, run details, groups, comparisons, and explanations.
- Define request validation, pagination, actionable errors, and tests. Keep domain logic independent of HTTP.

## Later: dashboard

- Build a React and TypeScript dashboard for runs, test names, errors, commit SHAs, environments, failure groups, comparisons, and cited evidence.
- Show unknowns and insufficient evidence clearly. Let developers trace explanations back to source lines.
- Extend GitHub Actions checks as frontend and integration tests become relevant.
