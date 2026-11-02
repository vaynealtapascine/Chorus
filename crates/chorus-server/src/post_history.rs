//! D-107: compact derived post history. The authoritative op log is never rewritten.
use chorus_core::revisions::Revision;
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

#[derive(Debug, Default, Serialize, Deserialize)]
struct Delta {
    set: Map<String, Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<Splice>,
}

#[derive(Debug, Serialize, Deserialize)]
struct Splice {
    start: usize,
    remove: usize,
    insert: String,
}

// A single common-prefix/suffix splice is linear, bounded in memory, and works well for
// journal corrections. Whole replacements win when their JSON is smaller. UTF-8 byte offsets
// are internal to the server; no client has to implement this storage format.
fn delta(before: &Map<String, Value>, after: &Map<String, Value>) -> Delta {
    let mut out = Delta::default();
    for (key, value) in after {
        if before.get(key) == Some(value) {
            continue;
        }
        if key == "text"
            && let (Some(a), Some(b)) = (before.get(key).and_then(Value::as_str), value.as_str())
        {
            let prefix = a.chars().zip(b.chars()).take_while(|(a, b)| a == b).map(|(c, _)| c.len_utf8()).sum::<usize>();
            let suffix = a[prefix..]
                .chars()
                .rev()
                .zip(b[prefix..].chars().rev())
                .take_while(|(a, b)| a == b)
                .map(|(c, _)| c.len_utf8())
                .sum::<usize>();
            let splice =
                Splice { start: prefix, remove: a.len() - prefix - suffix, insert: b[prefix..b.len() - suffix].into() };
            if serde_json::to_vec(&splice).unwrap_or_default().len() < value.to_string().len() {
                out.text = Some(splice);
                continue;
            }
        }
        out.set.insert(key.clone(), value.clone());
    }
    out
}

fn apply(state: &mut Map<String, Value>, delta: Delta) -> anyhow::Result<()> {
    if let Some(s) = delta.text {
        let text = state.get("text").and_then(Value::as_str).unwrap_or_default();
        let end = s.start.checked_add(s.remove).ok_or_else(|| anyhow::anyhow!("invalid history diff"))?;
        anyhow::ensure!(
            s.start <= end && end <= text.len() && text.is_char_boundary(s.start) && text.is_char_boundary(end),
            "invalid history diff"
        );
        state.insert("text".into(), json!(format!("{}{}{}", &text[..s.start], s.insert, &text[end..])));
    }
    state.extend(delta.set);
    Ok(())
}

pub fn write(conn: &Connection, id: &str, versions: &[Revision]) -> anyhow::Result<()> {
    conn.execute("DELETE FROM post_revision_diff WHERE post_id = ?1", [id])?;
    let mut previous = Map::new();
    for r in versions {
        let change = serde_json::to_string(&delta(&previous, &r.fields))?;
        conn.prepare_cached("INSERT INTO post_revision_diff(post_id,rev,delta,edited_at,hlc) VALUES (?1,?2,?3,?4,?5)")?
            .execute(params![id, r.rev, change, r.at, r.hlc.to_string()])?;
        previous.clone_from(&r.fields);
    }
    // Only after the complete replacement is stored, within the caller's transaction.
    if !versions.is_empty() {
        conn.execute("DELETE FROM post_revision WHERE post_id = ?1", [id])?;
    }
    Ok(())
}

pub fn read(conn: &Connection, id: &str) -> anyhow::Result<Vec<Value>> {
    let mut st =
        conn.prepare_cached("SELECT rev,delta,edited_at FROM post_revision_diff WHERE post_id = ?1 ORDER BY rev")?;
    let rows = st.query_map([id], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, i64>(2)?)))?;
    let mut state = Map::new();
    let mut out = Vec::new();
    for row in rows {
        let (rev, change, at) = row?;
        apply(&mut state, serde_json::from_str(&change)?)?;
        out.push(json!({"rev":rev,"title":state.get("title"),"text":state.get("text").unwrap_or(&json!("")),
            "entities":state.get("entities").unwrap_or(&json!([])),"cw":state.get("cw"),"at":at}));
    }
    Ok(out)
}

/// Pre-migration backup is performed by the normal migration caller. Rebuild from the log
/// first; if a legacy/imported database has no source ops, retain its legacy rows losslessly.
pub fn backfill(conn: &Connection) -> anyhow::Result<()> {
    let mut st = conn.prepare("SELECT DISTINCT post_id FROM post_revision")?;
    let ids = st.query_map([], |r| r.get::<_, String>(0))?.collect::<Result<Vec<_>, _>>()?;
    for id in ids {
        let ops = crate::oplog::for_entity_of_kinds(conn, &id, &["post.create", "post.edit"])?;
        let versions = chorus_core::revisions::revisions(&ops);
        if !versions.is_empty() {
            write(conn, &id, &versions)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    proptest! {
        #[test]
        fn unicode_changes_round_trip(a in ".{0,500}", b in ".{0,500}") {
            let before = json!({"text":a,"title":"Before","cw":"warning","entities":[]}).as_object().unwrap().clone();
            let after = json!({"text":b,"title":null,"cw":null,"entities":[{"type":"bold","offset":0,"length":1}]}).as_object().unwrap().clone();
            let mut restored = before.clone();
            apply(&mut restored, delta(&before, &after)).unwrap();
            prop_assert_eq!(restored, after);
        }
    }
    #[test]
    fn long_entry_correction_stays_small() {
        let a = json!({"text":"🪴".repeat(10_000)}).as_object().unwrap().clone();
        let b = json!({"text":format!("{}!", "🪴".repeat(10_000))}).as_object().unwrap().clone();
        let change = delta(&a, &b);
        assert!(serde_json::to_string(&change).unwrap().len() < 120);
        let mut restored = a;
        apply(&mut restored, change).unwrap();
        assert_eq!(restored, b);
    }
}
