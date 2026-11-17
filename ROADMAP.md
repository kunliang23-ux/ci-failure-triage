# CI Failure Triage roadmap

The product question is: **Why did my tests fail, and what should I investigate first?**
Advance in small, testable stages. Java 17 and Maven remain the backend foundation; JUnit supplies automated tests. Only the Day 1 stage is implemented in this scaffold.

## Day 1: scaffold and CLI

- Standard Maven layout, Java 17 compilation, and a runnable JAR.
- Help, version, explicit argument errors, and tests of output and exit codes.
- Setup instructions, ignore rules, and contributor guidance.

## Day 2: import JUnit XML locally

- Introduce small models for runs, test identities, outcomes, and failure details.
- Parse local JUnit XML using Java XML APIs with external entities and external DTD access disabled.
- Handle single suites, nested suites, failures, errors, skipped tests, and malformed input explicitly.
- Preserve report source, original messages, stack traces, and provenance. Document line-reference semantics before promising exact locations.
- Test sanitized fixtures in `src/test/resources`, including passing and malformed reports. Verify counts and extracted fields.
- Add GitHub Actions to run `mvn --batch-mode clean verify` on JDK 17 for pushes and pull requests.

Acceptance: a local report produces accurate test outcomes and useful diagnostics without storage, AI, or a web interface.

## Stage 3: logs and deterministic classification

- Import local build/test logs while preserving original text and exact, one-based source line numbers.
- Attach evidence to failures when supported; distinguish test-specific evidence from run-wide build failures.
- Classify assertion errors, missing dependencies, compilation errors, timeouts, and unknown failures using explicit, tested rules.
- Define precedence and ambiguous cases. Return unknown when no rule has sufficient support.
- Display test names, error details, commit SHAs, and environments when supplied; keep absent metadata explicitly unknown.

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

## Before starting Day 2

Be comfortable tracing the JAR manifest to `main()`, calling the testable CLI handler, reading JUnit results, and using Maven lifecycle phases. Agree on the first XML input contract and expected output, then start with sanitized fixtures and a small model. Keep the original report separate from interpreted data.
