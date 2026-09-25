//! Importing from PluralKit (D-005, docs/CLIENTS.md §6).
//!
//! Turns a PluralKit export (the JSON from `pk;export`, version 2) into planned ops. Every id is
//! derived from PluralKit's own ids with UUIDv5, so importing the same export twice produces the
//! same op ids — the server keeps the first and acknowledges the rest (no duplicates).

use std::collections::HashMap;

use serde_json::{Map, Value, json};
use uuid::Uuid;

use crate::replica::NewOp;

/// Namespace for PluralKit-derived ids.
const NS: Uuid = Uuid::from_u128(0x6368_6f72_7573_4b50_b000_0000_0000_0001);

fn v5(parts: &[&str]) -> String {
    Uuid::new_v5(&NS, parts.join(":").as_bytes()).hyphenated().to_string()
}

/// One op the import will create, with its fixed id.
#[derive(Clone, Debug, serde::Serialize)]
pub struct PlannedOp {
    pub id: String,
    #[serde(skip)]
    pub op: NewOp,
}

#[derive(Clone, Debug, Default, serde::Serialize)]
pub struct Plan {
    #[serde(skip)]
    pub ops: Vec<PlannedOp>,
    pub members: usize,
    pub groups: usize,
    pub switches: usize,
    pub warnings: Vec<String>,
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::trim).filter(|x| !x.is_empty()).map(str::to_string)
}

/// "ff0000" / "#ff0000" → "#ff0000"
fn color(v: &Value) -> Option<String> {
    let c = s(v, "color")?;
    let hex = c.trim_start_matches('#');
    (hex.len() == 6 && hex.chars().all(|ch| ch.is_ascii_hexdigit())).then(|| format!("#{}", hex.to_lowercase()))
}

/// PluralKit stores "no year" birthdays as year 0004.
fn birthday(v: &Value) -> Option<String> {
    let b = s(v, "birthday")?;
    Some(if let Some(rest) = b.strip_prefix("0004-") { format!("--{rest}") } else { b })
}

/// RFC 3339 → ms since epoch, without a date library (UTC or ±hh:mm offsets).
pub fn parse_rfc3339(t: &str) -> Option<i64> {
    let b = t.as_bytes();
    if b.len() < 19 || b[4] != b'-' || b[7] != b'-' || (b[10] != b'T' && b[10] != b' ') {
        return None;
    }
    let num = |r: std::ops::Range<usize>| t.get(r)?.parse::<i64>().ok();
    let (y, mo, d) = (num(0..4)?, num(5..7)?, num(8..10)?);
    let (h, mi, se) = (num(11..13)?, num(14..16)?, num(17..19)?);
    let mut rest = &t[19..];
    let mut ms = 0i64;
    if let Some(frac) = rest.strip_prefix('.') {
        let digits: String = frac.chars().take_while(char::is_ascii_digit).collect();
        rest = &frac[digits.len()..];
        ms = format!("{:0<3}", &digits[..digits.len().min(3)]).parse().ok()?;
    }
    let offset_min = match rest {
        "" | "Z" | "z" => 0,
        o if o.len() == 6 && (o.starts_with('+') || o.starts_with('-')) => {
            let sign = if o.starts_with('-') { -1 } else { 1 };
            sign * (o[1..3].parse::<i64>().ok()? * 60 + o[4..6].parse::<i64>().ok()?)
        }
        _ => return None,
    };
    // days from civil (Howard Hinnant)
    let (y2, m2) = if mo <= 2 { (y - 1, mo + 9) } else { (y, mo - 3) };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * m2 + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(((days * 86_400 + h * 3600 + mi * 60 + se) - offset_min * 60) * 1000 + ms)
}

fn op(
    id: String,
    kind: &str,
    scope: &str,
    entity: Option<String>,
    payload: Value,
    user_time: Option<i64>,
) -> PlannedOp {
    PlannedOp {
        id,
        op: NewOp { kind: kind.into(), scope: scope.into(), entity_id: entity, payload, member_id: None, user_time },
    }
}

/// Plan the import of a PluralKit export into the account scope `scope` ("account:<id>").
pub fn pluralkit(export: &Value, scope: &str) -> Result<Plan, String> {
    let system = s(export, "id").ok_or("not a PluralKit export: no system id")?;
    let members = export.get("members").and_then(Value::as_array).ok_or("not a PluralKit export: no members")?;
    let mut plan = Plan::default();
    let mut by_hid: HashMap<String, String> = HashMap::new(); // pk member id → Chorus member id

    // system name/tag/description
    let mut sys = Map::new();
    for (k, v) in [("name", s(export, "name")), ("tag", s(export, "tag")), ("description", s(export, "description"))] {
        if let Some(v) = v {
            sys.insert(k.into(), Value::String(v));
        }
    }
    if let Some(c) = color(export) {
        sys.insert("color".into(), Value::String(c));
    }
    if !sys.is_empty() {
        plan.ops.push(op(
            v5(&["pk", &system, "system.set"]),
            "system.set",
            scope,
            scope.strip_prefix("account:").map(str::to_string),
            Value::Object(sys),
            None,
        ));
    }

    for m in members {
        let Some(hid) = s(m, "id") else {
            plan.warnings.push("a member without an id was skipped".into());
            continue;
        };
        let id = v5(&["pk", &system, "member", &hid]);
        by_hid.insert(hid.clone(), id.clone());
        let mut f = Map::new();
        f.insert("name".into(), Value::String(s(m, "name").unwrap_or_else(|| hid.clone())));
        for k in ["display_name", "pronouns", "description"] {
            if let Some(v) = s(m, k) {
                f.insert(k.into(), Value::String(v));
            }
        }
        if let Some(c) = color(m) {
            f.insert("color".into(), Value::String(c));
        }
        if let Some(b) = birthday(m) {
            f.insert("birthday".into(), Value::String(b));
        }
        let tags: Vec<Value> = m
            .get("proxy_tags")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .map(
                |t| json!({"prefix": s(t, "prefix").unwrap_or_default(), "suffix": s(t, "suffix").unwrap_or_default()}),
            )
            .filter(|t| t["prefix"] != "" || t["suffix"] != "")
            .collect();
        f.insert("proxy_tags".into(), Value::Array(tags));
        f.insert("pk_id".into(), Value::String(hid.clone()));
        if s(m, "avatar_url").is_some() {
            plan.warnings.push(format!("{}: avatar not imported yet (attachments come later)", hid));
        }
        plan.ops.push(op(
            v5(&["pk", &system, "op", "member.create", &hid]),
            "member.create",
            scope,
            Some(id),
            Value::Object(f),
            None,
        ));
        plan.members += 1;
    }

    for g in export.get("groups").and_then(Value::as_array).into_iter().flatten() {
        let Some(gid) = s(g, "id") else { continue };
        let id = v5(&["pk", &system, "group", &gid]);
        let mut f = Map::new();
        f.insert(
            "name".into(),
            Value::String(s(g, "display_name").or_else(|| s(g, "name")).unwrap_or_else(|| gid.clone())),
        );
        f.insert("kind".into(), Value::String("group".into()));
        if let Some(d) = s(g, "description") {
            f.insert("description".into(), Value::String(d));
        }
        if let Some(c) = color(g) {
            f.insert("color".into(), Value::String(c));
        }
        plan.ops.push(op(
            v5(&["pk", &system, "op", "group.create", &gid]),
            "group.create",
            scope,
            Some(id.clone()),
            Value::Object(f),
            None,
        ));
        for mh in g.get("members").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str) {
            if let Some(mid) = by_hid.get(mh) {
                plan.ops.push(op(
                    v5(&["pk", &system, "op", "group.add_member", &gid, mh]),
                    "group.add_member",
                    scope,
                    Some(id.clone()),
                    json!({"member_id": mid}),
                    None,
                ));
            }
        }
        plan.groups += 1;
    }

    let mut switches: Vec<(i64, Vec<String>)> = export
        .get("switches")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|sw| {
            let t = parse_rfc3339(sw.get("timestamp")?.as_str()?)?;
            let ms: Vec<String> =
                sw.get("members")?.as_array()?.iter().filter_map(Value::as_str).map(str::to_string).collect();
            Some((t, ms))
        })
        .collect();
    crate::sort::ord(&mut switches);
    for (t, ms) in switches {
        let entries: Vec<Value> = ms
            .iter()
            .filter_map(|h| by_hid.get(h))
            .enumerate()
            .map(|(i, id)| json!({"subject_type": "member", "subject_id": id, "level": "front", "is_primary": i == 0}))
            .collect();
        let key = format!("{t}:{}", ms.join(","));
        let sid = v5(&["pk", &system, "switch", &key]);
        plan.ops.push(op(
            v5(&["pk", &system, "op", "front.switch", &key]),
            "front.switch",
            scope,
            Some(sid),
            json!({"entries": entries, "notify": "silent"}),
            Some(t),
        ));
        plan.switches += 1;
    }
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn export() -> Value {
        json!({
            "version": 2, "id": "exmpl", "name": "The Stars", "tag": "✨",
            "members": [
                {"id": "aaaaa", "name": "Kai", "color": "C0694E", "birthday": "0004-03-12", "pronouns": "they/them",
                 "proxy_tags": [{"prefix": "k:", "suffix": null}, {"prefix": null, "suffix": null}]},
                {"id": "bbbbb", "name": "June", "display_name": "Juniper", "color": null, "birthday": "1999-07-01", "proxy_tags": []}
            ],
            "groups": [{"id": "ggggg", "name": "Stars", "members": ["aaaaa", "bbbbb", "zzzzz"]}],
            "switches": [
                {"timestamp": "2024-05-01T10:00:00.123456+00:00", "members": ["bbbbb", "aaaaa"]},
                {"timestamp": "2024-04-30T09:00:00Z", "members": ["aaaaa"]},
                {"timestamp": "2024-05-02T00:00:00Z", "members": []}
            ]
        })
    }

    #[test]
    fn maps_members_groups_switches() {
        let scope = format!("account:{}", crate::id::new_id(1, [1; 10]));
        let p = pluralkit(&export(), &scope).unwrap();
        assert_eq!((p.members, p.groups, p.switches), (2, 1, 3));
        let kai = p.ops.iter().find(|o| o.op.kind == "member.create" && o.op.payload["name"] == "Kai").unwrap();
        assert_eq!(kai.op.payload["color"], "#c0694e");
        assert_eq!(kai.op.payload["birthday"], "--03-12");
        assert_eq!(kai.op.payload["proxy_tags"], json!([{"prefix": "k:", "suffix": ""}]));
        // unknown member in a group is skipped; switches are in time order with first = primary
        assert_eq!(p.ops.iter().filter(|o| o.op.kind == "group.add_member").count(), 2);
        let sw: Vec<&PlannedOp> = p.ops.iter().filter(|o| o.op.kind == "front.switch").collect();
        assert_eq!(sw[0].op.user_time, Some(1_714_467_600_000));
        assert_eq!(sw[1].op.payload["entries"][0]["is_primary"], true);
        assert_eq!(sw[1].op.user_time, Some(1_714_557_600_123));
        assert_eq!(sw[2].op.payload["entries"], json!([]));
        // every planned op validates against the catalogue
        for o in &p.ops {
            let full = crate::op::Op {
                id: o.id.clone(),
                kind: o.op.kind.clone(),
                v: 1,
                scope: o.op.scope.clone(),
                entity_id: o.op.entity_id.clone(),
                hlc: crate::hlc::Hlc::new(1, 0, 1),
                device_at: 1,
                tz_offset_min: 0,
                mono: None,
                boot_id: None,
                time_source: Default::default(),
                seen_seq: 0,
                member_id: None,
                payload: o.op.payload.clone(),
                seq: None,
                account_id: None,
                device_id: None,
                occurred_at: None,
                received_at: None,
            };
            assert!(crate::op::validate(&full).is_ok(), "{} {:?}", o.op.kind, crate::op::validate(&full));
        }
    }

    #[test]
    fn ids_are_stable_across_imports() {
        let scope = "account:0192f8c2-0000-7000-8000-000000000001";
        let a: Vec<String> = pluralkit(&export(), scope).unwrap().ops.into_iter().map(|o| o.id).collect();
        let b: Vec<String> = pluralkit(&export(), scope).unwrap().ops.into_iter().map(|o| o.id).collect();
        assert_eq!(a, b);
        let mut dedup = a.clone();
        crate::sort::ord(&mut dedup);
        dedup.dedup();
        assert_eq!(dedup.len(), a.len(), "no two planned ops share an id");
    }

    #[test]
    fn rfc3339() {
        assert_eq!(parse_rfc3339("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(parse_rfc3339("1970-01-01T01:00:00+01:00"), Some(0));
        assert_eq!(parse_rfc3339("2000-02-29T12:30:15.5Z"), Some(951_827_415_500));
        assert_eq!(parse_rfc3339("nope"), None);
    }

    #[test]
    fn rejects_non_exports() {
        assert!(pluralkit(&json!({"hello": 1}), "account:x").is_err());
    }
}
