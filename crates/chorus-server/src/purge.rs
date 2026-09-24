//! `chorus-server purge`: the only true erase (D-053, DATA_MODEL.md §2, SPEC §5).
//!
//! Purged ops keep their ids, seq and scope, but their payload becomes `{"purged":true}` (an op
//! no longer valid, so every projection drops it). Keeping the ids means sync digests don't
//! change, so devices don't re-send what they hold. Projections are then rebuilt, which removes
//! the content from every derived table and the search index; a purged message's attachment
//! files are deleted when nothing else uses them. Devices that already synced the original keep
//! their copy, and existing backup snapshots keep theirs: a purge can't reach into either. Each
//! purge is appended to `<data_dir>/purge.log` (JSON lines: when, what was asked, which op ids).

use std::collections::BTreeSet;
use std::io::Write as _;

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};

use crate::{config::Config, project};

const PURGED: &str = "{\"purged\":true}";

/// What to erase.
pub enum Target {
    /// One op.
    Op(String),
    /// A message: every op on it (send, edits, deletes, pins), every op that points at it
    /// (`message_id`), the reactions to it, and the attachments it carries.
    Message(String),
    /// A whole (non-admin) account, e.g. a test account: every op it wrote, everything in its
    /// own scope and internal space, its follows either way, and its devices, sessions, tokens,
    /// webhooks and notifications. Other accounts' ops stay; a shared space it created goes.
    Account(String),
}

/// The ops a purge would rewrite (not yet purged ones only).
pub fn ops_for(conn: &Connection, target: &Target) -> anyhow::Result<Vec<String>> {
    let (sql, id) = match target {
        Target::Op(id) => ("SELECT id FROM op WHERE id = ?1", id),
        Target::Account(id) => (
            "SELECT id FROM op WHERE account_id = ?1 OR scope = 'account:' || ?1
               OR scope IN (SELECT 'space:' || id FROM space WHERE owner_account_id = ?1)
               OR entity_id IN (SELECT id FROM follow WHERE follower_account_id = ?1 OR target_account_id = ?1)
               OR json_extract(payload, '$.follower_account_id') = ?1",
            id,
        ),
        Target::Message(id) => (
            "SELECT id FROM op WHERE entity_id = ?1
               OR json_extract(payload, '$.message_id') = ?1
               OR (kind LIKE 'reaction.%' AND json_extract(payload, '$.target_id') = ?1)
               OR entity_id IN (SELECT a.value FROM op m, json_each(m.payload, '$.attachments') a
                                WHERE m.entity_id = ?1 AND json_valid(m.payload))",
            id,
        ),
    };
    let mut st = conn
        .prepare(&format!("SELECT id FROM ({sql}) WHERE id IN (SELECT id FROM op WHERE payload <> ?2) ORDER BY id"))?;
    Ok(st.query_map(params![id, PURGED], |r| r.get(0))?.collect::<Result<_, _>>()?)
}

/// Blob hashes named by these ops (attachments' files and thumbnails), read before the rewrite.
fn blobs_of(conn: &Connection, ids: &[String]) -> anyhow::Result<BTreeSet<String>> {
    let mut out = BTreeSet::new();
    let mut st = conn.prepare("SELECT payload FROM op WHERE id = ?1")?;
    for id in ids {
        let payload: String = st.query_row([id], |r| r.get(0))?;
        let v: Value = serde_json::from_str(&payload).unwrap_or(Value::Null);
        for key in ["blob_hash", "thumb_blob_hash"] {
            if let Some(h) = v.get(key).and_then(Value::as_str) {
                out.insert(h.to_string());
            }
        }
    }
    Ok(out)
}

/// Delete a blob's row and file when no attachment, avatar, emoji or banner still uses it.
fn drop_unused_blob(cfg: &Config, conn: &Connection, hash: &str) -> anyhow::Result<bool> {
    let used: bool = conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM attachment WHERE blob_hash = ?1 OR thumb_blob_hash = ?1)
             OR EXISTS (SELECT 1 FROM op WHERE payload <> ?2 AND instr(payload, ?1) > 0)",
        params![hash, PURGED],
        |r| r.get(0),
    )?;
    if used {
        return Ok(false);
    }
    conn.execute("DELETE FROM blob WHERE hash = ?1", [hash])?;
    if hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()) {
        let path = cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(hash);
        if path.exists() {
            std::fs::remove_file(path)?;
        }
    }
    Ok(true)
}

/// Refuse to erase an admin account (the owner's): a mistyped id must not take it.
pub fn check(conn: &Connection, target: &Target) -> anyhow::Result<()> {
    if let Target::Account(id) = target {
        let admin: Option<bool> =
            conn.query_row("SELECT is_admin FROM account WHERE id = ?1", [id], |r| r.get(0)).optional()?;
        match admin {
            None => anyhow::bail!("no account {id}"),
            Some(true) => anyhow::bail!("{id} is an admin account; refusing to purge it"),
            Some(false) => {}
        }
    }
    Ok(())
}

/// The server-side records of an account that no op carries.
fn drop_account_rows(conn: &Connection, account: &str) -> anyhow::Result<()> {
    let devices = "(SELECT id FROM device WHERE account_id = ?1)";
    for sql in [
        format!("DELETE FROM session WHERE device_id IN {devices}"),
        format!("DELETE FROM auth_nonce WHERE device_id IN {devices}"),
        format!("DELETE FROM invite WHERE account_id = ?1 OR created_by IN {devices}"),
        "DELETE FROM api_token WHERE account_id = ?1".into(),
        "DELETE FROM webhook WHERE account_id = ?1".into(),
        "DELETE FROM notification WHERE recipient_account_id = ?1".into(),
        "DELETE FROM follower_front_view WHERE follower_account_id = ?1 OR target_account_id = ?1".into(),
        "DELETE FROM follower_front_log WHERE follower_account_id = ?1 OR target_account_id = ?1".into(),
        "DELETE FROM scope_access WHERE account_id = ?1 OR scope = 'account:' || ?1
           OR scope IN (SELECT 'space:' || id FROM space WHERE owner_account_id = ?1)"
            .into(),
        "DELETE FROM device WHERE account_id = ?1".into(),
        "DELETE FROM account WHERE id = ?1".into(),
    ] {
        conn.execute(&sql, [account])?;
    }
    Ok(())
}

/// Rewrite the target's ops as purged, rebuild every projection, drop files nothing uses any
/// more, and log it. Returns the op ids.
pub fn run(cfg: &Config, conn: &mut Connection, target: &Target, asked: &str) -> anyhow::Result<Vec<String>> {
    check(conn, target)?;
    let ids = ops_for(conn, target)?;
    let mut blobs = blobs_of(conn, &ids)?;
    if let Target::Account(account) = target {
        let mut st = conn.prepare("SELECT hash FROM blob WHERE uploaded_by = ?1")?;
        blobs.extend(st.query_map([account], |r| r.get::<_, String>(0))?.collect::<Result<Vec<_>, _>>()?);
    } else if ids.is_empty() {
        return Ok(ids);
    }
    {
        let tx = conn.transaction()?;
        {
            let mut st = tx.prepare("UPDATE op SET payload = ?2 WHERE id = ?1")?;
            for id in &ids {
                st.execute(params![id, PURGED])?;
            }
        }
        if let Target::Account(account) = target {
            // (the space ids are needed for scope_access, so before the rebuild drops them)
            drop_account_rows(&tx, account)?;
            // and its export bundles: they hold everything the purge removes
            crate::export_job::forget_account(&tx, cfg, account)?;
        }
        tx.commit()?;
    }
    project::rebuild(conn)?;
    let mut files = Vec::new();
    for h in &blobs {
        if drop_unused_blob(cfg, conn, h)? {
            files.push(h.clone());
        }
    }
    let line = json!({"at": crate::now_ms(), "asked": asked, "ops": ids, "blobs_deleted": files});
    let mut log = std::fs::OpenOptions::new().create(true).append(true).open(cfg.server.data_dir.join("purge.log"))?;
    writeln!(log, "{line}")?;
    Ok(ids)
}
