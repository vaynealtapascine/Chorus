-- Post alerts (D-106): another account asks to hear about one member's new posts and entries;
-- the member's account answers. Server-written primary data like `notification` (no ops).
CREATE TABLE post_alert (
  subscriber_account_id TEXT NOT NULL,
  member_id TEXT NOT NULL,
  owner_account_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('requested', 'active', 'declined')),
  created_at INTEGER NOT NULL,
  decided_at INTEGER,
  PRIMARY KEY (subscriber_account_id, member_id)
);
CREATE INDEX post_alert_member ON post_alert(member_id, status);
CREATE INDEX post_alert_owner ON post_alert(owner_account_id, status);
