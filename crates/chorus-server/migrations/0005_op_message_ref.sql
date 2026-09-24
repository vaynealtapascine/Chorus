-- Ops that point at a message by `payload.message_id` (edits, pins, forwards, thread links…).
-- A late public message.send releases the ones peers were held back from
-- (visibility::backfill_for_public_send), and purge finds them; both used to scan the scope's
-- whole log per message. Payloads are always JSON written by the server, so json_extract can't fail.
CREATE INDEX IF NOT EXISTS op_message_ref ON op(json_extract(payload, '$.message_id'))
  WHERE json_extract(payload, '$.message_id') IS NOT NULL;
