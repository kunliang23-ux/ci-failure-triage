# Sanitized Day 2 inputs

All names, messages, stack traces, and paths are invented. A report describing failed tests is input data; the JUnit tests pass when they interpret it accurately.

| File | Expected result |
| --- | --- |
| `passing.xml` | 2 passed; includes zero duration and absent class metadata. |
| `mixed-results.xml` | 5 total: 2 passed, 1 failed, 1 errored, 1 skipped. Decimal durations, decoded messages, CDATA, and stack traces are preserved. |
| `nested-suites.xml` | 3 total: 2 passed, 1 failed. Repeated identities and suite ancestry remain distinct; parent suite totals must not be added to child totals. |
| `malformed.xml` | Reject the whole report with a source/position diagnostic. |
| `doctype.xml` | Reject the DOCTYPE before resolving its invented external entity. |

The earlier `../junit/` fixtures remain unchanged as compatibility and regression inputs. Keep original evidence separate from parsed interpretations.
