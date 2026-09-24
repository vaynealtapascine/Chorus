-- Post search (GET /search/posts): post_fts existed since 0001 but nothing filled it. project.rs
-- keeps it now (title, text, tags; rowid = the post's rowid); this indexes posts projected before.
DELETE FROM post_fts;
INSERT INTO post_fts(rowid, title, text, tags)
  SELECT rowid, coalesce(title, ''), text,
         coalesce((SELECT group_concat(value, ' ') FROM json_each(CASE WHEN json_valid(tags) THEN tags ELSE '[]' END)), '')
  FROM post WHERE deleted_at IS NULL;
