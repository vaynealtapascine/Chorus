-- D-119 audit: ops ingest now refuses, accepted before 2026-11-06. Read-only; counts only.
-- 1. entities written from more than one scope (payload-keyed kinds and follow prefs excepted)
SELECT 'cross-scope ' || t AS what, count(*) AS n FROM (
  SELECT entity_id, substr(kind, 1, instr(kind, '.') - 1) AS t, count(DISTINCT scope) AS scopes FROM op
  WHERE entity_id IS NOT NULL AND status = 'applied' AND kind NOT IN ('follow.set_prefs', 'poll.vote',
    'read.mark', 'read.set', 'message.poll_tally', 'post.poll_tally', 'post.react', 'post.unreact',
    'reaction.add', 'reaction.remove', 'highlight.add', 'highlight.remove', 'highlight.reorder',
    'field.set_value', 'pref.set', 'front.review_resolve', 'post.attachment')
  GROUP BY entity_id, t HAVING scopes > 1) GROUP BY t
UNION ALL
-- 2. an account's or system's row set from another account's scope
SELECT 'foreign ' || kind, count(*) FROM op
  WHERE kind IN ('account.set', 'system.set') AND scope <> 'account:' || entity_id GROUP BY kind
UNION ALL
-- 3. field values, highlights, group members naming another account's member
SELECT 'foreign member in ' || o.kind, count(*) FROM op o JOIN member m
  ON m.id = coalesce(json_extract(o.payload, '$.member_id'), json_extract(o.payload, '$.profile_member_id'))
  WHERE o.kind IN ('field.set_value', 'highlight.add', 'group.add_member') AND m.account_id <> o.account_id
  GROUP BY o.kind
UNION ALL
-- 4. posts and messages carrying another account's attachment
SELECT 'foreign attachment in ' || o.kind, count(*) FROM op o, json_each(o.payload, '$.attachments') j
  JOIN attachment a ON a.id = j.value
  WHERE o.kind IN ('post.create', 'post.edit', 'message.send', 'message.edit', 'message.forward')
    AND a.account_id <> o.account_id GROUP BY o.kind;
