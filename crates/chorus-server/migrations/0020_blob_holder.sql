-- Accounts that sent a stored file's bytes again after another account uploaded it (blobs.rs).
-- Holding a file is what lets an op name it (ingest `unproven_blob`); additive only.
CREATE TABLE blob_holder (
  hash TEXT NOT NULL, account_id TEXT NOT NULL,
  received INTEGER NOT NULL DEFAULT 0,             -- bytes compared so far
  matches INTEGER NOT NULL DEFAULT 1,              -- every chunk so far equalled the stored file
  is_complete INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (hash, account_id)
);
