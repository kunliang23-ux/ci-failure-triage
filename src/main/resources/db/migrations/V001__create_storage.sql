CREATE TABLE runs (
    run_id TEXT PRIMARY KEY NOT NULL,
    first_imported_at_ms INTEGER NOT NULL
) STRICT;

CREATE TABLE run_metadata (
    run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
    field TEXT NOT NULL CHECK (field IN (
        'COMMIT', 'ENVIRONMENT', 'REPOSITORY', 'WORKFLOW', 'JOB', 'ATTEMPT', 'EXECUTED_AT',
        'TEST_COMMAND', 'TEST_CONFIGURATION', 'OS', 'ARCHITECTURE', 'TOOLCHAIN', 'DEPENDENCY_FINGERPRINT'
    )),
    value TEXT NOT NULL,
    PRIMARY KEY (run_id, field)
) STRICT;

CREATE TABLE sources (
    source_id INTEGER PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('JUNIT_XML', 'LOG')),
    original_path TEXT NOT NULL,
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    original_bytes BLOB NOT NULL,
    imported_at_ms INTEGER NOT NULL,
    UNIQUE (run_id, kind, sha256)
) STRICT;

CREATE TABLE test_executions (
    source_id INTEGER NOT NULL REFERENCES sources(source_id) ON DELETE CASCADE,
    test_index INTEGER NOT NULL CHECK (test_index >= 0),
    class_name TEXT,
    test_name TEXT NOT NULL,
    duration_seconds TEXT,
    outcome TEXT NOT NULL CHECK (outcome IN ('PASSED', 'FAILED', 'ERROR', 'SKIPPED')),
    xml_line INTEGER NOT NULL CHECK (xml_line > 0 OR xml_line = -1),
    xml_column INTEGER NOT NULL CHECK (xml_column > 0 OR xml_column = -1),
    PRIMARY KEY (source_id, test_index)
) STRICT;

CREATE TABLE suite_path_elements (
    source_id INTEGER NOT NULL,
    test_index INTEGER NOT NULL,
    depth INTEGER NOT NULL CHECK (depth >= 0),
    suite_name TEXT NOT NULL,
    PRIMARY KEY (source_id, test_index, depth),
    FOREIGN KEY (source_id, test_index) REFERENCES test_executions(source_id, test_index) ON DELETE CASCADE
) STRICT;

CREATE TABLE outcome_details (
    source_id INTEGER NOT NULL,
    test_index INTEGER NOT NULL,
    detail_index INTEGER NOT NULL CHECK (detail_index >= 0),
    outcome TEXT NOT NULL CHECK (outcome IN ('FAILED', 'ERROR', 'SKIPPED')),
    type TEXT,
    message TEXT,
    body TEXT NOT NULL,
    xml_line INTEGER NOT NULL CHECK (xml_line > 0 OR xml_line = -1),
    xml_column INTEGER NOT NULL CHECK (xml_column > 0 OR xml_column = -1),
    PRIMARY KEY (source_id, test_index, detail_index),
    FOREIGN KEY (source_id, test_index) REFERENCES test_executions(source_id, test_index) ON DELETE CASCADE
) STRICT;

CREATE TABLE log_findings (
    source_id INTEGER NOT NULL REFERENCES sources(source_id) ON DELETE CASCADE,
    finding_index INTEGER NOT NULL CHECK (finding_index >= 0),
    category TEXT NOT NULL CHECK (category IN (
        'ASSERTION_ERROR', 'MISSING_DEPENDENCY', 'COMPILATION_ERROR', 'TIMEOUT', 'UNKNOWN'
    )),
    rule_id TEXT NOT NULL,
    explanation TEXT NOT NULL,
    class_name TEXT,
    test_name TEXT,
    first_line INTEGER NOT NULL CHECK (first_line > 0),
    last_line INTEGER NOT NULL CHECK (last_line >= first_line),
    PRIMARY KEY (source_id, finding_index),
    CHECK (test_name IS NOT NULL OR class_name IS NULL)
) STRICT;

CREATE TABLE failure_groups (
    source_id INTEGER NOT NULL REFERENCES sources(source_id) ON DELETE CASCADE,
    group_index INTEGER NOT NULL CHECK (group_index >= 0),
    display_id TEXT NOT NULL,
    category TEXT NOT NULL CHECK (category IN (
        'ASSERTION_ERROR', 'MISSING_DEPENDENCY', 'COMPILATION_ERROR', 'TIMEOUT', 'UNKNOWN'
    )),
    signature TEXT NOT NULL,
    PRIMARY KEY (source_id, group_index),
    UNIQUE (source_id, display_id)
) STRICT;

CREATE TABLE group_members (
    source_id INTEGER NOT NULL,
    group_index INTEGER NOT NULL,
    member_index INTEGER NOT NULL CHECK (member_index >= 0),
    finding_index INTEGER NOT NULL,
    PRIMARY KEY (source_id, group_index, member_index),
    UNIQUE (source_id, finding_index),
    FOREIGN KEY (source_id, group_index) REFERENCES failure_groups(source_id, group_index) ON DELETE CASCADE,
    FOREIGN KEY (source_id, finding_index) REFERENCES log_findings(source_id, finding_index) ON DELETE CASCADE
) STRICT;
