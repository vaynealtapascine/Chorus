-- Polls (D-081, D-086, docs/POLLS.md). A poll's definition rides on its parent message/post and
-- never changes. Votes are private to the voter's account; tallies are counts-only and derived.
ALTER TABLE message ADD COLUMN poll TEXT CHECK (poll IS NULL OR json_valid(poll));
ALTER TABLE post ADD COLUMN poll TEXT CHECK (poll IS NULL OR json_valid(poll));
CREATE TABLE poll_vote (
  poll_id TEXT NOT NULL, account_id TEXT NOT NULL, member_id TEXT NOT NULL,
  choices TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(choices)),
  hlc TEXT NOT NULL,
  PRIMARY KEY (poll_id, account_id, member_id)
) WITHOUT ROWID;
CREATE TABLE poll_tally (
  poll_id TEXT PRIMARY KEY,
  counts TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(counts)),
  voters INTEGER NOT NULL DEFAULT 0,
  hlc TEXT NOT NULL
) WITHOUT ROWID;
