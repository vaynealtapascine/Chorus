-- Messages' `reply_to_id` was never projected (only posts mapped the op field `reply_to` to it),
-- so the REST message API, CSV exports and analysis views showed no reply links. project.rs maps
-- it now; this fills in the rows projected before. The first applied send holds `reply_to`
-- (edits don't change it); purged payloads have none.
UPDATE message SET reply_to_id = (
  SELECT json_extract(o.payload, '$.reply_to') FROM op o
  WHERE o.entity_id = message.id AND o.kind IN ('message.send', 'message.forward')
    AND o.status = 'applied' AND json_valid(o.payload)
  ORDER BY o.seq LIMIT 1
) WHERE reply_to_id IS NULL;
