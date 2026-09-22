-- Chorus schema v1 (docs/DATA_MODEL.md §2, §4).
-- Projected tables are rebuilt from `op` by `chorus-server rebuild`; `op`, `account`, `device`,
-- `invite`, `blob`, `api_token`, `webhook`, `notification` and `server_meta` are primary data.
-- Data columns are nullable on purpose: rows are written by re-projecting an entity's ops, and
-- validation happens on ops, not on rows.

CREATE TABLE server_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

-- ─── the op log ─────────────────────────────────────────────────────────
CREATE TABLE op (
  seq            INTEGER PRIMARY KEY,
  id             TEXT NOT NULL UNIQUE,
  scope          TEXT NOT NULL,
  kind           TEXT NOT NULL,
  entity_id      TEXT,
  payload        TEXT NOT NULL CHECK (json_valid(payload)),
  v              INTEGER NOT NULL DEFAULT 1,
  hlc            TEXT NOT NULL,
  account_id     TEXT NOT NULL,
  device_id      TEXT NOT NULL,
  member_id      TEXT,
  occurred_at    INTEGER NOT NULL,
  device_at      INTEGER NOT NULL,
  tz_offset_min  INTEGER NOT NULL,
  mono           INTEGER,
  boot_id        TEXT,
  time_source    TEXT NOT NULL DEFAULT 'auto' CHECK (time_source IN ('auto','user')),
  time_suspect   INTEGER NOT NULL DEFAULT 0,
  seen_seq       INTEGER NOT NULL DEFAULT 0,
  received_at    INTEGER NOT NULL,
  restored       INTEGER NOT NULL DEFAULT 0,
  status         TEXT NOT NULL DEFAULT 'applied' CHECK (status IN ('applied','rejected','superseded'))
);
CREATE INDEX op_scope_seq ON op(scope, seq);
CREATE INDEX op_entity ON op(entity_id);
CREATE INDEX op_kind ON op(kind);

-- ─── accounts & devices ─────────────────────────────────────────────────
CREATE TABLE account (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('system','person')),
  handle TEXT UNIQUE,
  display_name TEXT,
  avatar_blob TEXT,
  is_admin INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  settings TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(settings)),
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE device (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES account(id),
  short_id TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  platform TEXT NOT NULL CHECK (platform IN ('android','web','cli','token')),
  public_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  clock_offset_ms INTEGER NOT NULL DEFAULT 0,
  push_endpoint TEXT,
  revoked_at INTEGER
);

CREATE TABLE session (
  token_hash TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES device(id),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE auth_nonce (
  nonce TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE invite (
  code_hash TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('system','person','device')),
  account_id TEXT,
  created_by TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  max_uses INTEGER NOT NULL DEFAULT 1,
  uses INTEGER NOT NULL DEFAULT 0
);

-- Which scopes an account may read/write (server-maintained from space membership).
CREATE TABLE scope_access (
  account_id TEXT NOT NULL,
  scope TEXT NOT NULL,
  PRIMARY KEY (account_id, scope)
);

-- ─── systems, members, groups ───────────────────────────────────────────
CREATE TABLE system (
  account_id TEXT PRIMARY KEY,
  name TEXT, tag TEXT, description TEXT, description_entities TEXT, color TEXT, timezone TEXT,
  terminology TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(terminology)),
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE member (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  name TEXT, display_name TEXT, pronouns TEXT,
  description TEXT, description_entities TEXT,
  color TEXT, avatar_blob TEXT, banner_blob TEXT, birthday TEXT,
  sigils TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(sigils)),
  proxy_tags TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(proxy_tags)),
  pinned_post_id TEXT, sort_key TEXT,
  is_self INTEGER NOT NULL DEFAULT 0,
  is_locked INTEGER NOT NULL DEFAULT 0,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}' CHECK (json_valid(visibility)),
  field_visibility TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(field_visibility)),
  notify_policy TEXT NOT NULL DEFAULT '{"announce":"everyone","announce_leaving":false}' CHECK (json_valid(notify_policy)),
  short_id TEXT, pk_id TEXT,
  created_at INTEGER NOT NULL,
  archived_at INTEGER, deleted_at INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX member_account ON member(account_id);

CREATE TABLE member_group (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  kind TEXT CHECK (kind IS NULL OR kind IN ('subsystem','group')),
  parent_id TEXT, effective_parent_id TEXT,
  name TEXT, description TEXT, description_entities TEXT,
  color TEXT, icon TEXT, avatar_blob TEXT,
  can_front INTEGER NOT NULL DEFAULT 1,
  sort_key TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  created_at INTEGER NOT NULL, deleted_at INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE group_membership (
  group_id TEXT NOT NULL, member_id TEXT NOT NULL,
  added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (group_id, member_id)
);

CREATE TABLE custom_state (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT, description TEXT, color TEXT, icon TEXT,
  sort_key TEXT, created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE field_def (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT,
  type TEXT CHECK (type IS NULL OR type IN ('text','long_text','number','date','select','multi_select','boolean','color','url','member_ref','rating')),
  options TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(options)),
  default_visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  sort_key TEXT, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE field_value (
  member_id TEXT NOT NULL, field_id TEXT NOT NULL,
  value TEXT NOT NULL CHECK (json_valid(value)),
  value_text TEXT GENERATED ALWAYS AS (json_extract(value, '$')) VIRTUAL,
  hlc TEXT NOT NULL,
  PRIMARY KEY (member_id, field_id)
);

-- ─── front ──────────────────────────────────────────────────────────────
CREATE TABLE switch (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('switch','add','remove','update')),
  occurred_at INTEGER NOT NULL,
  tz_offset_min INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  based_on TEXT,
  entries TEXT NOT NULL CHECK (json_valid(entries)),
  resulting_front TEXT NOT NULL CHECK (json_valid(resulting_front)),
  note TEXT,
  notify TEXT NOT NULL DEFAULT 'default' CHECK (notify IN ('default','silent','now','extra_delay')),
  was_offline INTEGER NOT NULL DEFAULT 0,
  retracted INTEGER NOT NULL DEFAULT 0,
  amended INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX switch_account_time ON switch(account_id, occurred_at);

CREATE TABLE front_interval (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  subject_type TEXT NOT NULL CHECK (subject_type IN ('member','group','state')),
  subject_id TEXT NOT NULL,
  level TEXT NOT NULL CHECK (level IN ('front','cocon','present')),
  is_primary INTEGER NOT NULL,
  position INTEGER NOT NULL,
  start_at INTEGER NOT NULL,
  end_at INTEGER,
  start_switch_id TEXT NOT NULL,
  end_switch_id TEXT,
  start_tz_offset_min INTEGER NOT NULL
);
CREATE INDEX fi_account_time ON front_interval(account_id, start_at);
CREATE INDEX fi_subject ON front_interval(subject_id, start_at);

CREATE TABLE front_daily (
  account_id TEXT NOT NULL, day TEXT NOT NULL,
  subject_type TEXT NOT NULL, subject_id TEXT NOT NULL, level TEXT NOT NULL,
  seconds INTEGER NOT NULL, as_primary_seconds INTEGER NOT NULL,
  PRIMARY KEY (account_id, day, subject_type, subject_id, level)
);

CREATE TABLE front_review (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
  switch_a TEXT NOT NULL, switch_b TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  resolution TEXT CHECK (resolution IN ('keep_both','keep_a','keep_b','merge')),
  resolved_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

-- ─── spaces, channels, messages ─────────────────────────────────────────
CREATE TABLE space (
  id TEXT PRIMARY KEY,
  kind TEXT CHECK (kind IS NULL OR kind IN ('internal','shared','dm')),
  owner_account_id TEXT NOT NULL,
  name TEXT, icon TEXT, color TEXT, description TEXT,
  settings TEXT NOT NULL DEFAULT '{}',
  roles TEXT NOT NULL DEFAULT '[]',
  created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE space_member (
  space_id TEXT NOT NULL, account_id TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',
  joined_hlc TEXT, left_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (joined_hlc IS NOT NULL AND (left_hlc IS NULL OR joined_hlc > left_hlc)) STORED,
  PRIMARY KEY (space_id, account_id)
);

CREATE TABLE channel (
  id TEXT PRIMARY KEY,
  space_id TEXT,
  kind TEXT CHECK (kind IS NULL OR kind IN ('text','thread','member_dm')),
  category TEXT, name TEXT, topic TEXT, icon TEXT, color TEXT,
  parent_message_id TEXT,
  member_ids TEXT CHECK (member_ids IS NULL OR json_valid(member_ids)),
  settings TEXT NOT NULL DEFAULT '{}',
  sort_key TEXT,
  created_at INTEGER NOT NULL, archived_at INTEGER, deleted_at INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE channel_permission (
  channel_id TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type IN ('role','account')),
  target_id TEXT NOT NULL,
  allow TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(allow)),
  deny TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(deny)),
  hlc TEXT NOT NULL,
  PRIMARY KEY (channel_id, target_type, target_id)
);

CREATE TABLE message (
  id TEXT PRIMARY KEY,
  channel_id TEXT,
  account_id TEXT,
  device_id TEXT,
  occurred_at INTEGER NOT NULL,
  tz_offset_min INTEGER NOT NULL DEFAULT 0,
  received_at INTEGER,
  sent_offline INTEGER NOT NULL DEFAULT 0,
  kind TEXT NOT NULL DEFAULT 'text',
  text TEXT NOT NULL DEFAULT '',
  entities TEXT NOT NULL DEFAULT '[]',
  cw TEXT,
  reply_to_id TEXT,
  quote TEXT,
  forward_of_id TEXT,
  forward_snapshot TEXT,
  visibility TEXT,
  revision_count INTEGER NOT NULL DEFAULT 1,
  edited_at INTEGER,
  pinned_at INTEGER, pinned_by_member TEXT,
  deleted_at INTEGER,
  thread_channel_id TEXT,
  char_count INTEGER GENERATED ALWAYS AS (length(text)) VIRTUAL,
  clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX message_channel_time ON message(channel_id, occurred_at, id);
CREATE INDEX message_reply ON message(reply_to_id);

CREATE TABLE message_segment (
  message_id TEXT NOT NULL, idx INTEGER NOT NULL,
  offset_u16 INTEGER NOT NULL, length_u16 INTEGER NOT NULL, text TEXT NOT NULL,
  PRIMARY KEY (message_id, idx)
);
CREATE TABLE message_segment_author (
  message_id TEXT NOT NULL, idx INTEGER NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (message_id, idx, member_id)
);
CREATE INDEX msa_member ON message_segment_author(member_id);

CREATE TABLE message_author (
  message_id TEXT NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (message_id, member_id)
);
CREATE INDEX message_author_member ON message_author(member_id);

CREATE TABLE message_revision (
  message_id TEXT NOT NULL, rev INTEGER NOT NULL,
  text TEXT NOT NULL, entities TEXT NOT NULL, cw TEXT,
  edited_at INTEGER NOT NULL, hlc TEXT NOT NULL, device_id TEXT NOT NULL,
  PRIMARY KEY (message_id, rev)
);

CREATE TABLE reaction (
  target_type TEXT NOT NULL CHECK (target_type IN ('message','post')),
  target_id TEXT NOT NULL, emoji TEXT NOT NULL, member_id TEXT NOT NULL,
  added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (target_type, target_id, emoji, member_id)
);

CREATE TABLE mention (
  source_type TEXT NOT NULL, source_id TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type IN ('member','group','account','front')),
  target_id TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (source_type, source_id, target_type, target_id)
);

CREATE TABLE read_state (
  channel_id TEXT NOT NULL, account_id TEXT NOT NULL,
  reader_member_id TEXT NOT NULL DEFAULT '',
  last_read_message_id TEXT NOT NULL, last_read_message_at INTEGER NOT NULL,
  PRIMARY KEY (channel_id, account_id, reader_member_id)
);

CREATE TABLE blob (
  hash TEXT PRIMARY KEY, size INTEGER NOT NULL, mime TEXT NOT NULL,
  stored_at INTEGER NOT NULL, uploaded_by TEXT NOT NULL,
  received INTEGER NOT NULL DEFAULT 0,
  is_complete INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE attachment (
  id TEXT PRIMARY KEY, account_id TEXT,
  blob_hash TEXT, filename TEXT, mime TEXT, size INTEGER,
  width INTEGER, height INTEGER, duration_ms INTEGER, waveform TEXT,
  thumb_blob_hash TEXT, alt_text TEXT, is_spoiler INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE item_attachment (
  owner_type TEXT NOT NULL CHECK (owner_type IN ('message','post')),
  owner_id TEXT NOT NULL, attachment_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (owner_type, owner_id, attachment_id)
);

CREATE VIRTUAL TABLE message_fts USING fts5(text, cw, content='message', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2');

-- ─── posts, profiles, social ────────────────────────────────────────────
CREATE TABLE post (
  id TEXT PRIMARY KEY, account_id TEXT, device_id TEXT,
  kind TEXT, title TEXT, text TEXT NOT NULL DEFAULT '', entities TEXT NOT NULL DEFAULT '[]',
  mood TEXT, tags TEXT NOT NULL DEFAULT '[]', cw TEXT,
  reply_to_id TEXT, quote TEXT, repost_of_id TEXT, front_snapshot TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  occurred_at INTEGER NOT NULL, tz_offset_min INTEGER NOT NULL DEFAULT 0, received_at INTEGER,
  sent_offline INTEGER NOT NULL DEFAULT 0,
  revision_count INTEGER NOT NULL DEFAULT 1, edited_at INTEGER, deleted_at INTEGER,
  char_count INTEGER GENERATED ALWAYS AS (length(text)) VIRTUAL,
  clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX post_account_time ON post(account_id, occurred_at);
CREATE VIRTUAL TABLE post_fts USING fts5(title, text, tags, content='post', content_rowid='rowid', tokenize='unicode61 remove_diacritics 2');

CREATE TABLE post_author (post_id TEXT NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (post_id, member_id));
CREATE TABLE post_revision (post_id TEXT NOT NULL, rev INTEGER NOT NULL, title TEXT, text TEXT NOT NULL, entities TEXT NOT NULL, edited_at INTEGER NOT NULL, hlc TEXT NOT NULL, PRIMARY KEY (post_id, rev));

CREATE TABLE highlight (
  profile_member_id TEXT NOT NULL, post_id TEXT NOT NULL,
  added_by_member TEXT, sort_key TEXT,
  added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (profile_member_id, post_id)
);

CREATE TABLE relationship_type (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT, inverse_name TEXT,
  is_symmetric INTEGER NOT NULL DEFAULT 0, color TEXT, icon TEXT, deleted_at INTEGER,
  clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE relationship (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
  from_member_id TEXT, to_kind TEXT, to_id TEXT, to_label TEXT, type_id TEXT, note TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE member_list (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT, description TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}', deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE member_list_item (
  list_id TEXT NOT NULL, member_id TEXT NOT NULL, added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (list_id, member_id)
);

CREATE TABLE feed (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT, description TEXT,
  query TEXT, query_ast TEXT,
  visibility TEXT NOT NULL DEFAULT '{"mode":"private"}',
  deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);

-- ─── privacy, follows, notifications ────────────────────────────────────
CREATE TABLE bucket (
  id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT, color TEXT, sort_key TEXT,
  ceiling TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(ceiling)),
  deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE bucket_assignment (
  bucket_id TEXT NOT NULL, follower_account_id TEXT NOT NULL, added_hlc TEXT, removed_hlc TEXT,
  is_present INTEGER GENERATED ALWAYS AS (added_hlc IS NOT NULL AND (removed_hlc IS NULL OR added_hlc > removed_hlc)) STORED,
  PRIMARY KEY (bucket_id, follower_account_id)
);

CREATE TABLE follow (
  id TEXT PRIMARY KEY,
  follower_account_id TEXT, target_account_id TEXT,
  status TEXT CHECK (status IS NULL OR status IN ('requested','active','declined','ended','blocked')),
  ceiling TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(ceiling)),
  prefs TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(prefs)),
  created_at INTEGER NOT NULL, clocks TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE notification (
  id TEXT PRIMARY KEY, recipient_account_id TEXT NOT NULL,
  kind TEXT NOT NULL, source_op_id TEXT, payload TEXT NOT NULL,
  created_at INTEGER NOT NULL, due_at INTEGER NOT NULL, displayed_time INTEGER,
  delivered_at INTEGER, collapsed_into TEXT, cancelled_at INTEGER
);
CREATE INDEX notification_due ON notification(due_at) WHERE delivered_at IS NULL AND cancelled_at IS NULL;

CREATE TABLE follower_front_view (
  follower_account_id TEXT NOT NULL, target_account_id TEXT NOT NULL,
  entries TEXT NOT NULL, displayed_since INTEGER, revealed_at INTEGER NOT NULL,
  PRIMARY KEY (follower_account_id, target_account_id)
);

-- ─── misc ───────────────────────────────────────────────────────────────
CREATE TABLE draft (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, context TEXT, authors TEXT, text TEXT, entities TEXT NOT NULL DEFAULT '[]', updated_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}');
CREATE TABLE stage (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT, definition TEXT, created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}');
CREATE TABLE pref (account_id TEXT NOT NULL, device_id TEXT NOT NULL DEFAULT '', key TEXT NOT NULL, value TEXT NOT NULL CHECK (json_valid(value)), hlc TEXT NOT NULL, PRIMARY KEY (account_id, device_id, key));
CREATE TABLE custom_emoji (
  id TEXT PRIMARY KEY, name TEXT, aliases TEXT NOT NULL DEFAULT '[]', category TEXT,
  blob_hash TEXT, is_animated INTEGER NOT NULL DEFAULT 0,
  created_by TEXT, created_at INTEGER NOT NULL, deleted_at INTEGER, clocks TEXT NOT NULL DEFAULT '{}'
);
CREATE UNIQUE INDEX custom_emoji_name ON custom_emoji(name) WHERE deleted_at IS NULL;
CREATE TABLE api_token (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, scopes TEXT NOT NULL, created_at INTEGER NOT NULL, last_used_at INTEGER, revoked_at INTEGER);
CREATE TABLE webhook (id TEXT PRIMARY KEY, account_id TEXT NOT NULL, url TEXT NOT NULL, secret TEXT NOT NULL, events TEXT NOT NULL, is_enabled INTEGER NOT NULL DEFAULT 1, last_status INTEGER, last_error TEXT, created_at INTEGER NOT NULL);
