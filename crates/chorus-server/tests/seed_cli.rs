use std::process::Command;

use chorus_server::{db, project};

#[test]
fn seed_cli_ingests_into_new_directory_and_refuses_existing_data() {
    let target = std::path::PathBuf::from(std::env::var_os("CARGO_TARGET_DIR").unwrap());
    let root = target.join(format!("seed-cli-test-{:016x}", rand::random::<u64>()));
    let dest = root.join("data");
    let missing_config = root.join("missing.toml");
    let run = || {
        Command::new(env!("CARGO_BIN_EXE_chorus-server"))
            .args([
                "--config",
                &missing_config.to_string_lossy(),
                "seed",
                "--members",
                "3",
                "--switches",
                "4",
                "--messages",
                "5",
                "--to",
                &dest.to_string_lossy(),
            ])
            .output()
            .unwrap()
    };
    let first = run();
    assert!(first.status.success(), "{}", String::from_utf8_lossy(&first.stderr));
    assert!(String::from_utf8_lossy(&first.stdout).contains("seeded 14 ops"));
    let mut conn = db::open(&dest.join("chorus.db")).unwrap();
    for (table, expected) in [("op", 14), ("member", 3), ("switch", 4), ("message", 5)] {
        let actual: i64 = conn.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0)).unwrap();
        assert_eq!(actual, expected, "{table}");
    }
    assert_eq!(project::rebuild(&mut conn).unwrap(), 14);
    assert_eq!(conn.query_row("SELECT count(*) FROM message", [], |r| r.get::<_, i64>(0)).unwrap(), 5);
    drop(conn);
    let second = run();
    assert!(!second.status.success(), "seed must refuse to overwrite an existing directory");
    let canonical_target = target.canonicalize().unwrap();
    let canonical_root = root.canonicalize().unwrap();
    assert!(canonical_root.starts_with(&canonical_target));
    std::fs::remove_dir_all(root).unwrap();
}
