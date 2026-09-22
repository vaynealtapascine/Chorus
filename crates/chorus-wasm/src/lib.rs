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
