CREATE TABLE github_origins (
    run_id TEXT PRIMARY KEY NOT NULL REFERENCES runs(run_id) ON DELETE CASCADE,
    repository TEXT NOT NULL,
    repository_id INTEGER NOT NULL CHECK (repository_id > 0),
    workflow_run_id INTEGER NOT NULL CHECK (workflow_run_id > 0),
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    job_id INTEGER NOT NULL CHECK (job_id > 0),
    artifact_id INTEGER NOT NULL CHECK (artifact_id > 0),
    triggering_head TEXT,
    manifest_name TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL CHECK (length(manifest_sha256) = 64),
    manifest_bytes BLOB NOT NULL,
    UNIQUE (repository_id, workflow_run_id, attempt, job_id)
) STRICT;
