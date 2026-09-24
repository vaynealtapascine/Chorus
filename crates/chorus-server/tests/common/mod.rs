use std::path::PathBuf;
use std::time::Duration;

/// A fresh data directory for an HTTP test. Tests delete theirs with [`release`]; a run that
/// panicked leaves one behind, so sweep this test's old, randomly named folders when it starts.
pub fn http_test_dir(prefix: &str) -> PathBuf {
    // Cargo's scratch dir for integration tests: under the target dir, set on every platform
    let root = PathBuf::from(env!("CARGO_TARGET_TMPDIR"));
    if let Ok(entries) = std::fs::read_dir(&root) {
        for entry in entries.flatten() {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            let Some(suffix) = name.strip_prefix(prefix).and_then(|s| s.strip_prefix('-')) else { continue };
            if suffix.len() != 16 || !suffix.bytes().all(|b| b.is_ascii_hexdigit()) {
                continue;
            }
            let old = entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|at| at.elapsed().ok())
                .is_some_and(|age| age > Duration::from_secs(300));
            if old {
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
    }
    root.join(format!("{prefix}-{:016x}", rand::random::<u64>()))
}

/// Let go of a test server's state so its database closes and the data directory can be deleted
/// (Windows won't delete an open file): stop its background tasks, then wait until this is the last
/// handle. Drop the HTTP client first; its keep-alive connections hold the router until they close.
/// Then delete `dir` (if the test made it), which must succeed.
#[allow(dead_code)]
pub async fn release(state: chorus_server::app::AppState, dir: &std::path::Path) {
    state.shutdown();
    for _ in 0..500 {
        if std::sync::Arc::strong_count(&state) == 1 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let held = std::sync::Arc::strong_count(&state) - 1;
    assert_eq!(held, 0, "the server state is still held {held} times after shutdown");
    drop(state);
    match std::fs::remove_dir_all(dir) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        other => other.unwrap(),
    }
}
