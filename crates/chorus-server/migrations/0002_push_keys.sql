-- M8.1: UnifiedPush endpoint keys per device (RFC 8291). Additive only.
ALTER TABLE device ADD COLUMN push_p256dh TEXT;
ALTER TABLE device ADD COLUMN push_auth TEXT;
ALTER TABLE device ADD COLUMN push_failures INTEGER NOT NULL DEFAULT 0;
