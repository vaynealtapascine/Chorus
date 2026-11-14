-- D-132: Handover and Notes to ourselves (docs/DATA_MODEL.md §4, "Special channels"). Additive.
-- A channel's role, set when it is made (`channel.create {special}`), internal spaces only.
ALTER TABLE channel ADD COLUMN special TEXT CHECK (special IS NULL OR special IN ('handover','notes'));
-- A handover's delivery and checklist (`message.send {handover}`), fixed at sending like a poll.
ALTER TABLE message ADD COLUMN handover TEXT CHECK (handover IS NULL OR json_valid(handover));
CREATE INDEX IF NOT EXISTS channel_special ON channel(space_id, special) WHERE special IS NOT NULL;
-- Who took a handover (`handover.ack`), LWW per (message, member); derived, rebuilt from ops.
CREATE TABLE IF NOT EXISTS handover_ack (
  message_id TEXT NOT NULL,
  member_id TEXT NOT NULL,
  acked_at INTEGER NOT NULL,
  hlc TEXT NOT NULL,
  PRIMARY KEY (message_id, member_id)
) WITHOUT ROWID;
-- Ticks on a handover's checklist (`checklist.set`), LWW per (message, item); derived.
CREATE TABLE IF NOT EXISTS checklist_item_state (
  message_id TEXT NOT NULL,
  item_id TEXT NOT NULL,
  done INTEGER NOT NULL,
  member_id TEXT,
  hlc TEXT NOT NULL,
  PRIMARY KEY (message_id, item_id)
) WITHOUT ROWID;
