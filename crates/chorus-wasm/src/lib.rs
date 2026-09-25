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
    wrap(api::front_daily(intervals_json, now as i64, offsets_json))
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

#[wasm_bindgen(js_name = readReaders)]
pub fn read_readers(per_member: bool, fronting_json: &str) -> Result<String, JsError> {
    wrap(api::read_readers(per_member, fronting_json))
}

#[wasm_bindgen(js_name = readUnseenBy)]
pub fn read_unseen_by(at: f64, id: &str, marks_json: &str) -> Result<String, JsError> {
    wrap(api::read_unseen_by(at as i64, id, marks_json))
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

    /// Ops another tab of this browser made (JSON array) → frames to send (SYNC §6.1).
    #[wasm_bindgen(js_name = adoptLocal)]
    pub fn adopt_local(&mut self, ops_json: &str, now: f64) -> Result<String, JsError> {
        wrap(self.0.adopt_local(ops_json, now as i64))
    }

    /// The syncing tab's saved op copies and evicted ids (JSON arrays): this tab catches up.
    pub fn absorb(&mut self, ops_json: &str, removed_json: &str) -> Result<(), JsError> {
        self.0.absorb(ops_json, removed_json).map_err(|m| JsError::new(&m))
    }

    /// This tab takes over syncing from what the last syncing tab saved.
    #[wasm_bindgen(js_name = reloadMeta)]
    pub fn reload_meta(&mut self, meta_json: &str, hlc_last: &str, now: f64) -> Result<(), JsError> {
        self.0.reload_meta(meta_json, hlc_last, now as i64).map_err(|m| JsError::new(&m))
    }

    /// A windowed replica (SYNC §6.5): keep message-family ops written since `window` (epoch
    /// ms), or everything (`undefined`). Takes effect at the next connect.
    #[wasm_bindgen(js_name = setWindow)]
    pub fn set_window(&mut self, window: Option<f64>) {
        self.0.set_window(window.map(|w| w as i64));
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

    /// Refused ops with why, oldest first (JSON array; SYNC §7 "Sync issues").
    #[wasm_bindgen(js_name = syncIssues)]
    pub fn sync_issues(&self) -> String {
        self.0.sync_issues()
    }

    /// Forget a refused op once seen (persist with takeChanges, like any change).
    #[wasm_bindgen(js_name = dismissIssue)]
    pub fn dismiss_issue(&mut self, id: &str) -> bool {
        self.0.dismiss_issue(id)
    }

    /// Slow mode (D-076): frames for held ops whose wait is over (JSON array).
    pub fn tick(&mut self, now: f64) -> String {
        self.0.tick(now as i64)
    }

    /// Held ops, soonest first (JSON array of `{id, until}`).
    pub fn held(&self) -> String {
        self.0.held()
    }

    /// Cancel a held op before it goes out (persist with takeChanges).
    #[wasm_bindgen(js_name = cancelHeld)]
    pub fn cancel_held(&mut self, id: &str) -> bool {
        self.0.cancel_held(id)
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
