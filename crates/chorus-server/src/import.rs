//! Account import (R27, D-068's other half): `chorus-server import-account --from <zip>` takes
//! an export bundle (export_job.rs) made on another server and brings the account over with its
//! history and files, so moving between a home PC and a VPS keeps everything: op ids, authors,
//! times and devices stay as they were, exactly as restore pushes keep them.
//!
//! Everything is checked before anything is written: the manifest, `ops.jsonl` against its hash
//! and count, every op (history-valid, written by this account), every file against its hash and
//! size. Then clashes on this server: the account id, the handle (mapped with `--handle`), any op
//! id. A problem refuses the whole import with a list of what is wrong; nothing is half imported.
//! Files are copied into the store first (content-addressed, so a refused import leaves at most
//! unreferenced files), then the account, its ops (projected one by one, as live) and the file
//! records go in one transaction.
//!
//! What the bundle can't carry (DATA_MODEL §7, OPS.md): other accounts. The account's ops in a
//! space someone else owns are imported, but that space stays unreadable here until its owner's
//! account is imported too, and then everything lines up (their `space.join` grants access to
//! the ops already here). Follows name accounts that may not be on this server; they wait the same
//! way. Ops in the server scope (custom emoji an admin made) belong to the old server and are left
//! out. Devices aren't carried either: the report ends with a one-time device invite for the
//! account.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::Write as _;
use std::path::Path;

use anyhow::Context;
use chorus_core::op::{self, Op};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;
use sha2::{Digest as _, Sha256};

use crate::config::Config;
use crate::zip::ZipReader;
use crate::{auth, ingest, oplog, project};

#[derive(Clone, Debug, Default)]
pub struct Options {
    /// Take this handle instead of the bundle's (it is taken here, or you want another).
    pub handle: Option<String>,
    /// Check the bundle and this server, report, write nothing.
    pub check_only: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Report {
    pub account_id: String,
    pub handle: Option<String>,
    pub ops: u64,
    /// ops per space scope, and whether this account created the space
    pub spaces: BTreeMap<String, (u64, bool)>,
    /// server-scope ops left out
    pub server_ops_left_out: u64,
    /// accounts the ops name (follows, shared spaces) that aren't on this server
    pub absent_accounts: BTreeSet<String>,
    pub files: u64,
    pub files_already_here: u64,
    /// files the export itself couldn't include (manifest `missing` / `damaged`)
    pub files_not_in_bundle: Vec<String>,
    /// one-time device invite for the imported account (none on a check)
    pub invite: Option<String>,
}

impl std::fmt::Display for Report {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        writeln!(f, "account {} (@{})", self.account_id, self.handle.as_deref().unwrap_or("-"))?;
        writeln!(f, "  {} ops", self.ops)?;
        let (own, others): (Vec<_>, Vec<_>) = self.spaces.iter().partition(|(_, (_, created))| *created);
        writeln!(f, "  {} of its own spaces", own.len())?;
        if !others.is_empty() {
            writeln!(
                f,
                "  {} ops in {} spaces other accounts own: kept, and readable once those accounts are imported too",
                others.iter().map(|(_, (n, _))| n).sum::<u64>(),
                others.len()
            )?;
        }
        if self.server_ops_left_out > 0 {
            writeln!(
                f,
                "  {} server-wide ops (custom emoji) left out: they belong to the old server",
                self.server_ops_left_out
            )?;
        }
        if !self.absent_accounts.is_empty() {
            writeln!(
                f,
                "  {} accounts it follows or shares spaces with aren't on this server: those links wait for them",
                self.absent_accounts.len()
            )?;
        }
        writeln!(f, "  {} files ({} already here)", self.files, self.files_already_here)?;
        if !self.files_not_in_bundle.is_empty() {
            writeln!(
                f,
                "  {} files the export couldn't include (missing or damaged on the old server)",
                self.files_not_in_bundle.len()
            )?;
        }
        if let Some(code) = &self.invite {
            writeln!(f, "  add a device with this one-time invite (7 days): {code}")?;
        }
        Ok(())
    }
}

/// What the bundle says, checked.
struct Bundle {
    account: String,
    kind: String,
    handle: Option<String>,
    ops: Vec<Op>,
    /// hash → (size, mime)
    files: BTreeMap<String, (u64, String)>,
    not_in_bundle: Vec<String>,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn is_hash(h: &str) -> bool {
    h.len() == 64 && h.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// Read and check the bundle; `problems` collects everything wrong (all of it, not the first).
fn read_bundle(zip: &mut ZipReader<fs::File>, problems: &mut Vec<String>) -> anyhow::Result<Option<Bundle>> {
    let Some(entry) = zip.entry("manifest.json").cloned() else {
        problems.push("no manifest.json: not a Chorus export bundle".into());
        return Ok(None);
    };
    let manifest: Value = serde_json::from_slice(&zip.read_all(&entry)?).context("manifest.json isn't JSON")?;
    if manifest["format"] != 1 {
        problems.push(format!("manifest format {} isn't one this server reads (1)", manifest["format"]));
        return Ok(None);
    }
    let account = manifest["account"]["id"].as_str().unwrap_or_default().to_string();
    let kind = manifest["account"]["kind"].as_str().unwrap_or_default().to_string();
    let handle = manifest["account"]["handle"].as_str().map(str::to_string);
    if !chorus_core::id::is_valid_id(&account) || !matches!(kind.as_str(), "system" | "person") {
        problems.push("the manifest's account (id, kind) isn't valid".into());
        return Ok(None);
    }
    // ops.jsonl: its hash and count, then each op
    let ops_name = manifest["ops"]["file"].as_str().unwrap_or("ops.jsonl").to_string();
    let Some(entry) = zip.entry(&ops_name).cloned() else {
        problems.push(format!("{ops_name} is missing"));
        return Ok(None);
    };
    let mut sha = Sha256::new();
    let mut ops = Vec::new();
    let mut line = Vec::new();
    let mut bad_lines = 0u64;
    let mut parse = |line: &[u8], ops: &mut Vec<Op>| {
        if line.iter().all(u8::is_ascii_whitespace) {
            return;
        }
        match serde_json::from_slice::<Op>(line) {
            Ok(o) => ops.push(o),
            Err(_) => bad_lines += 1,
        }
    };
    zip.read(&entry, |chunk| {
        sha.update(chunk);
        for piece in chunk.split_inclusive(|b| *b == b'\n') {
            line.extend_from_slice(piece);
            if piece.ends_with(b"\n") {
                parse(&line, &mut ops);
                line.clear();
            }
        }
        Ok(())
    })?;
    parse(&line, &mut ops);
    if bad_lines > 0 {
        problems.push(format!("{bad_lines} lines of {ops_name} aren't ops"));
    }
    if manifest["ops"]["sha256"].as_str() != Some(hex(&sha.finalize()).as_str()) {
        problems.push(format!("{ops_name} doesn't match the manifest's hash: it was changed or damaged"));
    }
    if manifest["ops"]["count"].as_u64() != Some(ops.len() as u64) {
        problems.push(format!("{ops_name} has {} ops, the manifest says {}", ops.len(), manifest["ops"]["count"]));
    }
    let mut ids = BTreeSet::new();
    for o in &ops {
        if !ids.insert(o.id.clone()) {
            problems.push(format!("op {} is in the bundle twice", o.id));
        } else if o.account_id.as_deref() != Some(account.as_str()) {
            problems.push(format!("op {} wasn't written by this account", o.id));
        } else if o.device_id.is_none() || o.occurred_at.is_none() || o.received_at.is_none() {
            problems.push(format!("op {} lacks its server stamps (device, times)", o.id));
        } else if let Err(e) = op::validate(o) {
            problems.push(format!("op {} ({}) isn't valid: {e}", o.id, o.kind));
        }
    }
    // the files: each one present, the right size, and its bytes hash to its name
    let mut files = BTreeMap::new();
    for b in manifest["blobs"].as_array().into_iter().flatten() {
        let hash = b["hash"].as_str().unwrap_or_default().to_string();
        let name = b["file"].as_str().map_or_else(|| format!("blobs/{hash}"), str::to_string);
        if !is_hash(&hash) {
            problems.push(format!("the manifest names a file {hash:?} that isn't a SHA-256"));
            continue;
        }
        let Some(entry) = zip.entry(&name).cloned() else {
            problems.push(format!("{name} is in the manifest but not in the bundle"));
            continue;
        };
        if Some(entry.size) != b["size"].as_u64() {
            problems.push(format!("{name}: {} bytes, the manifest says {}", entry.size, b["size"]));
            continue;
        }
        let mut sha = Sha256::new();
        zip.read(&entry, |c| {
            sha.update(c);
            Ok(())
        })?;
        if hex(&sha.finalize()) != hash {
            problems.push(format!("{name}: its bytes don't match its hash (damaged)"));
            continue;
        }
        files.insert(hash, (entry.size, b["mime"].as_str().unwrap_or("application/octet-stream").to_string()));
    }
    let not_in_bundle: Vec<String> = ["missing", "damaged"]
        .iter()
        .flat_map(|k| manifest[*k].as_array().into_iter().flatten())
        .filter_map(|h| h.as_str().map(str::to_string))
        .collect();
    Ok(Some(Bundle { account, kind, handle, ops, files, not_in_bundle }))
}

fn blob_path(cfg: &Config, hash: &str) -> std::path::PathBuf {
    cfg.blob_dir().join(&hash[..2]).join(&hash[2..4]).join(hash)
}

/// Import the account in `zip` into the database behind `conn` (the server stopped: `main.rs`
/// holds the file exclusively). Returns what was (or, with `check_only`, would be) imported;
/// refuses with every problem found.
pub fn import_account(
    cfg: &Config,
    conn: &mut Connection,
    zip: &Path,
    opts: &Options,
    now: i64,
) -> anyhow::Result<Report> {
    let file = fs::File::open(zip).with_context(|| format!("opening {}", zip.display()))?;
    let mut z = ZipReader::new(file).with_context(|| format!("reading {}", zip.display()))?;
    let mut problems = Vec::new();
    let bundle = read_bundle(&mut z, &mut problems)?;
    let Some(b) = bundle else { anyhow::bail!("refused:\n  - {}", problems.join("\n  - ")) };

    // clashes with what is here
    let handle = opts.handle.clone().map(|h| h.to_lowercase()).or(b.handle.clone());
    if conn.query_row("SELECT 1 FROM account WHERE id = ?1", [&b.account], |_| Ok(())).optional()?.is_some() {
        problems.push(format!("account {} is already on this server (imported before?)", b.account));
    }
    if let Some(h) = &handle {
        if !auth::valid_handle(h) {
            problems.push(format!("handle @{h}: 2–32 of a-z, 0-9, _"));
        } else if conn.query_row("SELECT 1 FROM account WHERE handle = ?1", [h], |_| Ok(())).optional()?.is_some() {
            problems.push(format!("the handle @{h} is taken here: choose another with --handle"));
        }
    }
    let mut clashing = 0u64;
    {
        let mut st = conn.prepare_cached("SELECT 1 FROM op WHERE id = ?1")?;
        for o in &b.ops {
            if st.exists([&o.id])? {
                clashing += 1;
            }
        }
    }
    if clashing > 0 {
        problems.push(format!("{clashing} of its op ids are already on this server"));
    }
    if !problems.is_empty() {
        anyhow::bail!("refused, nothing was imported:\n  - {}", problems.join("\n  - "));
    }

    // what it will be
    let mut report = Report {
        account_id: b.account.clone(),
        handle: handle.clone(),
        files_not_in_bundle: b.not_in_bundle.clone(),
        ..Default::default()
    };
    let created: BTreeSet<String> =
        b.ops.iter().filter(|o| o.kind == "space.create").map(|o| o.scope.clone()).collect();
    let mut named = BTreeSet::new();
    for o in &b.ops {
        if o.scope == "server" {
            report.server_ops_left_out += 1;
            continue;
        }
        report.ops += 1;
        if o.scope.starts_with("space:") {
            let e = report.spaces.entry(o.scope.clone()).or_insert((0, created.contains(&o.scope)));
            e.0 += 1;
        }
        if let Some(other) = o.scope.strip_prefix("account:").filter(|a| *a != b.account) {
            named.insert(other.to_string());
        }
        for k in ["target_account_id", "follower_account_id", "account_id"] {
            if let Some(a) = o.payload.get(k).and_then(Value::as_str).filter(|a| *a != b.account) {
                named.insert(a.to_string());
            }
        }
    }
    {
        let mut st = conn.prepare_cached("SELECT 1 FROM account WHERE id = ?1")?;
        for a in named {
            if !st.exists([&a])? {
                report.absent_accounts.insert(a);
            }
        }
    }
    for hash in b.files.keys() {
        if blob_path(cfg, hash).is_file() {
            report.files_already_here += 1;
        }
        report.files += 1;
    }
    if opts.check_only {
        return Ok(report);
    }

    // the files first (content-addressed: a refusal after this leaves unreferenced files only)
    for (hash, (size, _)) in &b.files {
        let dest = blob_path(cfg, hash);
        if dest.is_file() && fs::metadata(&dest)?.len() == *size {
            continue;
        }
        let entry = z.entry(&format!("blobs/{hash}")).cloned().context("a checked file went away")?;
        fs::create_dir_all(dest.parent().context("blob path")?)?;
        let part = dest.with_extension("import");
        let mut out = fs::File::create(&part)?;
        let mut sha = Sha256::new();
        z.read(&entry, |c| {
            sha.update(c);
            out.write_all(c)
        })?;
        out.sync_all()?;
        drop(out);
        anyhow::ensure!(hex(&sha.finalize()) == *hash, "blobs/{hash} changed while importing");
        fs::rename(&part, &dest)?;
    }

    // then the account, its history and its file records, all or nothing
    let tx = conn.transaction()?;
    let created_at = b.ops.iter().filter_map(|o| o.occurred_at).min().unwrap_or(now);
    tx.execute(
        "INSERT INTO account (id, kind, handle, created_at) VALUES (?1, ?2, ?3, ?4)",
        params![b.account, b.kind, handle, created_at],
    )?;
    ingest::grant(&tx, &b.account, &format!("account:{}", b.account))?;
    for (hash, (size, mime)) in &b.files {
        tx.execute(
            "INSERT OR IGNORE INTO blob (hash, size, mime, stored_at, uploaded_by, received, is_complete)
             VALUES (?1, ?2, ?3, ?4, ?5, ?2, 1)",
            params![hash, *size as i64, mime, now, b.account],
        )?;
    }
    for mut o in b.ops {
        if o.scope == "server" {
            continue;
        }
        // like a restore push: the op keeps its id, author, device and times (ingest.rs `preserved`)
        let seq = oplog::insert(&tx, &o, false, true)?;
        o.seq = Some(seq);
        project::after_insert(&tx, &o).with_context(|| format!("projecting op {} ({})", o.id, o.kind))?;
    }
    report.invite =
        Some(auth::create_invite(&tx, auth::InviteKind::Device, Some(&b.account), &b.account, 7 * 86_400_000, 1, now)?);
    tx.commit()?;
    Ok(report)
}
