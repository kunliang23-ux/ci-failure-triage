# Sanitized log fixtures

All names, errors, source paths, repository names, and timings are invented. Line ranges are one based and inclusive. Blank lines count toward the ranges.

| Fixture | Expected findings in source order |
| --- | --- |
| `assertion.log` | `ASSERTION_ERROR` from `java-assertion`, lines 3–5. The Surefire failure header identifies `example.CartTest#calculatesTotal`. Later test and build summaries add no findings. |
| `dependency.log` | `MISSING_DEPENDENCY` from `maven-dependency-resolution`, lines 3–5. The failure banner appears before the detailed error. |
| `compilation.log` | `COMPILATION_ERROR` from `java-compiler`, lines 4–6. The compiler location and notes take precedence over the summary. |
| `timeout.log` | `TIMEOUT` from `java-timeout`, lines 3–5. The Surefire header identifies `example.InventoryTest#waitsForInventory`. |
| `unknown.log` | `UNKNOWN` from `unclassified-error`, line 2. The error line provides evidence of a failure with insufficient detail to classify it further. |
| `passing.log` | Zero findings. The passing test's timeout configuration is informational. |
| `mixed.log` | `ASSERTION_ERROR` on lines 2–4, `MISSING_DEPENDENCY` on line 6, `UNKNOWN` on line 7, and `COMPILATION_ERROR` on line 9. The build summary adds no finding. |

Tests create additional temporary inputs for raw UTF-8 bytes, LF and CRLF, empty logs, missing files, and filenames containing spaces. Keep checked-in fixtures free of personal paths and credentials.
