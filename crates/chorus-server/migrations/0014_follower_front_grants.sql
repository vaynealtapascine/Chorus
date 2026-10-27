-- D-084: one revealed view per (follower, target, front). '' is Body, so every existing row and
-- log entry stays Body's. front_name is the granted front's name as revealed (never read live).
CREATE TABLE follower_front_view_v2 (
  follower_account_id TEXT NOT NULL, target_account_id TEXT NOT NULL, front_id TEXT NOT NULL DEFAULT '',
  front_name TEXT, entries TEXT NOT NULL, displayed_since INTEGER, revealed_at INTEGER NOT NULL,
  PRIMARY KEY (follower_account_id, target_account_id, front_id)
);
INSERT INTO follower_front_view_v2 (follower_account_id, target_account_id, entries, displayed_since, revealed_at)
  SELECT follower_account_id, target_account_id, entries, displayed_since, revealed_at FROM follower_front_view;
DROP TABLE follower_front_view;
ALTER TABLE follower_front_view_v2 RENAME TO follower_front_view;
ALTER TABLE follower_front_log ADD COLUMN front_id TEXT NOT NULL DEFAULT '';
