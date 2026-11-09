-- D-136: polls close and nudge (docs/POLLS.md). Additive: two derived tables, rebuilt from ops.
-- `closes_at` itself rides in the existing `message.poll` / `post.poll` JSON.
-- The author's early close: the earliest `message.poll_close` / `post.poll_close` wins.
CREATE TABLE IF NOT EXISTS poll_close (
  poll_id TEXT PRIMARY KEY,
  closed_at INTEGER NOT NULL,
  hlc TEXT NOT NULL
) WITHOUT ROWID;
-- Our own members nudged to vote (`poll.nudge`, account-scoped like `poll.vote`).
CREATE TABLE IF NOT EXISTS poll_nudge (
  poll_id TEXT NOT NULL, account_id TEXT NOT NULL, member_id TEXT NOT NULL,
  nudged_at INTEGER NOT NULL, by_member_id TEXT, hlc TEXT NOT NULL,
  PRIMARY KEY (poll_id, account_id, member_id)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS poll_nudge_recent ON poll_nudge(account_id, nudged_at);
