-- Link fields the projection dropped: messages' `reply_to` (only posts mapped it) and posts'
-- `repost_of`, so the REST APIs, CSV exports and analysis views had no reply or repost links.
-- project.rs maps them now; this fills in rows projected before. The first applied create holds
-- the link (edits don't change it); purged payloads have none.
UPDATE message SET reply_to_id = (
  SELECT json_extract(o.payload, '$.reply_to') FROM op o
  WHERE o.entity_id = message.id AND o.kind IN ('message.send', 'message.forward')
    AND o.status = 'applied' AND json_valid(o.payload)
  ORDER BY o.seq LIMIT 1
) WHERE reply_to_id IS NULL;
UPDATE post SET repost_of_id = (
  SELECT json_extract(o.payload, '$.repost_of') FROM op o
  WHERE o.entity_id = post.id AND o.kind = 'post.create' AND o.status = 'applied' AND json_valid(o.payload)
  ORDER BY o.seq LIMIT 1
) WHERE repost_of_id IS NULL;
