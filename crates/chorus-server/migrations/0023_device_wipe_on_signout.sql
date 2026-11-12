-- D-152: when a device is signed out, whether the person asked for it to erase its copy at once
-- (else it keeps it for two days, `auth::SIGNED_OUT_KEEP_MS`). Additive; existing rows keep 0.
ALTER TABLE device ADD COLUMN wipe_on_signout INTEGER NOT NULL DEFAULT 0;
