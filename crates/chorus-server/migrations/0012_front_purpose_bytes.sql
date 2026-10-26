-- Purpose IDs are opaque nonempty strings. SQLite length(TEXT) stops at NUL, whereas Rust/
-- JSON retain it. Keep the existing readable rows, but validate this prefix by byte length.
DROP VIEW v_named_front_current;
ALTER TABLE named_front_presence RENAME TO named_front_presence_previous;
CREATE TABLE named_front_presence (
  account_id TEXT NOT NULL, front_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('own','effective','authorship','notifications') OR
    (substr(kind,1,8)='purpose:' AND length(CAST(kind AS BLOB))>8)),
  subject_type TEXT NOT NULL CHECK(subject_type IN ('member','group','state')),
  subject_id TEXT NOT NULL, layer_id TEXT NOT NULL,
  is_primary INTEGER NOT NULL CHECK(is_primary IN (0,1)), position INTEGER NOT NULL CHECK(position>=0),
  PRIMARY KEY(account_id,front_id,kind,subject_type,subject_id)
) WITHOUT ROWID;
INSERT INTO named_front_presence SELECT * FROM named_front_presence_previous;
DROP TABLE named_front_presence_previous;
CREATE INDEX named_front_presence_order ON named_front_presence(account_id,front_id,kind,position);
CREATE VIEW v_named_front_current AS
SELECT p.account_id,p.front_id,f.name AS front_name,p.subject_type,p.subject_id,p.layer_id,
       l.name AS layer_name,l.level AS layer_level,p.is_primary,p.position,f.follow_state
FROM named_front_presence p
JOIN named_front f ON f.account_id=p.account_id AND f.id=p.front_id
LEFT JOIN front_layer l ON l.account_id=p.account_id AND l.id=p.layer_id
WHERE p.kind='effective' AND f.is_deleted=0 AND f.follow_state<>'unavailable';
