use std::path::PathBuf;
use std::time::Duration;

/// SQLite stays open in the webhook task until the test process exits on Windows.
/// Sweep only this test's old, randomly named folders when its next run starts.
pub fn http_test_dir(prefix: &str) -> PathBuf {
    let root = std::env::var_os("CARGO_TARGET_DIR").map(PathBuf::from).unwrap_or_else(std::env::temp_dir);
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
