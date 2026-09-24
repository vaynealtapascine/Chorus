-- The full export bundle (D-068, API.md §4): one row per job. Server state, not a projection:
-- no op writes it, rebuilds leave it alone, and a restart fails jobs that were running.
CREATE TABLE IF NOT EXISTS export_job (
  id           TEXT PRIMARY KEY,
  account_id   TEXT NOT NULL,
  kind         TEXT NOT NULL CHECK (kind IN ('full')),
  status       TEXT NOT NULL CHECK (status IN ('queued','running','done','failed','cancelled','expired')),
  phase        TEXT,                              -- ops | csv | blobs | zip
  done         INTEGER NOT NULL DEFAULT 0,        -- items (ops, then tables, then files)
  total        INTEGER NOT NULL DEFAULT 0,
  bytes        INTEGER NOT NULL DEFAULT 0,        -- written so far, then the zip's size
  error        TEXT,
  file_name    TEXT,                              -- chorus-<handle>-<YYYYMMDD>.zip
  download_key TEXT,                              -- capability in the download URL (random, 32 hex)
  created_at   INTEGER NOT NULL,
  finished_at  INTEGER,
  expires_at   INTEGER,
  downloaded_at INTEGER
);
CREATE INDEX IF NOT EXISTS export_job_by_account ON export_job(account_id, created_at);
