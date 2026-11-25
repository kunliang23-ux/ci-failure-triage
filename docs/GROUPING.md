# Group similar log failures

Build the JAR and run these commands from the project root.

```powershell
$sample = 'src/test/resources/fixtures/logs/grouping/grouped-failures.log'
java -jar target/ci-failure-triage.jar import-log $sample --group --commit abc1234 --environment 'sample CI'
java -jar target/ci-failure-triage.jar import-log $sample --commit abc1234 --environment 'sample CI'
```

`--group` is a switch. Supply it once after the path, in any order with `--commit` and `--environment`. The grouped view retains the source, SHA-256, metadata, line count, and number of findings. It adds the number of groups and displays each group's ID, category, occurrence count, representative diagnostic, and member evidence. Empty and passing logs show zero groups. Groups indicate diagnostic similarity. A shared root cause requires further evidence.

Each occurrence is one classified finding. The distinct known test count uses the exact class and test name pair. Multiple findings for one test still count as multiple occurrences. Each member without an explicit test identity displays `unknown` and contributes to the unknown test occurrence count.

SQLite log imports save these groups even when the import displays individual findings. `show-run <id> --db <database-path> --group` displays the saved groups and evidence for each source. Display IDs are scoped to the source. See [SQLite storage](STORAGE.md).

## Normalization and matching

`LogGrouper` accepts findings from a single imported log snapshot. The group model keeps every original `LogFinding` and its `LogEvidence`. The signature is a separate string derived from the evidence and serves as the representative diagnostic.

1. Skip a recognized Surefire test header when a diagnostic follows it. Skip Java stack frames and `... N more` lines. Keep exception headers and messages, including wrapper, cause, and suppressed exceptions. Keep dependency details and compiler context.
2. Remove a BOM at the start of the log and leading presentation padding, including spaces, tabs, ANSI color escapes, and a recognized `[ERROR]` or `[FATAL]` prefix. Keep compiler continuation lines verbatim because their indentation can encode a column. Join the retained physical lines with LF for matching.
3. Preserve case, punctuation, and whitespace inside and at the end of the diagnostic payload. The signature retains exception types, expected and actual values, missing classes, dependency coordinates and versions, timeout durations, and compiler paths, positions, symbols, and error codes.
4. Require an exact category and signature match. `UNKNOWN` findings and findings from the summary-only `maven-compilation` rule each get their own group. Differences in diagnostic wording or context can produce separate groups.

Groups appear in order of their first evidence line, and members appear in source order. For a matchable diagnostic, `g-` IDs use the first 16 hexadecimal digits of SHA-256 over the UTF-8 category name, an LF, and the signature. Singleton IDs use `line-` and their starting evidence line. If a display ID repeats, later groups receive suffixes such as `-2` in source order. Matching uses the full signature. The original bytes, hash, evidence line numbers and terminators remain available in each member's source and evidence. The console uses filenames, masks recognized home-directory prefixes, and escapes terminal control characters other than tabs.

## Fixture expectations

The sample yields six findings in three groups.

| Group category | Occurrences | Distinct known tests | Original evidence ranges |
| --- | ---: | ---: | --- |
| `ASSERTION_ERROR` | 3 | 3 | 1–3, 9–11, 17–19 |
| `MISSING_DEPENDENCY` | 2 | 0 | 5–7, 21–23 |
| `COMPILATION_ERROR` | 1 | 0 | 13–15 |

The Cart and Invoice failures share `org.opentest4j.AssertionFailedError: expected: <42> but was: <41>` after the test headers, severity prefix, and frames are removed from the matching view. If an otherwise identical diagnostic says `but was: <40>`, it has a different signature and forms a separate group.

For evidence and classifier behavior, see the [log input contract](LOG_IMPORT.md).
