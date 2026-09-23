//! JSON-in / JSON-out entry points shared by the FFI (Android, UniFFI) and wasm (web) wrappers.
//!
//! Every function takes and returns plain strings so both bindings stay one-liners and the
//! wire shapes are exactly the documented JSON (DATA_MODEL.md). Errors are `Err(String)` with a
//! human-readable message (feed errors are JSON `{pos, message}` for the editor).

use std::collections::BTreeMap;

use serde::Deserialize;
use serde_json::Value;

use crate::color::{self, Intensity, Theme};
use crate::front::{self, FrontOp};
use crate::hlc::{Hlc, HlcClock};
use crate::op::{self, Known, Op};
use crate::speaker::{self, Options, Speaker};
use crate::text::{self, MentionTarget, Resolver, Rich};
use crate::{feed, model};

fn js<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_else(|e| format!("{{\"error\":\"{e}\"}}"))
}

fn parse<T: for<'de> Deserialize<'de>>(what: &str, s: &str) -> Result<T, String> {
    serde_json::from_str(s).map_err(|e| format!("bad {what} JSON: {e}"))
}

pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Names the markup parser resolves: `{"mentions": {"kai": {"target_type": "member",
/// "target_id": "…"}}, "emoji": {"kai_wave": "<emoji id>"}}`. Missing = none.
#[derive(Deserialize, Default)]
pub struct Names {
    #[serde(default)]
    mentions: BTreeMap<String, MentionName>,
    #[serde(default)]
    emoji: BTreeMap<String, String>,
}

#[derive(Deserialize)]
struct MentionName {
    target_type: MentionTarget,
    #[serde(default)]
    target_id: Option<String>,
}

impl Resolver for Names {
    fn mention(&self, name: &str) -> Option<(MentionTarget, Option<String>)> {
        if name == "front" {
            return Some((MentionTarget::Front, None));
        }
        let m = self.mentions.get(name).or_else(|| self.mentions.get(&name.to_lowercase()))?;
        Some((m.target_type, m.target_id.clone()))
    }
    fn emoji(&self, name: &str) -> Option<String> {
        self.emoji.get(name).cloned()
    }
}

fn names(json: &str) -> Result<Names, String> {
    if json.trim().is_empty() { Ok(Names::default()) } else { parse("names", json) }
}

/// Markup → `{"text", "entities"}`.
pub fn parse_markup(src: &str, names_json: &str) -> Result<String, String> {
    Ok(js(&text::parse(src, &names(names_json)?)))
}

/// `{"text", "entities"}` → markup for editing.
pub fn to_markup(rich_json: &str) -> Result<String, String> {
    let r: Rich = parse("rich text", rich_json)?;
    Ok(text::to_markup(&r))
}

#[derive(Deserialize)]
struct SpeakerIn {
    member_id: String,
    #[serde(default)]
    sigils: Vec<String>,
    #[serde(default)]
    proxy_tags: Vec<speaker::ProxyTag>,
}

#[derive(Deserialize)]
struct OptionsIn {
    #[serde(default = "yes")]
    sigils: bool,
    #[serde(default = "yes")]
    segments: bool,
    #[serde(default)]
    tags_case_sensitive: bool,
}

fn yes() -> bool {
    true
}

/// What was typed → `{"rich", "segments", "authors", "explicit"}` (SPEC.md §5.2).
pub fn compose(
    src: &str,
    speakers_json: &str,
    options_json: &str,
    default_authors_json: &str,
    names_json: &str,
) -> Result<String, String> {
    let sp: Vec<SpeakerIn> = parse("speakers", speakers_json)?;
    let speakers: Vec<Speaker> = sp
        .into_iter()
        .map(|s| Speaker { member_id: s.member_id, sigils: s.sigils, proxy_tags: s.proxy_tags })
        .collect();
    let o: OptionsIn =
        if options_json.trim().is_empty() { parse("options", "{}")? } else { parse("options", options_json)? };
    let opts = Options { sigils: o.sigils, segments: o.segments, tags_case_sensitive: o.tags_case_sensitive };
    let defaults: Vec<String> = parse("default authors", default_authors_json)?;
    Ok(js(&speaker::compose(src, &speakers, opts, &defaults, &names(names_json)?)))
}

/// Ops (JSON array) → `{"switches", "intervals", "current"}` for one account.
pub fn fold_front(ops_json: &str) -> Result<String, String> {
    let ops: Vec<Op> = parse("ops", ops_json)?;
    let fronts: Vec<FrontOp> = ops.iter().filter_map(|o| FrontOp::from_op(o).ok()).collect();
    Ok(js(&front::fold(&fronts)))
}

/// Ops (JSON array) → the reference projection (for tests and debugging views).
pub fn project(ops_json: &str) -> Result<String, String> {
    let ops: Vec<Op> = parse("ops", ops_json)?;
    Ok(js(&model::project(ops.iter()).canonical()))
}

/// Structural validation: `"known"`, `"opaque"`, or an error message.
pub fn validate_op(op_json: &str) -> Result<String, String> {
    let o: Op = parse("op", op_json)?;
    match op::validate(&o) {
        Ok(Known::Yes(_)) => Ok("known".into()),
        Ok(Known::Opaque) => Ok("opaque".into()),
        Err(e) => Err(e.to_string()),
    }
}

/// Feed query → AST JSON, or `Err` with `{"pos", "message"}` JSON.
pub fn feed_parse(query: &str) -> Result<String, String> {
    feed::parse(query).map(|e| js(&e)).map_err(|e| js(&serde_json::json!({"pos": e.pos, "message": e.message})))
}

/// Member colour variants: `{"name", "ring", "tint"}`. `intensity`: off | subtle | vivid.
pub fn adapt_color(color: &str, dark: bool, intensity: &str) -> String {
    let i = match intensity {
        "off" => Intensity::Off,
        "vivid" => Intensity::Vivid,
        _ => Intensity::Subtle,
    };
    js(&color::adapt(color, if dark { Theme::ink() } else { Theme::paper() }, i))
}

/// HLC for a new local event. `last` is the persisted last HLC ("" for none).
pub fn hlc_tick(last: &str, node: u32, now_ms: u64) -> Result<String, String> {
    let mut c = clock(last, node)?;
    Ok(c.tick(now_ms).to_string())
}

/// Merge a remote HLC; returns the new local last HLC.
pub fn hlc_observe(last: &str, node: u32, remote: &str, now_ms: u64) -> Result<String, String> {
    let mut c = clock(last, node)?;
    let r: Hlc = remote.parse().map_err(|e| format!("{e}"))?;
    Ok(c.observe(r, now_ms).to_string())
}

fn clock(last: &str, node: u32) -> Result<HlcClock, String> {
    if last.is_empty() {
        Ok(HlcClock::new(node))
    } else {
        Ok(HlcClock::resume(node, last.parse().map_err(|e| format!("{e}"))?))
    }
}

/// A follower-ceiling preset (NOTIFICATIONS.md §3): close | gentle | private | digest | off.
pub fn notify_preset(name: &str) -> Result<String, String> {
    crate::notify::preset(name).map(|v| js(&v)).ok_or_else(|| format!("unknown preset {name}"))
}

/// Stage plan: items (JSON array, display order) + definition → `{"rows", "names"}`.
pub fn stage_plan(items_json: &str, definition_json: &str) -> Result<String, String> {
    let items: Vec<crate::stage::Item> = parse("stage items", items_json)?;
    let def: crate::stage::Definition = if definition_json.trim().is_empty() {
        Default::default()
    } else {
        parse("stage definition", definition_json)?
    };
    Ok(js(&crate::stage::plan(&items, &def)))
}

/// Echo for binding smoke tests.
pub fn echo_json(v: &str) -> Result<String, String> {
    let x: Value = parse("value", v)?;
    Ok(js(&x))
}

// ─── replica (JSON facade shared by the wasm and UniFFI bindings) ────────────

/// JSON facade over [`crate::replica::Replica`].
pub struct JsonReplica(pub crate::replica::Replica);

fn rand10(bytes: &[u8]) -> Result<[u8; 10], String> {
    bytes.get(..10).and_then(|b| b.try_into().ok()).ok_or_else(|| "need 10 random bytes".to_string())
}

impl JsonReplica {
    pub fn new(device_id: &str, node: u32) -> JsonReplica {
        JsonReplica(crate::replica::Replica::new(device_id, node))
    }

    /// `meta_json` / `hlc_last` may be empty strings when nothing was persisted yet.
    pub fn restore(
        device_id: &str,
        node: u32,
        meta_json: &str,
        ops_json: &str,
        hlc_last: &str,
    ) -> Result<JsonReplica, String> {
        let meta = if meta_json.trim().is_empty() { None } else { Some(parse("meta", meta_json)?) };
        let ops: Vec<Op> = if ops_json.trim().is_empty() { vec![] } else { parse("ops", ops_json)? };
        let hlc = if hlc_last.is_empty() { None } else { Some(hlc_last.parse().map_err(|e| format!("{e}"))?) };
        Ok(JsonReplica(crate::replica::Replica::restore(device_id, node, meta, ops, hlc)))
    }

    /// → `{"op": …, "frames": […]}`
    pub fn create(&mut self, new_op_json: &str, device_now_json: &str, random: &[u8]) -> Result<String, String> {
        let n: crate::replica::NewOp = parse("new op", new_op_json)?;
        let d: crate::replica::DeviceNow = parse("device now", device_now_json)?;
        let (o, frames) = self.0.create(n, &d, rand10(random)?).map_err(|e| e.to_string())?;
        Ok(js(&serde_json::json!({"op": o, "frames": frames})))
    }

    pub fn connect(&mut self, clock_json: &str, token: &str) -> Result<String, String> {
        let c: crate::sync::ClockReading = parse("clock", clock_json)?;
        Ok(js(&self.0.connect(c, token)))
    }

    /// Frame in → frames to send (JSON array).
    pub fn on_frame(&mut self, frame_json: &str, now: i64) -> Result<String, String> {
        let f: crate::sync::Frame = parse("frame", frame_json)?;
        Ok(js(&self.0.on_frame(f, now)))
    }

    pub fn disconnect(&mut self) {
        self.0.disconnect();
    }

    pub fn take_changes(&mut self) -> String {
        js(&self.0.take_changes())
    }

    pub fn projection(&mut self) -> String {
        js(&self.0.projection().canonical())
    }

    /// Only what changed since the last call (`{"full": true}` the first time: read
    /// `projection()` then). Rows/sets/fronts set to `null` are gone.
    pub fn projection_delta(&mut self) -> String {
        js(&self.0.projection_delta())
    }

    pub fn state(&self) -> String {
        format!("{:?}", self.0.state()).to_lowercase()
    }

    pub fn pending_count(&self) -> u64 {
        self.0.pending_count() as u64
    }

    pub fn rejected(&self) -> String {
        js(&self.0.store.rejected)
    }

    /// Plan only (for the preview): `{"members", "groups", "switches", "warnings"}`.
    pub fn plan_pluralkit(export_json: &str, scope: &str) -> Result<String, String> {
        let export: Value = parse("PluralKit export", export_json)?;
        Ok(js(&crate::import::pluralkit(&export, scope)?))
    }

    /// Import: → `{"added": n, "frames": […]}`. Safe to repeat.
    pub fn import_pluralkit(
        &mut self,
        export_json: &str,
        scope: &str,
        device_now_json: &str,
    ) -> Result<String, String> {
        let export: Value = parse("PluralKit export", export_json)?;
        let plan = crate::import::pluralkit(&export, scope)?;
        let d: crate::replica::DeviceNow = parse("device now", device_now_json)?;
        let (added, frames) = self.0.import(plan, &d).map_err(|e| e.to_string())?;
        Ok(js(&serde_json::json!({"added": added, "frames": frames})))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_api_smoke() {
        let r =
            parse_markup("hi **@kai**", r#"{"mentions":{"kai":{"target_type":"member","target_id":"m1"}}}"#).unwrap();
        assert!(r.contains(r#""type":"mention""#) && r.contains(r#""type":"bold""#));
        assert_eq!(to_markup(&r).unwrap(), "hi **@kai**");
        let c = compose("🌌 hello", r#"[{"member_id":"sky","sigils":["🌌"]}]"#, "", r#"["def"]"#, "").unwrap();
        assert!(c.contains(r#""authors":["sky"]"#));
        assert!(feed_parse("kind:banana").unwrap_err().contains(r#""pos":0"#));
        assert!(adapt_color("#fff27a", false, "subtle").contains("name"));
        let h = hlc_tick("", 7, 1000).unwrap();
        assert_eq!(h, "0000000003e8-0000-00000007");
        assert!(hlc_observe(&h, 7, "0000000007d0-0005-00000009", 1000).unwrap().starts_with("0000000007d0-0006"));
        assert_eq!(fold_front("[]").unwrap(), r#"{"switches":[],"intervals":[],"current":[]}"#);
    }
}
