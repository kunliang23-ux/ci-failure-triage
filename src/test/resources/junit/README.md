# Sanitized JUnit XML fixtures

Every name, message, stack frame, and path in these files is invented. There are no production reports, credentials, or real infrastructure identifiers.

| Fixture | Expected observation |
| --- | --- |
| `mixed-suite.xml` | Four executions: one passed, one failed, one error, one skipped. The deliberately incorrect declared counters must not override the actual testcases. |
| `passing-suite.xml` | Two passed executions; classname is absent for one. |
| `nested-suites.xml` | Three executions with repeated names, distinct suite paths, namespace prefixes, and multiple details on one error outcome. |
| `malformed.xml` | Reject the entire document with a source/position diagnostic; never return partial results. |
| `doctype.xml` | Reject the DOCTYPE before resolving its invented external entity. |

Keep these original files unchanged when interpreting their contents. Add a new fixture for a new case. Parser tests also create small in-memory reports for encoding, line-ending, and unsupported-structure checks, and temporary synthetic files for external-resource rejection tests.
