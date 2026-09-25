-- R26: the per-item rows every message and post writes (authors, segments, mentions,
-- attachments) are stored in their primary key's B-tree alone. As rowid tables each row went
-- into two B-trees (the table and its primary-key index); a rebuild writes ~3 of these rows per
-- message. Same columns, keys and secondary indexes; rows are copied as they are.

CREATE TABLE message_author_0009 (
  message_id TEXT NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (message_id, member_id)
) WITHOUT ROWID;
INSERT INTO message_author_0009 (message_id, member_id, position)
  SELECT message_id, member_id, position FROM message_author;
DROP TABLE message_author;
ALTER TABLE message_author_0009 RENAME TO message_author;
CREATE INDEX message_author_member ON message_author(member_id);

CREATE TABLE message_segment_0009 (
  message_id TEXT NOT NULL, idx INTEGER NOT NULL,
  offset_u16 INTEGER NOT NULL, length_u16 INTEGER NOT NULL, text TEXT NOT NULL,
  PRIMARY KEY (message_id, idx)
) WITHOUT ROWID;
INSERT INTO message_segment_0009 (message_id, idx, offset_u16, length_u16, text)
  SELECT message_id, idx, offset_u16, length_u16, text FROM message_segment;
DROP TABLE message_segment;
ALTER TABLE message_segment_0009 RENAME TO message_segment;

CREATE TABLE message_segment_author_0009 (
  message_id TEXT NOT NULL, idx INTEGER NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (message_id, idx, member_id)
) WITHOUT ROWID;
INSERT INTO message_segment_author_0009 (message_id, idx, member_id, position)
  SELECT message_id, idx, member_id, position FROM message_segment_author;
DROP TABLE message_segment_author;
ALTER TABLE message_segment_author_0009 RENAME TO message_segment_author;
CREATE INDEX msa_member ON message_segment_author(member_id);

CREATE TABLE mention_0009 (
  source_type TEXT NOT NULL, source_id TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type IN ('member','group','account','front')),
  target_id TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (source_type, source_id, target_type, target_id)
) WITHOUT ROWID;
INSERT INTO mention_0009 (source_type, source_id, target_type, target_id)
  SELECT source_type, source_id, target_type, target_id FROM mention;
DROP TABLE mention;
ALTER TABLE mention_0009 RENAME TO mention;

CREATE TABLE post_author_0009 (
  post_id TEXT NOT NULL, member_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (post_id, member_id)
) WITHOUT ROWID;
INSERT INTO post_author_0009 (post_id, member_id, position) SELECT post_id, member_id, position FROM post_author;
DROP TABLE post_author;
ALTER TABLE post_author_0009 RENAME TO post_author;

CREATE TABLE item_attachment_0009 (
  owner_type TEXT NOT NULL CHECK (owner_type IN ('message','post')),
  owner_id TEXT NOT NULL, attachment_id TEXT NOT NULL, position INTEGER NOT NULL,
  PRIMARY KEY (owner_type, owner_id, attachment_id)
) WITHOUT ROWID;
INSERT INTO item_attachment_0009 (owner_type, owner_id, attachment_id, position)
  SELECT owner_type, owner_id, attachment_id, position FROM item_attachment;
DROP TABLE item_attachment;
ALTER TABLE item_attachment_0009 RENAME TO item_attachment;
