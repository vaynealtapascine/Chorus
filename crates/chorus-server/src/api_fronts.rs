//! On-demand, own-account named history. Never use these raw causes or intervals for followers.
//! Current-state reads and message composition must keep using the incremental current index.

use chorus_core::{front, front_effective, front_history, op::Op};
use rusqlite::Connection;
use serde::Deserialize;
use serde_json::{Value, json};

use crate::{
    api_data::{DataError, Principal},
    fronts,
};

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum HistoryView {
    Own,
    Effective,
}

#[derive(Default, Deserialize)]
pub struct Selection {
    /// Body is the account ID. Front IDs are local to the caller's account namespace.
    pub front: Option<String>,
    pub history: Option<HistoryView>,
}

#[derive(Default, Deserialize)]
pub struct SwitchQuery {
    #[serde(flatten)]
    pub selection: Selection,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub limit: Option<i64>,
}

#[derive(Default, Deserialize)]
pub struct IntervalQuery {
    #[serde(flatten)]
    pub selection: Selection,
    pub from: Option<i64>,
    pub to: Option<i64>,
    pub subject: Option<String>,
    pub layer_id: Option<String>,
    pub level: Option<String>,
}

#[derive(Default, Deserialize)]
pub struct DailyQuery {
    #[serde(flatten)]
    pub selection: Selection,
    pub from: Option<String>,
    pub to: Option<String>,
    pub layer_id: Option<String>,
    pub level: Option<String>,
    /// One fixed offset, minutes east of UTC, as used by the existing daily API.
    pub tz: Option<i32>,
}

#[derive(Default, Deserialize)]
pub struct ReviewQuery {
    pub front: Option<String>,
    pub open: Option<u8>,
}

struct Log {
    front: String,
    ops: Vec<Op>,
}

fn load(conn: &Connection, p: &Principal, selection: &Selection) -> Result<Option<Log>, DataError> {
    if !p.allows("read:front") {
        return Err(DataError::Scope("read:front"));
    }
    let current = fronts::current(conn, &p.account_id)?;
    if current.is_none() && selection.front.is_none() && selection.history.is_none() {
        return Ok(None); // Preserve the legacy response and indexed SQL path until configured.
    }
    let front = selection.front.as_deref().unwrap_or(&p.account_id);
    let exists = current.as_ref().map_or(front == p.account_id, |c| c.system.fronts.contains_key(front));
    if !exists {
        return Err(DataError::Bad("no such front in this account".into()));
    }
    Ok(Some(Log { front: front.into(), ops: fronts::account_ops(conn, &p.account_id)? }))
}

fn layer<'a>(layer_id: Option<&'a str>, level: Option<&'a str>) -> Result<Option<&'a str>, DataError> {
    if layer_id.zip(level).is_some_and(|(a, b)| a != b) {
        return Err(DataError::Bad("layer_id and level must identify the same layer".into()));
    }
    Ok(layer_id.or(level))
}

/// A compatibility field only for the original IDs; labels/levels never assign semantics.
fn value<T: serde::Serialize>(row: T) -> Value {
    let mut row = serde_json::to_value(row).expect("front history has JSON-safe fields");
    fn levels(v: &mut Value) {
        match v {
            Value::Array(rows) => rows.iter_mut().for_each(levels),
            Value::Object(fields) => {
                if let Some(id @ ("front" | "cocon" | "present")) = fields.get("layer_id").and_then(Value::as_str) {
                    fields.insert("level".into(), json!(id));
                }
                fields.values_mut().for_each(levels);
            }
            _ => {}
        }
    }
    levels(&mut row);
    row
}

pub fn switches(conn: &Connection, p: &Principal, q: &SwitchQuery) -> Result<Value, DataError> {
    let Some(log) = load(conn, p, &q.selection)? else {
        return crate::api_data::legacy_switches(conn, p, q.from, q.to, q.limit);
    };
    let history = q.selection.history.unwrap_or(HistoryView::Own);
    let mut items: Vec<Value> = match history {
        HistoryView::Own => front_history::fold(&p.account_id, &log.ops)
            .histories
            .remove(&log.front)
            .unwrap_or_default()
            .switches
            .into_iter()
            .map(|row| {
                let mut row = value(row);
                row["front_id"] = json!(log.front);
                row["editable"] = json!(true);
                row
            })
            .collect(),
        HistoryView::Effective => front_effective::fold(&p.account_id, &log.front, &log.ops)
            .changes
            .into_iter()
            .map(|row| {
                let mut row = value(row);
                row["kind"] = json!("effective");
                row["resulting_front"] = row["entries"].clone();
                row["editable"] = json!(false);
                row
            })
            .collect(),
    };
    items.retain(|row| {
        row["occurred_at"].as_i64().is_some_and(|at| at >= q.from.unwrap_or(i64::MIN) && at < q.to.unwrap_or(i64::MAX))
    });
    items.sort_by(|a, b| {
        b["occurred_at"].as_i64().cmp(&a["occurred_at"].as_i64()).then_with(|| b["id"].as_str().cmp(&a["id"].as_str()))
    });
    items.truncate(q.limit.unwrap_or(200).clamp(1, 1000) as usize);
    Ok(
        json!({"front_id":log.front,"history":match history {HistoryView::Own=>"own",HistoryView::Effective=>"effective"},"items":items}),
    )
}

fn history_intervals(account: &str, log: &Log, view: HistoryView) -> (Vec<front_history::Interval>, i32) {
    match view {
        HistoryView::Own => {
            let own = front_history::fold(account, &log.ops).histories.remove(&log.front).unwrap_or_default();
            let tz = own.switches.iter().rev().find(|s| !s.retracted).map_or(0, |s| s.tz_offset_min);
            (own.intervals, tz)
        }
        HistoryView::Effective => {
            let effective = front_effective::fold(account, &log.front, &log.ops);
            let tz = effective.changes.last().map_or(0, |s| s.tz_offset_min);
            (effective.intervals, tz)
        }
    }
}

pub fn intervals(conn: &Connection, p: &Principal, q: &IntervalQuery) -> Result<Value, DataError> {
    let log = load(conn, p, &q.selection)?;
    let selected_layer = layer(q.layer_id.as_deref(), q.level.as_deref())?;
    let Some(log) = log else {
        return crate::api_data::legacy_intervals(conn, p, q.from, q.to, q.subject.as_deref(), selected_layer);
    };
    let history = q.selection.history.unwrap_or(HistoryView::Effective);
    let (intervals, _) = history_intervals(&p.account_id, &log, history);
    let items: Vec<_> = intervals
        .into_iter()
        .filter(|iv| {
            iv.start_at < q.to.unwrap_or(i64::MAX)
                && iv.end_at.is_none_or(|end| end > q.from.unwrap_or(i64::MIN))
                && q.subject.as_ref().is_none_or(|id| id == &iv.subject_id)
                && selected_layer.is_none_or(|id| id == iv.layer_id)
        })
        .map(value)
        .collect();
    Ok(
        json!({"front_id":log.front,"history":match history {HistoryView::Own=>"own",HistoryView::Effective=>"effective"},"items":items}),
    )
}

fn day_start(day: &str, tz: i32) -> Result<i64, DataError> {
    if !crate::api_reads::day_ok(day) {
        return Err(DataError::Bad(format!("{day:?} isn't a YYYY-MM-DD day")));
    }
    let start = chorus_core::search::date_start(day, tz)
        .ok_or_else(|| DataError::Bad(format!("{day:?} isn't a calendar day")))?;
    let date = chorus_core::front::civil_date((start + i64::from(tz) * 60_000).div_euclid(86_400_000));
    if date != day {
        return Err(DataError::Bad(format!("{day:?} isn't a calendar day")));
    }
    Ok(start)
}

pub fn daily(conn: &Connection, p: &Principal, q: &DailyQuery, now: i64) -> Result<Value, DataError> {
    let selection = Selection {
        front: q.selection.front.clone(),
        history: q.selection.history.or(q.tz.map(|_| HistoryView::Effective)),
    };
    let log = load(conn, p, &selection)?;
    let selected_layer = layer(q.layer_id.as_deref(), q.level.as_deref())?;
    if q.tz.is_some_and(|tz| !(-1440..=1440).contains(&tz)) {
        return Err(DataError::Bad("tz must be minutes east of UTC within -1440..1440".into()));
    }
    let Some(log) = log else {
        return crate::api_reads::legacy_daily(conn, p, q.from.as_deref(), q.to.as_deref(), selected_layer);
    };
    let history = q.selection.history.unwrap_or(HistoryView::Effective);
    let (mut intervals, latest_tz) = history_intervals(&p.account_id, &log, history);
    let tz = q.tz.unwrap_or(latest_tz).clamp(-1440, 1440);
    // The API's four-digit day range also bounds iteration for extreme restored timestamps.
    let from = day_start(q.from.as_deref().unwrap_or("0000-01-01"), tz)?;
    let to = day_start(q.to.as_deref().unwrap_or("9999-12-31"), tz)? + 86_400_000;
    intervals.retain(|iv| selected_layer.is_none_or(|id| id == iv.layer_id));
    for iv in &mut intervals {
        iv.start_at = iv.start_at.max(from);
        iv.end_at = Some(iv.end_at.unwrap_or(now).min(now).min(to));
    }
    intervals.retain(|iv| iv.end_at.is_some_and(|end| end > iv.start_at));
    let items: Vec<_> = front_history::own_daily(&intervals, now, |_| tz).into_iter().map(value).collect();
    Ok(
        json!({"front_id":log.front,"history":match history {HistoryView::Own=>"own",HistoryView::Effective=>"effective"},"tz":tz,"items":items}),
    )
}

pub fn reviews(conn: &Connection, p: &Principal, q: &ReviewQuery) -> Result<Value, DataError> {
    let selection = Selection { front: q.front.clone(), history: None };
    let Some(log) = load(conn, p, &selection)? else {
        return crate::api_reads::legacy_reviews(conn, p, q.open.unwrap_or(0) != 0);
    };
    let folded = front_history::fold(&p.account_id, &log.ops);
    let by_id: std::collections::HashMap<_, _> = log.ops.iter().map(|o| (o.id.as_str(), o)).collect();
    let resolutions = front::review_resolutions(&p.account_id, &log.ops);
    let mut items = Vec::new();
    for review in folded.reviews.get(&log.front).into_iter().flatten() {
        // Resolve from the authoritative account log, including a resolution received before
        // the switches. A derived legacy review row may not exist for these named events.
        let resolution = resolutions.get(review.id.as_str()).copied();
        if q.open.unwrap_or(0) != 0 && resolution.is_some() {
            continue;
        }
        let created = [review.switch_a.as_str(), review.switch_b.as_str()]
            .into_iter()
            .filter_map(|id| by_id.get(id))
            .map(|o| o.received_at.unwrap_or(o.time()))
            .max();
        items.push(json!({"id":review.id,"front_id":log.front,"switch_a":review.switch_a,
            "switch_b":review.switch_b,"created_at":created,
            "resolution":resolution.and_then(|o|o.payload.get("resolution")),
            "resolved_at":resolution.map(Op::time)}));
    }
    items.sort_by(|a, b| {
        b["created_at"].as_i64().cmp(&a["created_at"].as_i64()).then_with(|| a["id"].as_str().cmp(&b["id"].as_str()))
    });
    Ok(json!({"front_id":log.front,"items":items}))
}

#[cfg(test)]
mod tests {
    use super::*;
    use chorus_core::{hlc::Hlc, id::new_id};

    const BODY: &str = "0192f8c2-0000-7000-8000-0000000000a1";
    const SOURCE: &str = "0192f8c2-0000-7000-8000-0000000000a2";
    const OTHER: &str = "0192f8c2-0000-7000-8000-0000000000a3";
    const T: i64 = 1_790_000_000_000;

    fn database() -> Connection {
        let mut conn = crate::db::open_memory().unwrap();
        crate::db::migrate(&mut conn).unwrap();
        conn
    }
    fn op(i: u64, account: &str, front: &str, kind: &str, payload: Value) -> Op {
        let at = T + i as i64 * 1000;
        serde_json::from_value(json!({"id":new_id(at as u64,[i as u8;10]),"scope":format!("account:{account}"),
            "kind":kind,"entity_id":front,"hlc":Hlc::new(at as u64,0,1),"device_at":at,
            "occurred_at":at,"received_at":at,"account_id":account,"device_id":"device1","payload":payload}))
        .unwrap()
    }
    fn insert(conn: &Connection, mut op: Op) -> Op {
        op.seq = Some(crate::oplog::insert(conn, &op, false, false).unwrap());
        crate::project::after_insert(conn, &op).unwrap();
        op
    }
    fn presence(member: &str, layer: &str) -> Value {
        json!({"subject_type":"member","subject_id":member,"layer_id":layer})
    }
    fn selected(front: &str, history: HistoryView) -> Selection {
        Selection { front: Some(front.into()), history: Some(history) }
    }

    #[test]
    fn own_edits_keep_following_and_effective_reads_keep_the_original_causes() {
        let conn = database();
        let p = Principal::owner(BODY);
        let first = insert(
            &conn,
            op(
                1,
                BODY,
                BODY,
                "front.switch",
                json!({"entries":[{"subject_type":"member","subject_id":"saved","level":"front"}]}),
            ),
        );
        let source = insert(
            &conn,
            op(2, BODY, SOURCE, "front.change", json!({"action":"switch","entries":[presence("inherited","quiet")]})),
        );
        insert(&conn, op(3, BODY, BODY, "front.define", json!({"follow_source":SOURCE})));
        insert(
            &conn,
            op(4, BODY, SOURCE, "front.change", json!({"action":"switch","entries":[presence("later","quiet")]})),
        );
        let missed = insert(
            &conn,
            op(
                5,
                BODY,
                BODY,
                "front.change",
                json!({"action":"switch","effective_at":T+1500,"history_only":true,"entries":[presence("missed","quiet")]}),
            ),
        );
        insert(
            &conn,
            op(
                6,
                BODY,
                SOURCE,
                "front.change",
                json!({"action":"amend","target_op_id":source.id,"entries":[presence("corrected","quiet")]}),
            ),
        );

        let own = switches(&conn, &p, &SwitchQuery::default()).unwrap();
        assert_eq!(own["history"], "own");
        assert_eq!(own["items"][0]["id"], missed.id);
        assert_eq!(own["items"][1]["id"], first.id);
        assert_eq!(own["items"][0]["resulting_front"][0]["layer_id"], "quiet");
        assert!(own["items"][0]["resulting_front"][0].get("level").is_none());
        assert_eq!(
            fronts::current(&conn, BODY).unwrap().unwrap().resolved[BODY].state,
            chorus_core::front_system::FollowState::Following
        );

        let effective =
            intervals(&conn, &p, &IntervalQuery { from: Some(T + 3000), to: Some(T + 4000), ..Default::default() })
                .unwrap();
        assert_eq!(effective["history"], "effective");
        assert_eq!(effective["items"].as_array().unwrap().len(), 1);
        assert_eq!(effective["items"][0]["subject_id"], "corrected");
        assert_eq!(effective["items"][0]["start_at"], T + 3000);
        assert_eq!(effective["items"][0]["end_at"], T + 4000);
        let saved =
            intervals(&conn, &p, &IntervalQuery { selection: selected(BODY, HistoryView::Own), ..Default::default() })
                .unwrap();
        assert_eq!(saved["items"][0]["level"], "front");
        assert!(saved["items"].as_array().unwrap().iter().any(|r| r["subject_id"] == "missed"));
        let changes = switches(
            &conn,
            &p,
            &SwitchQuery {
                selection: selected(BODY, HistoryView::Effective),
                from: Some(T + 3000),
                to: Some(T + 4000),
                limit: Some(1),
            },
        )
        .unwrap();
        assert_eq!(changes["items"].as_array().unwrap().len(), 1);
        assert_eq!(changes["items"][0]["editable"], false);
        assert_eq!(changes["items"][0]["causes"][0]["front_id"], BODY);
        assert_ne!(changes["items"][0]["id"], source.id);
        // Another account may use the same front ID without sharing history or metadata.
        insert(
            &conn,
            op(7, OTHER, BODY, "front.change", json!({"action":"switch","entries":[presence("foreign","quiet")]})),
        );
        let foreign = switches(
            &conn,
            &Principal::owner(OTHER),
            &SwitchQuery { selection: selected(BODY, HistoryView::Own), ..Default::default() },
        )
        .unwrap();
        assert_eq!(foreign["items"].as_array().unwrap().len(), 1);
        assert_eq!(foreign["items"][0]["entries"][0]["subject_id"], "foreign");
        assert!(!own.to_string().contains("foreign"));
        assert!(matches!(
            switches(&conn, &p, &SwitchQuery { selection: selected(OTHER, HistoryView::Own), ..Default::default() }),
            Err(DataError::Bad(_))
        ));
    }

    #[test]
    fn daily_clips_requested_local_days_and_closed_future_ends_without_layer_aliases() {
        let conn = database();
        let p = Principal::owner(BODY);
        let midnight = chorus_core::search::date_start("2026-10-26", 0).unwrap();
        let start = midnight - 30_000;
        insert(&conn, op(1, BODY, BODY, "front.layer", json!({"layer_id":"quiet","name":"Fronting","level":0})));
        insert(
            &conn,
            op(
                2,
                BODY,
                BODY,
                "front.change",
                json!({"action":"switch","effective_at":start,"entries":[presence("member","quiet")]}),
            ),
        );
        insert(
            &conn,
            op(
                3,
                BODY,
                BODY,
                "front.change",
                json!({"action":"switch","effective_at":midnight+120_000,"entries":[presence("future","quiet")]}),
            ),
        );
        let q = DailyQuery {
            from: Some("2026-10-25".into()),
            to: Some("2026-10-26".into()),
            tz: Some(0),
            ..Default::default()
        };
        let d = daily(&conn, &p, &q, midnight + 60_000).unwrap();
        assert_eq!(d["items"].as_array().unwrap().len(), 2);
        assert_eq!(d["items"][0]["seconds"], 30);
        assert_eq!(d["items"][1]["seconds"], 60);
        assert!(d["items"][0].get("level").is_none());
        assert!(!d.to_string().contains("future"));
        let shifted = daily(&conn, &p, &DailyQuery { tz: Some(60), ..q }, midnight + 60_000).unwrap();
        assert_eq!(shifted["items"].as_array().unwrap().len(), 1);
        assert_eq!(shifted["items"][0]["day"], "2026-10-26");
        assert_eq!(shifted["items"][0]["seconds"], 90);
        assert!(
            daily(&conn, &p, &DailyQuery { from: Some("2026-02-30".into()), ..Default::default() }, midnight).is_err()
        );
        assert!(daily(&conn, &p, &DailyQuery { tz: Some(i32::MAX), ..Default::default() }, midnight).is_err());
        assert!(
            intervals(
                &conn,
                &p,
                &IntervalQuery { level: Some("front".into()), layer_id: Some("quiet".into()), ..Default::default() }
            )
            .is_err()
        );
    }

    #[test]
    fn named_reviews_use_account_log_resolutions_even_when_the_resolution_arrives_first() {
        let conn = database();
        let p = Principal::owner(BODY);
        let a = op(1, BODY, SOURCE, "front.change", json!({"action":"switch","entries":[presence("a","quiet")]}));
        let mut b = op(2, BODY, SOURCE, "front.change", json!({"action":"switch","entries":[presence("b","quiet")]}));
        b.device_id = Some("device2".into());
        let mut reference = vec![a.clone(), b.clone()];
        reference[0].seq = Some(2);
        reference[1].seq = Some(3);
        let review = front_history::fold(BODY, &reference).reviews[SOURCE][0].clone();
        let resolution = insert(
            &conn,
            op(4, BODY, BODY, "front.review_resolve", json!({"review_id":review.id,"resolution":"keep_both"})),
        );
        insert(&conn, a);
        insert(&conn, b);
        let mut future =
            op(5, BODY, BODY, "front.review_resolve", json!({"review_id":review.id,"resolution":"unsupported"}));
        future.v = 2;
        insert(&conn, future);
        let q = ReviewQuery { front: Some(SOURCE.into()), open: Some(1) };
        assert_eq!(reviews(&conn, &p, &q).unwrap()["items"], json!([]));
        let r = reviews(&conn, &p, &ReviewQuery { open: Some(0), ..q }).unwrap();
        assert_eq!(r["items"].as_array().unwrap().len(), 1);
        assert_eq!(r["items"][0]["id"], review.id);
        assert_eq!(r["items"][0]["resolution"], "keep_both");
        assert_eq!(r["items"][0]["resolved_at"], resolution.time());
        assert_eq!(reviews(&conn, &p, &ReviewQuery::default()).unwrap()["items"], json!([]));
    }

    #[test]
    fn unconfigured_accounts_keep_legacy_shapes_and_explicit_reads_can_request_the_new_contract() {
        let conn = database();
        let p = Principal::owner(BODY);
        insert(
            &conn,
            op(
                1,
                BODY,
                BODY,
                "front.switch",
                json!({"entries":[{"subject_type":"member","subject_id":"saved","level":"cocon"}]}),
            ),
        );
        assert_eq!(
            switches(&conn, &p, &SwitchQuery::default()).unwrap(),
            crate::api_data::legacy_switches(&conn, &p, None, None, None).unwrap()
        );
        assert_eq!(
            intervals(&conn, &p, &IntervalQuery::default()).unwrap(),
            crate::api_data::legacy_intervals(&conn, &p, None, None, None, None).unwrap()
        );
        assert_eq!(
            daily(&conn, &p, &DailyQuery::default(), T + 5000).unwrap(),
            crate::api_reads::legacy_daily(&conn, &p, None, None, None).unwrap()
        );
        let explicit = intervals(
            &conn,
            &p,
            &IntervalQuery { selection: selected(BODY, HistoryView::Effective), ..Default::default() },
        )
        .unwrap();
        assert_eq!(explicit["items"][0]["layer_id"], "cocon");
        assert_eq!(explicit["items"][0]["level"], "cocon");
    }

    #[test]
    fn legacy_review_resolution_cannot_mutate_another_accounts_card() {
        let conn = database();
        let review = "0123456789abcdef0123456789abcdef";
        conn.execute(
            "INSERT INTO front_review (id,account_id,switch_a,switch_b,created_at) VALUES (?1,?2,'a','b',0)",
            rusqlite::params![review, BODY],
        )
        .unwrap();
        insert(&conn, op(1, OTHER, OTHER, "front.review_resolve", json!({"review_id":review,"resolution":"keep_a"})));
        let open =
            reviews(&conn, &Principal::owner(BODY), &ReviewQuery { open: Some(1), ..Default::default() }).unwrap();
        assert_eq!(open["items"].as_array().unwrap().len(), 1);
        assert!(open["items"][0]["resolution"].is_null());
        let own = insert(
            &conn,
            op(2, BODY, BODY, "front.review_resolve", json!({"review_id":review,"resolution":"keep_both"})),
        );
        let resolved = reviews(&conn, &Principal::owner(BODY), &ReviewQuery::default()).unwrap();
        assert_eq!(resolved["items"][0]["resolution"], "keep_both");
        assert_eq!(resolved["items"][0]["resolved_at"], own.time());
        assert_eq!(
            reviews(&conn, &Principal::owner(BODY), &ReviewQuery { open: Some(1), ..Default::default() }).unwrap()["items"],
            json!([])
        );
    }

    #[test]
    fn legacy_and_named_review_choices_survive_early_delivery_equal_clocks_and_rebuild() {
        let mut conn = database();
        let a = op(
            1,
            BODY,
            BODY,
            "front.switch",
            json!({"entries":[{"subject_type":"member","subject_id":"a","level":"front"}]}),
        );
        let mut b = op(
            2,
            BODY,
            BODY,
            "front.switch",
            json!({"entries":[{"subject_type":"member","subject_id":"b","level":"front"}]}),
        );
        b.device_id = Some("device2".into());
        let reference = [a.clone(), b.clone()]
            .into_iter()
            .enumerate()
            .map(|(i, mut o)| {
                o.seq = Some(i as i64 + 1);
                front::FrontOp::from_op(&o).unwrap()
            })
            .collect::<Vec<_>>();
        let review = front::reviews(&reference, &front::fold(&reference), front::DEFAULT_REVIEW_WINDOW_MS)[0].clone();
        let choice = insert(
            &conn,
            op(4, BODY, BODY, "front.review_resolve", json!({"review_id":review.id,"resolution":"keep_both"})),
        );
        insert(&conn, a);
        insert(&conn, b);
        let principal = Principal::owner(BODY);
        assert_eq!(reviews(&conn, &principal, &ReviewQuery::default()).unwrap()["items"][0]["resolution"], "keep_both");
        insert(&conn, op(3, BODY, BODY, "front.review_resolve", json!({"review_id":review.id,"resolution":"keep_b"})));
        let mut tied = op(0, BODY, BODY, "front.review_resolve", json!({"review_id":review.id,"resolution":"keep_a"}));
        tied.hlc = choice.hlc;
        let tied = insert(&conn, tied); // Canonical smaller ID wins the same-clock tie.
        let inspect = |conn: &Connection| {
            for front in [None, Some(BODY.into())] {
                let data = reviews(conn, &principal, &ReviewQuery { front, ..Default::default() }).unwrap();
                assert_eq!(data["items"][0]["id"], review.id);
                assert_eq!(data["items"][0]["resolution"], "keep_a");
                assert_eq!(data["items"][0]["resolved_at"], tied.time());
            }
            assert_eq!(
                reviews(conn, &principal, &ReviewQuery { open: Some(1), ..Default::default() }).unwrap()["items"],
                json!([])
            );
            let clock: String =
                conn.query_row("SELECT clocks FROM front_review WHERE id=?1", [&review.id], |r| r.get(0)).unwrap();
            assert_eq!(serde_json::from_str::<Value>(&clock).unwrap()["resolution"], choice.hlc.to_string());
        };
        inspect(&conn);
        crate::project::rebuild(&mut conn).unwrap();
        inspect(&conn);
        let plan: String = conn.query_row(
            "EXPLAIN QUERY PLAN SELECT id FROM op WHERE scope=?1 AND kind='front.review_resolve' AND status='applied'
             AND json_extract(payload,'$.review_id')=?2 AND seq<=?3",
            rusqlite::params![format!("account:{BODY}"),review.id,i64::MAX],|r|r.get(3)).unwrap();
        assert!(plan.contains("op_front_review_choice"), "{plan}");
    }

    #[test]
    fn review_choice_migration_repairs_derived_rows_without_changing_operations() {
        let mut conn = crate::db::open_memory().unwrap();
        // everything before 0013, which repairs the derived cards
        let upto = crate::db::MIGRATIONS.iter().position(|(n, _)| *n == "0013_front_review_resolution").unwrap();
        for (_, sql) in crate::db::MIGRATIONS.iter().take(upto) {
            conn.execute_batch(sql).unwrap();
        }
        crate::db::set_meta(&conn, "schema_version", &upto.to_string()).unwrap();
        let review = "card";
        let winner = op(2, BODY, BODY, "front.review_resolve", json!({"review_id":review,"resolution":"keep_a"}));
        let mut future = op(5, BODY, BODY, "front.review_resolve", json!({"review_id":review,"resolution":"keep_b"}));
        future.v = 2;
        for choice in [
            winner.clone(),
            op(1, BODY, BODY, "front.review_resolve", json!({"review_id":review,"resolution":"keep_b"})),
            op(3, OTHER, OTHER, "front.review_resolve", json!({"review_id":review,"resolution":"merge"})),
            op(4, BODY, BODY, "front.review_resolve", json!({"review_id":review,"resolution":"unknown_legacy_value"})),
            future,
        ] {
            crate::oplog::insert(&conn, &choice, false, false).unwrap();
        }
        conn.execute(
            "INSERT INTO front_review(id,account_id,switch_a,switch_b,created_at,resolution,resolved_at)
            VALUES (?1,?2,'a','b',0,'keep_b',0)",
            rusqlite::params![review, BODY],
        )
        .unwrap();
        let before = crate::oplog::for_scope_kinds(&conn, &format!("account:{BODY}"), "front.").unwrap();
        assert_eq!(crate::db::migrate(&mut conn).unwrap(), crate::db::MIGRATIONS.len() - upto);
        let row: (String, i64, String) = conn
            .query_row("SELECT resolution,resolved_at,clocks FROM front_review WHERE id=?1", [review], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?))
            })
            .unwrap();
        assert_eq!(row.0, "keep_a");
        assert_eq!(row.1, winner.time());
        assert_eq!(serde_json::from_str::<Value>(&row.2).unwrap()["resolution"], winner.hlc.to_string());
        assert_eq!(crate::oplog::for_scope_kinds(&conn, &format!("account:{BODY}"), "front.").unwrap(), before);
        assert_eq!(crate::db::migrate(&mut conn).unwrap(), 0);
    }
}
