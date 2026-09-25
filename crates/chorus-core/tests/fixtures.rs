//! Conformance fixtures (SYNC.md §9.1): `fixtures/<area>/<case>.json`.
//!
//! ```json
//! { "description": "…", "fn": "parse_markup", "args": ["**hi**", ""], "expect": { … } }
//! ```
//!
//! `fn` names a `chorus_core::api` function; `args` are its string/number/bool arguments;
//! `expect` is the parsed JSON result, or `{"error": true}` when the call must fail. These files
//! are the cross-language contract: any Kotlin/TypeScript reimplementation must pass them too.
//!
//! `CHORUS_FIXTURES_BLESS=1` rewrites `expect` from the current output (review the diff!).

use std::path::{Path, PathBuf};

use chorus_core::api;
use serde_json::{Value, json};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures")
}

fn s(args: &[Value], i: usize) -> String {
    match &args[i] {
        Value::String(x) => x.clone(),
        other => other.to_string(), // JSON args may be given inline as objects/arrays
    }
}

fn call(f: &str, a: &[Value]) -> Result<String, String> {
    match f {
        "parse_markup" => api::parse_markup(&s(a, 0), &s(a, 1)),
        "to_markup" => api::to_markup(&s(a, 0)),
        "compose" => api::compose(&s(a, 0), &s(a, 1), &s(a, 2), &s(a, 3), &s(a, 4)),
        "fold_front" => api::fold_front(&s(a, 0)),
        "project" => api::project(&s(a, 0)),
        "validate_op" => api::validate_op(&s(a, 0)).map(|k| json!(k).to_string()),
        "feed_parse" => api::feed_parse(&s(a, 0)),
        "search_parse" => api::search_parse(&s(a, 0)),
        "default_speaker" => api::default_speaker(&s(a, 0)),
        "search_filter" => api::search_filter(&s(a, 0), &s(a, 1), &s(a, 2)),
        "adapt_color" => Ok(api::adapt_color(&s(a, 0), a[1].as_bool().unwrap_or(false), &s(a, 2))),
        "hlc_tick" => api::hlc_tick(&s(a, 0), a[1].as_u64().unwrap_or(0) as u32, a[2].as_u64().unwrap_or(0))
            .map(|h| json!(h).to_string()),
        "hlc_observe" => {
            api::hlc_observe(&s(a, 0), a[1].as_u64().unwrap_or(0) as u32, &s(a, 2), a[3].as_u64().unwrap_or(0))
                .map(|h| json!(h).to_string())
        }
        other => Err(format!("unknown fn {other}")),
    }
}

fn is_json(s: &str) -> Option<Value> {
    serde_json::from_str(s).ok()
}

#[test]
fn fixtures_pass() {
    let bless = std::env::var("CHORUS_FIXTURES_BLESS").is_ok();
    let mut files = Vec::new();
    for area in std::fs::read_dir(root()).expect("fixtures dir") {
        let area = area.expect("entry").path();
        if area.is_dir() {
            for f in std::fs::read_dir(&area).expect("area dir") {
                let f = f.expect("entry").path();
                if f.extension().is_some_and(|e| e == "json") {
                    files.push(f);
                }
            }
        }
    }
    files.sort();
    assert!(!files.is_empty(), "no fixtures found");
    let mut failures = Vec::new();
    for path in &files {
        let text = std::fs::read_to_string(path).expect("read");
        let mut case: Value = serde_json::from_str(&text).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        let f = case["fn"].as_str().unwrap_or_default().to_string();
        let args = case["args"].as_array().cloned().unwrap_or_default();
        let got = match call(&f, &args) {
            Ok(out) => is_json(&out).unwrap_or(Value::String(out)),
            Err(e) => json!({"error": true, "message": e}),
        };
        if bless {
            case["expect"] = got;
            let pretty = serde_json::to_string_pretty(&case).expect("json") + "\n";
            std::fs::write(path, pretty).expect("write");
            continue;
        }
        let expect = &case["expect"];
        let ok =
            if expect.get("error") == Some(&Value::Bool(true)) { got.get("error").is_some() } else { &got == expect };
        if !ok {
            failures.push(format!("{}\n  expected {expect}\n  got      {got}", path.display()));
        }
    }
    assert!(failures.is_empty(), "{} fixture(s) failed:\n{}", failures.len(), failures.join("\n"));
}
