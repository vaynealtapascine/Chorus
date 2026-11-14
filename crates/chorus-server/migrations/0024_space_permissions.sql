-- D-133: shared-space permissions (docs/DATA_MODEL.md §4.4). Additive only.
-- Space-wide overrides, like channel_permission but for every channel of the space, plus the
-- whole-space permissions `invite` and `history` (`space.set_permission`, derived, rebuilt).
CREATE TABLE IF NOT EXISTS space_permission (
  space_id TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type IN ('role','account')),
  target_id TEXT NOT NULL,
  allow TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(allow)),
  deny TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(deny)),
  hlc TEXT NOT NULL,
  PRIMARY KEY (space_id, target_type, target_id)
) WITHOUT ROWID;
-- What each account shares in a space (`space.set_sharing`, its own row; LWW per field; derived).
-- NULL = the default: member names shown, fronting status off, a 15-minute delay.
CREATE TABLE IF NOT EXISTS space_sharing (
  space_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  member_names INTEGER,
  fronts INTEGER,
  fronts_delay_min INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}',
  PRIMARY KEY (space_id, account_id)
) WITHOUT ROWID;
-- The fronting status each account shares with a space, as revealed (D-133, NOTIFICATIONS §5
-- rule 11). Server state like follower_front_view, never rebuilt from ops: `entries` changes only
-- when `pending` comes due.
CREATE TABLE IF NOT EXISTS space_front_view (
  space_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  entries TEXT,
  revealed_at INTEGER,
  pending TEXT,
  due_at INTEGER,
  PRIMARY KEY (space_id, account_id)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS space_front_view_due ON space_front_view(due_at) WHERE due_at IS NOT NULL;
-- The server time of the join that began the account's current stretch in the space: the
-- earliest `space.join` received after its latest `space.leave` (by HLC). History from before it
-- is readable only with the `history` permission. Projected from the log by `project.rs`.
ALTER TABLE space_member ADD COLUMN joined_at INTEGER;
UPDATE space_member SET joined_at = (
  SELECT min(o.received_at) FROM op o
  WHERE o.entity_id = space_member.space_id AND o.kind = 'space.join' AND o.status = 'applied'
    AND json_extract(o.payload, '$.account_id') = space_member.account_id
    AND (space_member.left_hlc IS NULL OR o.hlc > space_member.left_hlc))
WHERE joined_at IS NULL;
