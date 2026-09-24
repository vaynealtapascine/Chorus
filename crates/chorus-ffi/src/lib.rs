//! UniFFI bindings of `chorus_core::api` for the Android app (Kotlin).
//!
//! Build: `scripts/build-android-core.ps1` (cargo-ndk → jniLibs, then uniffi-bindgen → Kotlin).

uniffi::setup_scaffolding!();

use chorus_core::api;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{reason}")]
    Invalid { reason: String },
}

fn wrap(r: Result<String, String>) -> Result<String, CoreError> {
    r.map_err(|reason| CoreError::Invalid { reason })
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

/// Stage plan (screenshot view): items + definition → `{"rows", "names"}`.
#[uniffi::export]
pub fn stage_plan(items_json: String, definition_json: String) -> Result<String, CoreError> {
    wrap(api::stage_plan(&items_json, &definition_json))
}

/// A new UUIDv7 id (`random` = 10 bytes).
#[uniffi::export]
pub fn new_id(now_ms: u64, random: Vec<u8>) -> Result<String, CoreError> {
    let r: [u8; 10] = random
        .get(..10)
        .and_then(|b| b.try_into().ok())
        .ok_or_else(|| CoreError::Invalid { reason: "need 10 random bytes".into() })?;
    Ok(chorus_core::id::new_id(now_ms, r))
}

/// Preview a PluralKit import: `{"members", "groups", "switches", "warnings"}`.
#[uniffi::export]
pub fn plan_pluralkit(export_json: String, scope: String) -> Result<String, CoreError> {
    wrap(api::JsonReplica::plan_pluralkit(&export_json, &scope))
}

/// The device's replica (engine + in-memory store + clock). See `chorus_core::replica`.
#[derive(uniffi::Object)]
pub struct CoreReplica(std::sync::Mutex<api::JsonReplica>);

impl CoreReplica {
    fn lock(&self) -> std::sync::MutexGuard<'_, api::JsonReplica> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl CoreReplica {
    #[uniffi::constructor]
    pub fn new(device_id: String, node: u32) -> std::sync::Arc<Self> {
        std::sync::Arc::new(CoreReplica(std::sync::Mutex::new(api::JsonReplica::new(&device_id, node))))
    }

    #[uniffi::constructor]
    pub fn restore(
        device_id: String,
        node: u32,
        meta_json: String,
        ops_json: String,
        hlc_last: String,
    ) -> Result<std::sync::Arc<Self>, CoreError> {
        let r = api::JsonReplica::restore(&device_id, node, &meta_json, &ops_json, &hlc_last)
            .map_err(|reason| CoreError::Invalid { reason })?;
        Ok(std::sync::Arc::new(CoreReplica(std::sync::Mutex::new(r))))
    }

    pub fn create(&self, new_op_json: String, device_now_json: String, random: Vec<u8>) -> Result<String, CoreError> {
        wrap(self.lock().create(&new_op_json, &device_now_json, &random))
    }

    pub fn connect(&self, clock_json: String, token: String) -> Result<String, CoreError> {
        wrap(self.lock().connect(&clock_json, &token))
    }

    pub fn on_frame(&self, frame_json: String, now: i64) -> Result<String, CoreError> {
        wrap(self.lock().on_frame(&frame_json, now))
    }

    pub fn disconnect(&self) {
        self.lock().disconnect();
    }

    /// "Sync everything now": frames asking for every scope again (JSON array; empty unless live).
    pub fn recheck(&self) -> String {
        self.lock().recheck()
    }

    /// Scopes still being repaired after a digest mismatch (JSON array).
    pub fn repairing(&self) -> String {
        self.lock().repairing()
    }

    pub fn take_changes(&self) -> String {
        self.lock().take_changes()
    }

    pub fn projection(&self) -> String {
        self.lock().projection()
    }

    /// Only what changed since the last call (`{"full": true}` the first time).
    pub fn projection_delta(&self) -> String {
        self.lock().projection_delta()
    }

    pub fn state(&self) -> String {
        self.lock().state()
    }

    pub fn pending_count(&self) -> u64 {
        self.lock().pending_count()
    }

    pub fn rejected(&self) -> String {
        self.lock().rejected()
    }

    /// → `{"added": n, "frames": […]}`. Safe to repeat.
    pub fn import_pluralkit(
        &self,
        export_json: String,
        scope: String,
        device_now_json: String,
    ) -> Result<String, CoreError> {
        wrap(self.lock().import_pluralkit(&export_json, &scope, &device_now_json))
    }
}
