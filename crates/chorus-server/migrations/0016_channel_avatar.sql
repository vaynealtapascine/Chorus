-- Representative channel images use the existing content-addressed blob store.
ALTER TABLE channel ADD COLUMN avatar_blob TEXT;
