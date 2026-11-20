# Local log import

Import one local UTF-8 build or test log from the project root after building the JAR.

```powershell
java -jar target/ci-failure-triage.jar import-log src/test/resources/fixtures/logs/timeout.log --commit abc1234 --environment 'sample CI'
```

Supply the path first. `--commit` and `--environment` each take one nonblank value. `--group` is a switch. Each flag may appear once after the path, in any order. Quote paths and labels containing spaces. A filename beginning with `--` can use an explicit relative path such as `.\--example.log`. Metadata comes exclusively from the flags. The CLI displays supplied values as text and uses `unknown` for each omitted value.

| Exit | Meaning |
| --- | --- |
| `0` | Import succeeded. This includes empty logs and logs containing failure findings. |
| `1` | The log could not be read or decoded as UTF-8. The diagnostic goes to stderr. |
| `2` | The command arguments were invalid. Stderr includes usage guidance. |

The output gives the source path, SHA-256, metadata, line count, and findings. A blank log reports `Evidence unavailable`. A nonblank log with zero matches reports `No failure diagnostics matched`. Findings describe observed diagnostics, and a successful import confirms only that the file was read and decoded. Use `--group` for a diagnostic similarity view that keeps every finding's original numbered evidence. See [grouping](GROUPING.md).

## Evidence and line numbers

`LogReader` reads one regular file and decodes it with a strict UTF-8 decoder. `BuildLog` holds a `ReportSource` snapshot with the absolute source path, immutable bytes, and a SHA-256 hash. It also holds the complete decoded text and an immutable list of `LogLine` records. Each record stores the line number, untrimmed text, and original terminator. The file remains available at its original path.

- Line numbers start at 1. LF ends a line, and CR immediately before LF belongs to the CRLF terminator. A standalone CR remains in the line's text.
- Blank lines count. A final line with text and no newline also counts. A trailing newline closes its line, so `x\n` has one line and `x\n\n` has two. An empty file has zero lines.
- A leading UTF-8 BOM remains in the original text. Classification uses a separate view that drops the initial BOM, ANSI color sequences, and outer whitespace. Rules also handle recognized severity and exception prefixes on that view.
- `LogEvidence` stores the source snapshot and a consecutive list of original lines. The first and last line numbers are inclusive. `originalText()` includes the original terminators, including the last line's terminator when present.
- The CLI prefixes each line with its number and displays terminal control characters other than tabs as Unicode escapes. The model retains the literal characters. Source line numbers refer to the imported log. A stack frame such as `InventoryTest.java:31` is part of the log text.

The log snapshot lives in memory for the import. Keep the source file for later use. A hash identifies the bytes and can be compared with a later copy.

## Supported rules

| Category | Rule ID | Diagnostic |
| --- | --- | --- |
| `TIMEOUT` | `java-timeout` | Java `TimeoutException` or `SocketTimeoutException`, JUnit `TestTimedOutException`, JUnit execution or test timeout messages with a duration, and the Surefire fork timeout message. |
| `ASSERTION_ERROR` | `java-assertion` | `AssertionError`, `AssertionFailedError`, `MultipleFailuresError`, or `ComparisonFailure` in a Java exception header. |
| `MISSING_DEPENDENCY` | `java-class-loading` | `ClassNotFoundException` or `NoClassDefFoundError`. The explicit `Could not initialize class` variant uses the unclassified exception rule. |
| `MISSING_DEPENDENCY` | `maven-dependency-resolution` | Maven dependency collection or resolution errors, missing artifacts, failed artifact transfers, or unreadable artifact descriptors. |
| `COMPILATION_ERROR` | `java-compiler` | Java source diagnostics with a file and position, `cannot find symbol`, `package … does not exist`, or invalid release errors. |
| `COMPILATION_ERROR` | `maven-compilation` | Compiler failure banner or Maven compiler plugin failure summary. |
| `UNKNOWN` | `unclassified-java-exception` | Java exception or error header without a supported diagnostic in its block. |
| `UNKNOWN` | `surefire-test-failure` | Surefire per-test failure or error header without an adjacent recognized exception or timeout diagnostic. |
| `UNKNOWN` | `unclassified-error` | Other nonempty Maven-style `[ERROR]` or `[FATAL]` diagnostics, excluding routine help and separators. |
| `UNKNOWN` | `surefire-failure-summary` | A Surefire summary that reports failed or errored tests, including positive counts. |
| `UNKNOWN` | `build-failure-summary` | A generic build failure, goal failure without a reason, or nonzero process exit summary. |

Maven dependency diagnostics can reflect artifact availability, repository access, or configuration. See the [Maven troubleshooting examples](https://maven.apache.org/run-maven/index.html).

## Precedence and boundaries

1. Test identity comes from a recognized Surefire `Class.method -- Time elapsed: … <<< FAILURE!` or `<<< ERROR!` header. Its evidence can include the immediately following exception and trace.
2. One finding covers a diagnostic and its recognized contiguous context. Java frames and cause or suppressed exception lines extend a trace. A Maven dependency heading can include adjacent dependency and artifact details. Compiler findings can include adjacent symbol/location notes or a source line followed by a caret. Blank lines and unrecognized context end the excerpt. Separate diagnostic blocks remain separate, including repeated spellings on different lines.
3. Within a trace, the first recognized specific diagnostic determines the category. An explicit JUnit timeout message on an assertion header takes precedence over that header's assertion type. A compiler's missing-package message is a compilation diagnostic. The `timeout` word in a passing or informational line is insufficient.
4. Compiler summaries supply a compilation finding when there is no compiler detail in the same run. Test and build summaries are fallbacks, with the test summary preferred. Detail findings take precedence even when the summary appears first. An independent unclassified error line still produces `UNKNOWN` alongside other findings. Maven's `[INFO] Scanning for projects...` starts a new run for this decision.

In `timeout.log`, line 3 provides the Surefire test name, line 4 matches `java-timeout`, and the frame on line 5 finishes the evidence range. The build summary on line 6 is redundant once those lines are available.

This ruleset covers the English diagnostic forms above. Parallel or interleaved logs, unfamiliar producers, and summary-only output can omit the structure needed for precise findings. Log evidence comes from this one file. XML import has its own evidence contract and approximate diagnostic positions. See the [JUnit XML import contract](JUNIT_XML_IMPORT.md).
