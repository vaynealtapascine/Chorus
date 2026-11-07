-- D-126: handles a member claims (a JSON list, unique across members and account handles,
-- checked at ingest). Additive: every existing member starts with none; defaults are derived.
ALTER TABLE member ADD COLUMN handles TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(handles));
