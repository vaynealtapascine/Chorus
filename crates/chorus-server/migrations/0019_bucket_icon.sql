-- Additive metadata only: old buckets keep the shared default icon.
ALTER TABLE bucket ADD COLUMN icon TEXT;
