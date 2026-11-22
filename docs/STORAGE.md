# Save and reopen runs with SQLite

Build with `mvn clean verify` and run the JAR from the project root. This example uses temporary copies of the fixtures so the saved evidence can be read after the copies are removed.

```powershell
$demo = New-Item -ItemType Directory -Path (Join-Path 'target' ('storage-demo-' + [guid]::NewGuid()))
$db = Join-Path $demo.FullName 'triage.sqlite'
$xml = Join-Path $demo.FullName 'report.xml'
$log = Join-Path $demo.FullName 'build.log'
Copy-Item -LiteralPath 'src/test/resources/fixtures/mixed-results.xml' -Destination $xml
Copy-Item -LiteralPath 'src/test/resources/fixtures/logs/grouping/grouped-failures.log' -Destination $log

java -jar target/ci-failure-triage.jar import-junit $xml --db $db --run-id demo --commit abc1234
java -jar target/ci-failure-triage.jar import-log $log --db $db --run-id demo --environment 'sample CI' --group
java -jar target/ci-failure-triage.jar runs --db $db
java -jar target/ci-failure-triage.jar import-log $log --db $db --run-id demo --group
Remove-Item -LiteralPath $xml, $log
java -jar target/ci-failure-triage.jar show-run demo --db $db --group
```

The fixture imports save one run with one JUnit report, five JUnit executions, one log, six log findings, and three log groups. The repeat import reports `Storage: artifact already saved.` The reopened run prints the JUnit outcomes and XML diagnostic positions, then the log groups and each member's original numbered evidence. Omit `--group` on `show-run` for the individual log findings. `runs` lists the saved run IDs, first import times, commit and environment, and separate JUnit and log counts.

Supply import options after the source path and `show-run` options after the run ID. Options can appear in any order once per command. `--group` is a switch. With `--db`, either importer accepts `--commit` and `--environment`. `import-log` also accepts those flags for an in-memory import. The path-only `--report <path>` alias still performs an in-memory JUnit import. Use an existing parent directory for a new database. Files ending in `.db`, `.sqlite`, or `.sqlite3` and their SQLite sidecars are ignored by Git.

| Exit | Output | Meaning |
| --- | --- | --- |
| `0` | stdout | Import or query succeeded, including imports with observed failures or previously saved content. |
| `1` | stderr | Source read or parse error, database error, conflicting known metadata, or missing database or run. |
| `2` | stderr | Invalid arguments with `--help` guidance. |

Query commands open existing databases read-only. A missing path produces an error without creating a database. An import parses the source before opening its destination database. A database failure leaves stdout empty.

## Run identity and metadata

Use the same explicit `--run-id` for artifacts from one execution. The ID is a case-sensitive, nonblank string scoped to the database. Omitting `--run-id` when saving creates a fresh UUID and prints it. Both importers print the run ID and whether the source was newly saved or already present.

The unique source key is `(run_id, kind, sha256)`. Retrying an import in the same run keeps the first saved source path, bytes, parsed results, and import time. A different path with the same kind and hash is the same source in that run. The same bytes in a different run are saved as separate observations because separate executions can produce identical output. Both source kinds can coexist in one run, and each XML testcase element is a separate execution, including repeated names.

Execution metadata is held per run. Each supplied field can fill an unknown value. Repeating the exact value is valid, and a conflicting known value rolls back the entire import. An accepted duplicate can still fill previously unknown metadata. The CLI supplies commit and environment. The schema also has fields for repository, workflow, job, attempt, execution time, test command and configuration, OS, architecture, toolchain, and dependency fingerprint. Missing fields have no row and display as `unknown`. The recorded import time is the local act of saving and is stored separately from the explicitly supplied execution time.

## Schema and evidence

| Tables | Saved data |
| --- | --- |
| `runs`, `run_metadata` | Run ID, first import time, and known execution metadata fields. |
| `sources` | Run, source kind, original path, original bytes as a BLOB, SHA-256, and artifact import time. |
| `test_executions`, `suite_path_elements`, `outcome_details` | Each execution in document order, identity, suite ancestry, outcome, exact optional decimal duration, decoded markers, and approximate SAX positions. |
| `log_findings` | Each finding in source order with category, rule, explanation, optional identity, and inclusive one-based evidence range. |
| `failure_groups`, `group_members` | Source-scoped display IDs, categories, signatures, group order, and ordered references to findings from the same source. Occurrence and known-test counts derive from those references. |

SQLite stores decimal durations as `TEXT` using `BigDecimal`'s round-trip representation. Optional XML and log fields remain optional. The original bytes preserve line endings, encodings, entity spellings, and other source details. On reopening, the repository checks each source SHA-256 against the stored bytes. It loads the saved JUnit observations directly and decodes the saved UTF-8 log bytes to recover exact evidence ranges. Stored rules, findings, and groups are used as saved. Every persisted log has groups available, including logs imported with individual output. Group display IDs can repeat across artifacts or runs. See the [XML contract](JUNIT_XML_IMPORT.md), [log contract](LOG_IMPORT.md), and [grouping policy](GROUPING.md).

## Migrations and packaging

The first saved import applies `db/migrations/V001__create_storage.sql`. The database uses SQLite `application_id` to recognize this application and `user_version` to record the applied version. Write connections apply pending migrations in order. Schema changes, run metadata, source insertion, and observations commit together. Imports start an immediate write transaction so concurrent retries serialize against the unique source key. Every application connection enables foreign keys before its transaction. A database with a newer version is rejected with an error that states the version. Querying an older version that needs migration requires a saved import first. See the [SQLite PRAGMA reference](https://sqlite.org/pragma.html).

Xerial SQLite JDBC 3.54.1.0 is pinned and works with Java 17. Maven Shade 3.6.2 packages the driver, JDBC service descriptor, native resources, and migrations with the application entry point in `target/ci-failure-triage.jar`. The built CLI works offline. See the [SQLite JDBC release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0) and [Maven Shade documentation](https://maven.apache.org/plugins-archives/maven-shade-plugin-3.6.2/plugin-info.html).
