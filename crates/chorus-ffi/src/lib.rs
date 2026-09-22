//! UniFFI bindings of `chorus_core::api` for the Android app (Kotlin).
//!
//! Build: `scripts/build-android-core.ps1` (cargo-ndk → jniLibs, then uniffi-bindgen → Kotlin).

uniffi::setup_scaffolding!();

use chorus_core::api;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{message}")]
    Invalid { message: String },
}

fn wrap(r: Result<String, String>) -> Result<String, CoreError> {
    r.map_err(|message| CoreError::Invalid { message })
}

#[uniffi::export]
pub fn core_version() -> String {
    api::version()
}

#[uniffi::export]
pub fn parse_markup(src: String, names_json: String) -> Result<String, CoreError> {
    wrap(api::parse_markup(&src, &names_json))
}

#[uniffi::export]
pub fn to_markup(rich_json: String) -> Result<String, CoreError> {
    wrap(api::to_markup(&rich_json))
}

#[uniffi::export]
pub fn compose(
    src: String,
    speakers_json: String,
    options_json: String,
    default_authors_json: String,
    names_json: String,
) -> Result<String, CoreError> {
    wrap(api::compose(&src, &speakers_json, &options_json, &default_authors_json, &names_json))
}

#[uniffi::export]
pub fn fold_front(ops_json: String) -> Result<String, CoreError> {
    wrap(api::fold_front(&ops_json))
}

#[uniffi::export]
pub fn project(ops_json: String) -> Result<String, CoreError> {
    wrap(api::project(&ops_json))
}

#[uniffi::export]
pub fn validate_op(op_json: String) -> Result<String, CoreError> {
    wrap(api::validate_op(&op_json))
}

#[uniffi::export]
pub fn feed_parse(query: String) -> Result<String, CoreError> {
    wrap(api::feed_parse(&query))
}

#[uniffi::export]
pub fn adapt_color(color: String, dark: bool, intensity: String) -> String {
    api::adapt_color(&color, dark, &intensity)
}

#[uniffi::export]
pub fn hlc_tick(last: String, node: u32, now_ms: u64) -> Result<String, CoreError> {
    wrap(api::hlc_tick(&last, node, now_ms))
}

#[uniffi::export]
pub fn hlc_observe(last: String, node: u32, remote: String, now_ms: u64) -> Result<String, CoreError> {
    wrap(api::hlc_observe(&last, node, &remote, now_ms))
}
