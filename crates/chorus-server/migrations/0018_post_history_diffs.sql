-- Derived data only; migrate() regenerates edited histories before clearing legacy copies.
CREATE TABLE post_revision_diff (
    post_id TEXT NOT NULL, rev INTEGER NOT NULL,
    delta TEXT NOT NULL CHECK(json_valid(delta)),
    edited_at INTEGER NOT NULL, hlc TEXT NOT NULL,
    PRIMARY KEY(post_id, rev)
) WITHOUT ROWID;
