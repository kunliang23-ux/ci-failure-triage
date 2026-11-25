CREATE TABLE run_metadata_v2 (
    run_id TEXT NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
    field TEXT NOT NULL CHECK (field IN (
        'COMMIT', 'ENVIRONMENT', 'REPOSITORY', 'WORKFLOW', 'JOB', 'ATTEMPT', 'EXECUTED_AT',
        'TEST_COMMAND', 'TEST_CONFIGURATION', 'OS', 'ARCHITECTURE', 'TOOLCHAIN', 'DEPENDENCY_FINGERPRINT',
        'CONCLUSION', 'COMPLETED_AT'
    )),
    value TEXT NOT NULL,
    PRIMARY KEY (run_id, field)
) STRICT;

INSERT INTO run_metadata_v2 (run_id, field, value) SELECT run_id, field, value FROM run_metadata;
DROP TABLE run_metadata;
ALTER TABLE run_metadata_v2 RENAME TO run_metadata;
