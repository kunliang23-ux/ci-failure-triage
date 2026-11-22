# Local JUnit XML import contract

Day 2 imports one local report and answers what test outcomes it contains. It does not infer why tests failed. Java 17's built-in SAX parser supplies the XML implementation; there is no new runtime dependency.

## Try it

After `mvn package` or `mvn clean verify`, run from the project root:

```powershell
java -jar target/ci-failure-triage.jar import-junit src/test/resources/fixtures/mixed-results.xml
```

The invented report yields `Tests: 5 | passed=2 failed=1 errors=1 skipped=1`. `Tests` is the total execution count; `errors` counts errored executions. The CLI prints the source path, snapshot SHA-256, test class/name, available durations in seconds, messages, types, detail text, and XML diagnostic positions. Quote paths containing spaces. `--report <path>` is the compatibility alias for this in-memory import. To save the report, use `import-junit <path> --db <database-path>` with an optional `--run-id <id>`. See [SQLite storage](STORAGE.md).

Exit `0` means the report was imported, including reports containing failed tests. Exit `1` means reading, parsing, or saving to the requested database failed. Exit `2` means the command's arguments were invalid. Imports read the original XML file without modifying it.

## Supported structures

- The root must be `<testsuite>` or `<testsuites>`. Suite containers may nest. Testcases must be direct children of `<testsuite>`.
- Elements are recognized by local name, so namespace prefixes and default namespaces are accepted. Suite ancestry and document order are retained.
- A testcase needs a nonblank `name`. `classname` is optional; absence remains `Optional.empty()`. Missing suite names are represented by an empty string in the suite path. Names are not trimmed, normalized, or used to merge executions.
- A testcase's optional `time` attribute is interpreted as a nonnegative number of seconds and stored as `Optional<BigDecimal>`. Zero and decimal precision are retained without floating-point rounding. A missing value remains unknown; a blank, negative, nonnumeric, NaN, or infinite value is rejected with a source-aware diagnostic. Suite durations are not assigned to individual tests.
- A testcase without a `<failure>`, `<error>`, or `<skipped>` marker is `PASSED` under this input contract.
- `<failure>` yields `FAILED`; `<error>` yields `ERROR`; `<skipped>` yields `SKIPPED`. Details retain optional `type`/`message`, decoded body text, and their XML diagnostic position.
- Multiple failure/error details are retained in order. If any is an error, the execution's outcome is `ERROR`; otherwise it is `FAILED`. A skipped marker cannot be repeated or mixed with a failure/error: that ambiguous input is rejected.
- Detail text must be escaped XML or CDATA. Nested markup inside an outcome marker is rejected explicitly.
- Counts are derived from testcase executions. Suite `tests`, `failures`, `errors`, and `skipped` attributes are not trusted or summed, which avoids counting nested suites twice. Empty suites are valid and contain zero observed executions.
- Other subtrees, including properties, stdout/stderr, and producer metadata, are opaque. Their bytes remain in the original source but they do not add test executions or populate separate fields. Producer-specific outcome extensions and status attributes are not interpreted in this initial subset.
- Outside opaque metadata, unexpected placement of a recognized structural element, or non-whitespace text outside details, is rejected. Malformed XML never returns a partial report.

This contract covers the XML structures above. Add a fixture and define its behavior before expanding it. Declared-count discrepancy reporting, rerun/flaky extensions, and XML run metadata are later extensions. Local build and test logs have a separate [import command](LOG_IMPORT.md).

## Models

| Model | Meaning |
| --- | --- |
| `ReportSource` | Source name, a defensive copy of original bytes, and their SHA-256 hash. `bytes()` returns another copy. |
| `JUnitReport` | Source snapshot and an immutable ordered list of executions; `count(outcome)` derives counts. |
| `TestIdentity` | Optional reported classname and required test name. Several executions may share an identity; `displayName()` formats it for the CLI. |
| `TestCaseResult` | Suite path, explicit identity, optional duration in seconds, immutable details, and testcase XML position. Its `outcome()` is derived from the details. Earlier `className()`/`name()` accessors and the constructor without duration metadata remain available. |
| `TestOutcome` | `PASSED`, `FAILED`, `ERROR`, or `SKIPPED`. These describe observations, not root-cause categories. |
| `OutcomeDetail` | One reported failure, error, or skip marker with its decoded text and attributes. |
| `SourceLocation` | Source name and one-based SAX line/column diagnostics; `-1` means a position is unavailable. |
| `ReportParseException` | Rejected input, original source snapshot, diagnostic position, and cause. An unreadable file instead produces `IOException`, because no full source snapshot could be read. |

## Evidence semantics

`parse(Path)` reads the original file once into a snapshot and parses those bytes. It does not rewrite the file. Keeping bytes, rather than immediately converting them to a Java string, lets the XML parser honor the report's encoding declaration and preserves BOMs, CRLF/LF line endings, entity spellings, whitespace, and CDATA delimiters in the snapshot.

Parsed fields are a separate view. XML processing decodes entities and normalizes line endings/attribute whitespace according to XML rules. The parser does not additionally trim, normalize error messages, or group stack traces. A decoded message is not presented as a byte-for-byte copy of the original XML.

The SAX Locator reports approximate diagnostic positions at the end of an event. We snapshot a testcase/detail position when its opening-tag event is delivered. For a multiline opening tag, its line is typically where that tag ends. Use the original bytes as authoritative XML evidence. The [log importer](LOG_IMPORT.md) provides exact line ranges for its own text input. See the [SAX Locator contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.xml/org/xml/sax/Locator.html).

The returned report retains its snapshot in memory, and a parse exception retains rejected bytes too. With `--db`, the accepted snapshot and parsed results are also saved for later review. The parser loads the whole report into memory.

## XML resource handling

Secure processing is enabled. DOCTYPE declarations are rejected, external general/parameter entities and external DTD loading are disabled, external DTD/schema access is empty, and an entity resolver rejects external resources. XInclude is disabled and its elements are rejected, including inside otherwise ignored metadata. The parser fails if it cannot establish these settings. See Java's [SAXParserFactory](https://docs.oracle.com/en/java/javase/17/docs/api/java.xml/javax/xml/parsers/SAXParserFactory.html) and [XMLConstants](https://docs.oracle.com/en/java/javase/17/docs/api/java.xml/javax/xml/XMLConstants.html) documentation.

## Use the parser from Java

```java
import java.nio.file.Path;
import com.kunliang.citriage.report.JUnitReport;
import com.kunliang.citriage.report.JUnitXmlParser;
import com.kunliang.citriage.report.TestCaseResult;

JUnitReport report = new JUnitXmlParser().parse(Path.of("report.xml"));
for (TestCaseResult test : report.testCases()) {
    System.out.println(test.identity().displayName() + ": " + test.outcome());
    test.durationSeconds().ifPresent(seconds -> System.out.println("Seconds: " + seconds));
}
byte[] originalEvidence = report.source().bytes();
String snapshotHash = report.source().sha256();
```

The caller handles `IOException` and `ReportParseException`. Parsing is independent of the CLI and `System.exit()`. Tests in `JUnitXmlParserTest` exercise this API with the invented fixtures described in `src/test/resources/fixtures/README.md` and the unchanged earlier regression fixtures in `src/test/resources/junit/README.md`.
