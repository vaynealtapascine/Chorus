//! Account-owned exports. Every query is anchored to the authenticated account id.

use anyhow::Context;
use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::{
    Connection, OpenFlags, params_from_iter,
    types::{Value, ValueRef},
};

use crate::{db, oplog, project};

/// A WAL reader for HTTP exports. The read transaction keeps account, device and op rows from
/// one snapshot while ingest continues on the server's primary connection.
pub fn read_snapshot(path: &Path) -> anyhow::Result<Connection> {
    let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;
    conn.execute_batch("PRAGMA query_only=ON; BEGIN")?;
    Ok(conn)
}

/// Applied operations authored by this account, in server sequence order. The op envelope is
/// preserved so a later importer can replay it without inferring fields from projections.
pub fn ops_jsonl(conn: &Connection, account: &str) -> anyhow::Result<Vec<u8>> {
    let mut out = Vec::new();
    write_ops_jsonl(conn, account, &mut out, |_| true)?;
    Ok(out)
}

/// [`ops_jsonl`], streamed into `out`. `each` is told how many ops are written so far and may
/// stop the export by returning false (then this errors). Returns the op count.
pub fn write_ops_jsonl(
    conn: &Connection,
    account: &str,
    out: &mut dyn std::io::Write,
    mut each: impl FnMut(u64) -> bool,
) -> anyhow::Result<u64> {
    let mut st = conn.prepare("SELECT id FROM op WHERE account_id = ?1 AND status = 'applied' ORDER BY seq")?;
    let ids: Vec<String> = st.query_map([account], |r| r.get(0))?.collect::<Result<_, _>>()?;
    let mut n = 0u64;
    for id in ids {
        let op = oplog::by_id(conn, &id)?.with_context(|| format!("op {id} disappeared during export"))?;
        serde_json::to_writer(&mut *out, &op)?;
        out.write_all(b"\n")?;
        n += 1;
        if !each(n) {
            anyhow::bail!("stopped");
        }
    }
    Ok(n)
}

struct CsvSpec {
    name: &'static str,
    columns: &'static [&'static str],
    sql: &'static str,
}

const CSV: &[CsvSpec] = &[
    CsvSpec {
        name: "members",
        columns: &["id", "name", "display_name", "pronouns", "color", "is_archived", "created_at", "created_local"],
        sql: "SELECT id,name,display_name,pronouns,color,(archived_at IS NOT NULL),created_at,
              strftime('%Y-%m-%dT%H:%M:%S',created_at/1000,'unixepoch','localtime')
              FROM member WHERE account_id=?1 ORDER BY created_at,id",
    },
    CsvSpec {
        name: "groups",
        columns: &["id", "kind", "parent_id", "name", "color", "created_at", "created_local"],
        sql: "SELECT id,kind,parent_id,name,color,created_at,
              strftime('%Y-%m-%dT%H:%M:%S',created_at/1000,'unixepoch','localtime')
              FROM member_group WHERE account_id=?1 ORDER BY created_at,id",
    },
    CsvSpec {
        name: "switches",
        columns: &[
            "id",
            "occurred_at",
            "occurred_local",
            "kind",
            "entries",
            "resulting_front",
            "note",
            "was_offline",
            "device_id",
        ],
        sql: "SELECT id,occurred_at,
              strftime('%Y-%m-%dT%H:%M:%S',(occurred_at+tz_offset_min*60000)/1000,'unixepoch'),
              kind,entries,resulting_front,note,was_offline,device_id
              FROM switch WHERE account_id=?1 ORDER BY occurred_at,id",
    },
    CsvSpec {
        name: "front_intervals",
        columns: &[
            "id",
            "subject_type",
            "subject_id",
            "level",
            "is_primary",
            "position",
            "start_at",
            "end_at",
            "start_local",
            "end_local",
            "duration_s",
        ],
        sql: "SELECT id,subject_type,subject_id,level,is_primary,position,start_at,end_at,
              strftime('%Y-%m-%dT%H:%M:%S',(start_at+start_tz_offset_min*60000)/1000,'unixepoch'),
              strftime('%Y-%m-%dT%H:%M:%S',(end_at+start_tz_offset_min*60000)/1000,'unixepoch'),
              (COALESCE(end_at,CAST(strftime('%s','now') AS INTEGER)*1000)-start_at)/1000
              FROM front_interval WHERE account_id=?1 ORDER BY start_at,id",
    },
    CsvSpec {
        name: "front_daily",
        columns: &["day", "subject_type", "subject_id", "level", "seconds", "hours", "as_primary_seconds"],
        sql: "SELECT day,subject_type,subject_id,level,seconds,round(seconds/3600.0,4),as_primary_seconds
              FROM front_daily WHERE account_id=?1 ORDER BY day,subject_type,subject_id,level",
    },
    CsvSpec {
        name: "messages",
        columns: &[
            "id",
            "channel_id",
            "occurred_at",
            "occurred_local",
            "text",
            "cw",
            "reply_to_id",
            "revision_count",
            "sent_offline",
        ],
        sql: "SELECT id,channel_id,occurred_at,
              strftime('%Y-%m-%dT%H:%M:%S',(occurred_at+tz_offset_min*60000)/1000,'unixepoch'),
              text,cw,reply_to_id,revision_count,sent_offline
              FROM message WHERE account_id=?1 AND deleted_at IS NULL ORDER BY occurred_at,id",
    },
    CsvSpec {
        name: "posts",
        columns: &[
            "id",
            "kind",
            "title",
            "text",
            "mood",
            "occurred_at",
            "occurred_local",
            "reply_to_id",
            "revision_count",
        ],
        sql: "SELECT id,kind,title,text,mood,occurred_at,
              strftime('%Y-%m-%dT%H:%M:%S',(occurred_at+tz_offset_min*60000)/1000,'unixepoch'),
              reply_to_id,revision_count
              FROM post WHERE account_id=?1 AND deleted_at IS NULL ORDER BY occurred_at,id",
    },
];

pub const CSV_NAMES: &[&str] =
    &["members", "groups", "switches", "front_intervals", "front_daily", "messages", "posts"];

fn cell(v: ValueRef<'_>) -> String {
    match v {
        ValueRef::Null => String::new(),
        ValueRef::Integer(i) => i.to_string(),
        ValueRef::Real(f) => f.to_string(),
        ValueRef::Text(t) => String::from_utf8_lossy(t).into_owned(),
        ValueRef::Blob(b) => b.iter().map(|x| format!("{x:02x}")).collect(),
    }
}

fn csv_field(out: &mut Vec<u8>, value: &str) {
    if value.bytes().any(|b| matches!(b, b',' | b'"' | b'\n' | b'\r')) {
        out.push(b'"');
        for b in value.bytes() {
            if b == b'"' {
                out.push(b'"');
            }
            out.push(b);
        }
        out.push(b'"');
    } else {
        out.extend_from_slice(value.as_bytes());
    }
}

fn csv_row(out: &mut Vec<u8>, fields: &[String]) {
    for (i, field) in fields.iter().enumerate() {
        if i > 0 {
            out.push(b',');
        }
        csv_field(out, field);
    }
    out.extend_from_slice(b"\r\n");
}

/// One documented, account-filtered tidy CSV. Unknown names return `None`.
pub fn csv(conn: &Connection, account: &str, name: &str) -> anyhow::Result<Option<Vec<u8>>> {
    let Some(spec) = CSV.iter().find(|s| s.name == name) else { return Ok(None) };
    let mut out = Vec::new();
    csv_row(&mut out, &spec.columns.iter().map(|s| (*s).to_string()).collect::<Vec<_>>());
    let mut st = conn.prepare(spec.sql)?;
    let mut rows = st.query([account])?;
    while let Some(row) = rows.next()? {
        let fields = (0..spec.columns.len()).map(|i| row.get_ref(i).map(cell)).collect::<Result<Vec<_>, _>>()?;
        csv_row(&mut out, &fields);
    }
    Ok(Some(out))
}

fn copy_owned_rows(
    source: &Connection,
    target: &Connection,
    table: &str,
    filter: &str,
    account: &str,
) -> anyhow::Result<()> {
    // Table and predicate are fixed call-site constants; the account id is always bound.
    let mut select = source.prepare(&format!("SELECT * FROM {table} WHERE {filter}"))?;
    let columns: Vec<String> = select.column_names().iter().map(|s| (*s).to_string()).collect();
    let marks = (1..=columns.len()).map(|i| format!("?{i}")).collect::<Vec<_>>().join(",");
    let insert = format!("INSERT INTO {table} ({}) VALUES ({marks})", columns.join(","));
    let mut rows = select.query([account])?;
    while let Some(row) = rows.next()? {
        let values = (0..columns.len()).map(|i| row.get::<_, Value>(i)).collect::<Result<Vec<_>, _>>()?;
        target.execute(&insert, params_from_iter(values))?;
    }
    Ok(())
}

struct TemporaryCopy(PathBuf);

impl Drop for TemporaryCopy {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// A newly built, single-account database. Only the owner's primary account/device records and
/// applied authored ops enter the file; projections are replayed from those ops. No full-server
/// pages are copied, so SQLite's free pages cannot retain another account's data.
pub fn sqlite_copy(source: &Connection, account: &str, work_dir: &Path) -> anyhow::Result<Vec<u8>> {
    fs::create_dir_all(work_dir)?;
    let path = work_dir.join(format!("export-{:016x}.sqlite", rand::random::<u64>()));
    let _cleanup = TemporaryCopy(path.clone());
    let mut target = db::open(&path)?;
    db::migrate(&mut target)?;
    copy_owned_rows(source, &target, "account", "id=?1", account)?;
    copy_owned_rows(source, &target, "device", "account_id=?1", account)?;
    // push endpoints and keys are delivery credentials, not the account's data
    target.execute("UPDATE device SET push_endpoint = NULL, push_p256dh = NULL, push_auth = NULL", [])?;
    copy_owned_rows(source, &target, "op", "account_id=?1 AND status='applied'", account)?;
    project::rebuild(&mut target)?;
    for spec in CSV {
        let view = match spec.name {
            "members" => "v_member",
            "groups" => "v_group",
            "switches" => "v_switch",
            "front_intervals" => "v_front_interval",
            "front_daily" => "v_front_daily",
            "messages" => "v_message",
            "posts" => "v_post",
            _ => unreachable!(),
        };
        let query = spec.sql.replace("account_id=?1", "account_id=(SELECT id FROM account LIMIT 1)");
        target.execute_batch(&format!("CREATE VIEW {view} ({}) AS {query};", spec.columns.join(",")))?;
    }
    target.execute_batch("PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;")?;
    drop(target);
    Ok(fs::read(&path)?)
}
