//! wasm-bindgen bindings of `chorus_core::api` for the web app.
//!
//! Build: `scripts/build-web-core.ps1` (cargo → wasm-bindgen → web/src/lib/core/pkg).
//! Errors become thrown JS `Error`s carrying the message.

use chorus_core::api;
use wasm_bindgen::prelude::*;

fn wrap(r: Result<String, String>) -> Result<String, JsError> {
    r.map_err(|m| JsError::new(&m))
}

#[wasm_bindgen(js_name = coreVersion)]
pub fn core_version() -> String {
    api::version()
}

#[wasm_bindgen(js_name = parseMarkup)]
pub fn parse_markup(src: &str, names_json: &str) -> Result<String, JsError> {
    wrap(api::parse_markup(src, names_json))
}

#[wasm_bindgen(js_name = toMarkup)]
pub fn to_markup(rich_json: &str) -> Result<String, JsError> {
    wrap(api::to_markup(rich_json))
}

#[wasm_bindgen]
pub fn compose(
    src: &str,
    speakers_json: &str,
    options_json: &str,
    default_authors_json: &str,
    names_json: &str,
) -> Result<String, JsError> {
    wrap(api::compose(src, speakers_json, options_json, default_authors_json, names_json))
}

#[wasm_bindgen(js_name = foldFront)]
pub fn fold_front(ops_json: &str) -> Result<String, JsError> {
    wrap(api::fold_front(ops_json))
}

/// The web supplies local-time UTC offset transitions; core owns the day split and totals.
#[wasm_bindgen(js_name = frontDaily)]
pub fn front_daily(intervals_json: &str, now: f64, offsets_json: &str) -> Result<String, JsError> {
    use chorus_core::front::{self, Interval, Level, SubjectType};
    use serde_json::Value;

    let raw: Vec<Value> = serde_json::from_str(intervals_json).map_err(|e| JsError::new(&e.to_string()))?;
    let mut intervals = Vec::with_capacity(raw.len());
    for (position, v) in raw.into_iter().enumerate() {
        let subject_type: SubjectType =
            serde_json::from_value(v["subject_type"].clone()).map_err(|e| JsError::new(&e.to_string()))?;
        let level: Level = serde_json::from_value(v["level"].clone()).map_err(|e| JsError::new(&e.to_string()))?;
        let start_at = v["start_at"].as_i64().ok_or_else(|| JsError::new("interval start_at missing"))?;
        intervals.push(Interval {
            id: v["id"].as_str().unwrap_or("").into(),
            subject_type,
            subject_id: v["subject_id"].as_str().unwrap_or("").into(),
            level,
            is_primary: v["is_primary"].as_bool().unwrap_or(false),
            position,
            start_at,
            end_at: v["end_at"].as_i64(),
            start_switch_id: String::new(),
            end_switch_id: None,
            start_tz_offset_min: 0,
        });
    }
    let mut offsets: Vec<(i64, i32)> = serde_json::from_str(offsets_json).map_err(|e| JsError::new(&e.to_string()))?;
    offsets.sort_by_key(|x| x.0);
    let at = |t: i64| {
        let idx = offsets.partition_point(|(start, _)| *start <= t);
        offsets.get(idx.saturating_sub(1)).map_or(0, |(_, offset)| *offset)
    };
    // Split at offset changes so a DST transition inside a day cannot be charged to the
    // preceding offset's day boundary. This only prepares intervals for core::front::daily.
    let mut split = Vec::new();
    for interval in intervals {
        let end = interval.end_at.unwrap_or(now as i64);
        let mut start = interval.start_at;
        for &(transition, _) in &offsets {
            if transition <= start || transition >= end {
                continue;
            }
            let mut piece = interval.clone();
            piece.start_at = start;
            piece.end_at = Some(transition);
            split.push(piece);
            start = transition;
        }
        let mut piece = interval;
        piece.start_at = start;
        split.push(piece);
    }
    serde_json::to_string(&front::daily(&split, now as i64, at)).map_err(|e| JsError::new(&e.to_string()))
}

#[wasm_bindgen]
pub fn project(ops_json: &str) -> Result<String, JsError> {
    wrap(api::project(ops_json))
}

#[wasm_bindgen(js_name = validateOp)]
pub fn validate_op(op_json: &str) -> Result<String, JsError> {
    wrap(api::validate_op(op_json))
}

#[wasm_bindgen(js_name = feedParse)]
pub fn feed_parse(query: &str) -> Result<String, JsError> {
    wrap(api::feed_parse(query))
}

#[wasm_bindgen(js_name = feedFilter)]
pub fn feed_filter(ast_json: &str, items_json: &str, context_json: &str) -> Result<String, JsError> {
    wrap(api::feed_filter(ast_json, items_json, context_json))
}

#[wasm_bindgen(js_name = defaultSpeaker)]
pub fn default_speaker(context_json: &str) -> Result<String, JsError> {
    wrap(api::default_speaker(context_json))
}

#[wasm_bindgen(js_name = searchParse)]
pub fn search_parse(query: &str) -> Result<String, JsError> {
    wrap(api::search_parse(query))
}

#[wasm_bindgen(js_name = searchFilter)]
pub fn search_filter(query_json: &str, candidates_json: &str, context_json: &str) -> Result<String, JsError> {
    wrap(api::search_filter(query_json, candidates_json, context_json))
}

#[wasm_bindgen(js_name = adaptColor)]
pub fn adapt_color(color: &str, dark: bool, intensity: &str) -> String {
    api::adapt_color(color, dark, intensity)
}

#[wasm_bindgen(js_name = hlcTick)]
pub fn hlc_tick(last: &str, node: u32, now_ms: f64) -> Result<String, JsError> {
    wrap(api::hlc_tick(last, node, now_ms as u64))
}

#[wasm_bindgen(js_name = hlcObserve)]
pub fn hlc_observe(last: &str, node: u32, remote: &str, now_ms: f64) -> Result<String, JsError> {
    wrap(api::hlc_observe(last, node, remote, now_ms as u64))
}

/// The device's replica (engine + in-memory store + clock). See `chorus_core::replica`.
#[wasm_bindgen]
pub struct WebReplica(api::JsonReplica);

#[wasm_bindgen]
impl WebReplica {
    #[wasm_bindgen(constructor)]
    pub fn new(device_id: &str, node: u32) -> WebReplica {
        WebReplica(api::JsonReplica::new(device_id, node))
    }

    pub fn restore(
        device_id: &str,
        node: u32,
        meta_json: &str,
        ops_json: &str,
        hlc_last: &str,
    ) -> Result<WebReplica, JsError> {
        api::JsonReplica::restore(device_id, node, meta_json, ops_json, hlc_last)
            .map(WebReplica)
            .map_err(|m| JsError::new(&m))
    }

    /// Open from a snapshot: metadata now, then `addOps`, `indexStep`, `adopt` (CLIENTS.md §4.3).
    pub fn begin(device_id: &str, node: u32, meta_json: &str, hlc_last: &str) -> Result<WebReplica, JsError> {
        api::JsonReplica::begin(device_id, node, meta_json, hlc_last).map(WebReplica).map_err(|m| JsError::new(&m))
    }

    #[wasm_bindgen(js_name = addOps)]
    pub fn add_ops(&mut self, ops_json: &str) -> Result<(), JsError> {
        self.0.add_ops(ops_json).map_err(|m| JsError::new(&m))
    }

    /// Index up to `n` more ops; true when done.
    #[wasm_bindgen(js_name = indexStep)]
    pub fn index_step(&mut self, n: u32) -> bool {
        self.0.index_step(n)
    }

    /// True if the snapshot (its `projectionDigest`, or "" for none) was taken as is; false means
    /// read `projection()` again.
    pub fn adopt(&mut self, digest_json: &str) -> Result<bool, JsError> {
        self.0.adopt(digest_json).map_err(|m| JsError::new(&m))
    }

    #[wasm_bindgen(js_name = projectionDigest)]
    pub fn projection_digest(&mut self) -> String {
        self.0.projection_digest()
    }

    pub fn create(&mut self, new_op_json: &str, device_now_json: &str, random: &[u8]) -> Result<String, JsError> {
        wrap(self.0.create(new_op_json, device_now_json, random))
    }

    pub fn connect(&mut self, clock_json: &str, token: &str) -> Result<String, JsError> {
        wrap(self.0.connect(clock_json, token))
    }

    #[wasm_bindgen(js_name = onFrame)]
    pub fn on_frame(&mut self, frame_json: &str, now: f64) -> Result<String, JsError> {
        wrap(self.0.on_frame(frame_json, now as i64))
    }

    pub fn disconnect(&mut self) {
        self.0.disconnect();
    }

    /// "Sync everything now": frames asking for every scope again (empty unless live).
    pub fn recheck(&self) -> String {
        self.0.recheck()
    }

    /// Scopes still being repaired after a digest mismatch (JSON array).
    pub fn repairing(&self) -> String {
        self.0.repairing()
    }

    /// Every version of an edited message or post, oldest first (JSON array of
    /// `{rev, op_id, original, fields: {text, entities, cw?, title?, segments?}, at, hlc, device_id}`).
    pub fn revisions(&self, entity: &str) -> String {
        self.0.revisions(entity)
    }

    /// After a `welcome` with `reconcile: true`: blobs (JSON array of hashes) named by the ops
    /// being restored; upload the ones this browser has (SYNC.md §7.3).
    #[wasm_bindgen(js_name = restoringBlobs)]
    pub fn restoring_blobs(&self) -> String {
        self.0.restoring_blobs()
    }

    #[wasm_bindgen(js_name = takeChanges)]
    pub fn take_changes(&mut self) -> String {
        self.0.take_changes()
    }

    pub fn projection(&mut self) -> String {
        self.0.projection()
    }

    /// Only what changed since the last call (see `JsonReplica::projection_delta`).
    #[wasm_bindgen(js_name = projectionDelta)]
    pub fn projection_delta(&mut self) -> String {
        self.0.projection_delta()
    }

    pub fn state(&self) -> String {
        self.0.state()
    }

    pub fn rejected(&self) -> String {
        self.0.rejected()
    }
}

/// A new UUIDv7 (`random`: at least 10 bytes from `crypto.getRandomValues`).
#[wasm_bindgen(js_name = newId)]
pub fn new_id(now_ms: f64, random: &[u8]) -> Result<String, JsError> {
    let r: [u8; 10] =
        random.get(..10).and_then(|b| b.try_into().ok()).ok_or_else(|| JsError::new("need 10 random bytes"))?;
    Ok(chorus_core::id::new_id(now_ms as u64, r))
}

/// Stage plan (screenshot view): items + definition → `{"rows", "names"}`.
#[wasm_bindgen(js_name = stagePlan)]
pub fn stage_plan(items_json: &str, definition_json: &str) -> Result<String, JsError> {
    wrap(api::stage_plan(items_json, definition_json))
}

/// A follower-ceiling preset: close | gentle | private | digest | off.
#[wasm_bindgen(js_name = notifyPreset)]
pub fn notify_preset(name: &str) -> Result<String, JsError> {
    wrap(api::notify_preset(name))
}

/// Preview a PluralKit import: `{"members", "groups", "switches", "warnings"}`.
#[wasm_bindgen(js_name = planPluralkit)]
pub fn plan_pluralkit(export_json: &str, scope: &str) -> Result<String, JsError> {
    wrap(api::JsonReplica::plan_pluralkit(export_json, scope))
}

#[wasm_bindgen]
impl WebReplica {
    /// Import a PluralKit export into `scope`: `{"added", "frames"}`. Safe to repeat.
    #[wasm_bindgen(js_name = importPluralkit)]
    pub fn import_pluralkit(
        &mut self,
        export_json: &str,
        scope: &str,
        device_now_json: &str,
    ) -> Result<String, JsError> {
        wrap(self.0.import_pluralkit(export_json, scope, device_now_json))
    }
}
