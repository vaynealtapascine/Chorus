-- Incremental projections (SPEC §9 ingest budget).
--
-- Read states are kept up to date one op at a time: besides the effective
-- position, each row keeps the furthest mark after the manual set and that set
-- (chorus_core::model::read_best / read_effective). Rows from before this migration have
-- state_ok = 0 and are recomputed from the log the next time a read op touches them. Additive only.
ALTER TABLE read_state ADD COLUMN mark_at INTEGER;
ALTER TABLE read_state ADD COLUMN mark_id TEXT;
ALTER TABLE read_state ADD COLUMN mark_hlc TEXT;
ALTER TABLE read_state ADD COLUMN set_at INTEGER;
ALTER TABLE read_state ADD COLUMN set_id TEXT;
ALTER TABLE read_state ADD COLUMN set_hlc TEXT;
ALTER TABLE read_state ADD COLUMN state_ok INTEGER NOT NULL DEFAULT 0;

-- A live switch folds one step on top of the open intervals (project.rs append_front), and daily
-- totals are redone from the intervals that reach past a day; both look intervals up by end.
CREATE INDEX fi_open ON front_interval (account_id) WHERE end_at IS NULL;
CREATE INDEX fi_account_end ON front_interval (account_id, end_at);
