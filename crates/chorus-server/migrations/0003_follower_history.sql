-- M6.3: what each follower was shown, appended at reveal time only (NOTIFICATIONS.md §5 rule 4).
-- Follower history and stats are computed from this, never from the raw front. Additive only.
CREATE TABLE follower_front_log (
  follower_account_id TEXT NOT NULL, target_account_id TEXT NOT NULL,
  revealed_at INTEGER NOT NULL,
  displayed TEXT NOT NULL CHECK (json_valid(displayed)),   -- notify::Displayed (fuzzed time)
  entries TEXT NOT NULL CHECK (json_valid(entries))         -- shown entries, without avatars
);
CREATE INDEX follower_front_log_by_pair ON follower_front_log (follower_account_id, target_account_id, revealed_at);
