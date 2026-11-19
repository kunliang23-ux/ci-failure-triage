# CI Failure Triage roadmap

The product question is: **Why did my tests fail, and what should I investigate first?**
Advance in small, testable stages. Java 17 and Maven remain the backend foundation, and JUnit supplies automated tests. Day 1 through Stage 3 are implemented. The input and evidence contracts are in [JUnit XML import](docs/JUNIT_XML_IMPORT.md) and [log import](docs/LOG_IMPORT.md).

## Day 1: scaffold and CLI

- Standard Maven layout, Java 17 compilation, and a runnable JAR.
- Help, version, explicit argument errors, and tests of output and exit codes.
- Setup instructions, ignore rules, and contributor guidance.

## Day 2: import JUnit XML locally and add basic CI (implemented)

- [x] Introduce small immutable models for a report, explicit test identities/suite paths, outcomes, optional decimal durations in seconds, and original outcome details. Full run/commit/environment metadata remains later work.
- [x] Add `import-junit <report-path>` and preserve the earlier `--report <path>` compatibility alias, help, version, and argument behavior.
- [x] Parse local JUnit XML using Java XML APIs with external entities and external DTD access disabled.
- [x] Handle single suites, nested suites, failures, errors, skipped tests, and malformed input explicitly.
- [x] Preserve original bytes, source names, SHA-256 hashes, decoded messages, and stack traces. Label SAX positions as diagnostics rather than exact source spans.
- [x] Test sanitized fixtures in `src/test/resources/fixtures`, including a mixed report with 5 executions (2 passed, 1 failed, 1 errored, 1 skipped), nested suites, malformed XML, and a rejected DOCTYPE. Retain earlier fixtures as regressions.
- [x] Add `.github/workflows/ci.yml` to run `mvn --batch-mode clean verify` on JDK 17 for pushes and pull requests, using verified supported Action releases pinned to commit SHAs. GitHub-hosted execution starts after the workflow is pushed.

Acceptance: a local report produces accurate test outcomes and useful diagnostics without storage, AI, or a web interface.

## Stage 3: logs and deterministic classification (implemented)

- [x] Add `import-log <log-path>` for one local UTF-8 file. Preserve source path, original bytes, decoded text, SHA-256, and exact one-based line numbers.
- [x] Classify assertion, dependency, compiler, timeout, and unknown diagnostics with deterministic rules. Print each finding's rule and evidence excerpt.
- [x] Recognize explicitly named Surefire tests and retain separate findings for independent diagnostics. Use build/test summaries as fallback evidence.
- [x] Display supplied commit and environment metadata, with `unknown` for omitted values.
- [x] Cover the rules, line semantics, and CLI with sanitized fixtures and tests.

## Stage 4: group similar failures

- Normalize derived copies of messages and group likely related failures deterministically.
- Keep original evidence accessible. Do not remove differences such as expected/actual values when they change the diagnosis.
- Test both failures that should group and failures that must remain distinct.

## Stage 5: persistence and compatible baselines

- Add SQLite through JDBC, with a small repository layer, prepared statements, schema migrations, and transactional imports.
- Store runs, test executions, metadata, and evidence provenance without changing CLI/domain contracts unnecessarily.
- Compare a failed run with an earlier passing run only when repository, suite, environment, and relevant runtime/configuration metadata are compatible.
- Report why a baseline was chosen, or why no compatible baseline exists. Missing compatibility data must not silently imply a match.

## Stage 6: inconsistent repeated outcomes

- Link repeated executions by test identity and compatible execution context.
- Show observed pass/fail changes with execution counts and evidence.
- Describe the observation as an inconsistent outcome. A single failure or an incompatible environment does not establish flakiness or a root cause.

## Stage 7: AI explanations and evaluation

- Place optional AI integration behind an interface; obtain credentials from the environment.
- Give explanations only from supplied evidence and require citations to exact log lines.
- Validate citation ranges against stored source text and return **insufficient evidence** when support is inadequate.
- Treat imported logs as untrusted data. Keep prompts, model versions, and explanation provenance available for evaluation.
- Build a labeled, sanitized evaluation set with a held-out portion. Compare rules-only and AI-assisted classification, grouping, unsupported claims, citation accuracy, and abstention behavior.
- Record costs and latency alongside accuracy; document limits before claiming improvements.

## Stage 8: web API

- Add Spring Boot around established services for import, run details, groups, comparisons, and explanations.
- Define request validation, pagination, actionable errors, and tests. Keep domain logic independent of HTTP.

## Stage 9: dashboard and GitHub importing

- Build a React and TypeScript dashboard for runs, test names, errors, commit SHAs, environments, failure groups, comparisons, and cited evidence.
- Show unknowns and insufficient evidence clearly. Let developers trace explanations back to source lines.
- Add GitHub run/artifact importing with explicit repository selection, credential handling, pagination, and rate-limit behavior.
- Extend GitHub Actions checks as frontend and integration tests become relevant.

## Before starting Stage 4

Run the mixed log fixture and trace each finding back to the source range. Define what makes two findings similar and how grouping will retain each original excerpt before adding normalization for that stage.
