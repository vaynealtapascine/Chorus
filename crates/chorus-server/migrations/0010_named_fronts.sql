-- UI2: additive, rebuildable own-account current projections (D-080). The original operation
-- log and legacy Body tables remain intact. Catalogue/ingestion and UI integration are separate.

CREATE TABLE front_layer (
  account_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
  level INTEGER NOT NULL, is_deleted INTEGER NOT NULL CHECK(is_deleted IN (0,1)),
  PRIMARY KEY(account_id,id)
) WITHOUT ROWID;

CREATE TABLE named_front (
  account_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
  follow_source_id TEXT, follow_hlc TEXT,
  is_overridden INTEGER NOT NULL CHECK(is_overridden IN (0,1)),
  follow_state TEXT NOT NULL CHECK(follow_state IN ('independent','following','overridden','unavailable')),
  follow_issue TEXT CHECK(follow_issue IN ('cycle','source_unavailable')),
  is_deleted INTEGER NOT NULL CHECK(is_deleted IN (0,1)),
  PRIMARY KEY(account_id,id)
) WITHOUT ROWID;

CREATE TABLE named_front_purpose_config (
  account_id TEXT NOT NULL, front_id TEXT NOT NULL, purpose TEXT NOT NULL,
  use_legacy INTEGER NOT NULL CHECK(use_legacy IN (0,1)),
  PRIMARY KEY(account_id,front_id,purpose)
) WITHOUT ROWID;

CREATE TABLE named_front_purpose (
  account_id TEXT NOT NULL, front_id TEXT NOT NULL,
  purpose TEXT NOT NULL, layer_id TEXT NOT NULL,
  PRIMARY KEY(account_id,front_id,purpose,layer_id)
) WITHOUT ROWID;

CREATE TABLE named_front_presence (
  account_id TEXT NOT NULL, front_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('own','effective','authorship','notifications') OR (substr(kind,1,8)='purpose:' AND length(kind)>8)),
  subject_type TEXT NOT NULL CHECK(subject_type IN ('member','group','state')),
  subject_id TEXT NOT NULL, layer_id TEXT NOT NULL,
  is_primary INTEGER NOT NULL CHECK(is_primary IN (0,1)), position INTEGER NOT NULL CHECK(position>=0),
  PRIMARY KEY(account_id,front_id,kind,subject_type,subject_id)
) WITHOUT ROWID;
CREATE INDEX named_front_presence_order ON named_front_presence(account_id,front_id,kind,position);

CREATE TABLE front_system_cache (
  account_id TEXT PRIMARY KEY, projection_version INTEGER NOT NULL, last_front_seq INTEGER NOT NULL,
  projection TEXT NOT NULL CHECK(json_valid(projection)),
  append_guard TEXT NOT NULL CHECK(json_valid(append_guard))
) WITHOUT ROWID;
CREATE INDEX op_front_scope_seq ON op(scope,seq)
  WHERE status='applied' AND kind GLOB 'front.*';

-- Confirmed effective presence only. Unavailable/deleted recovery data remains in the private
-- raw projection for repair, and is deliberately excluded from this analysis view.
CREATE VIEW v_named_front_current AS
SELECT p.account_id,p.front_id,f.name AS front_name,p.subject_type,p.subject_id,p.layer_id,
       l.name AS layer_name,l.level AS layer_level,p.is_primary,p.position,f.follow_state
FROM named_front_presence p
JOIN named_front f ON f.account_id=p.account_id AND f.id=p.front_id
LEFT JOIN front_layer l ON l.account_id=p.account_id AND l.id=p.layer_id
WHERE p.kind='effective' AND f.is_deleted=0 AND f.follow_state<>'unavailable';
