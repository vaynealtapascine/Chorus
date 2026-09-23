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

    #[wasm_bindgen(js_name = takeChanges)]
    pub fn take_changes(&mut self) -> String {
        self.0.take_changes()
    }

    pub fn projection(&self) -> String {
        self.0.projection()
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
