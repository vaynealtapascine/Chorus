//! `chorus-server` — see docs/OPS.md §4 for the commands.

use std::path::PathBuf;

use clap::{Parser, Subcommand};

use chorus_server::{config::Config, db};

#[derive(Parser)]
#[command(name = "chorus-server", version, about = "Chorus server")]
struct Cli {
    /// Path to chorus.toml (defaults apply when missing).
    #[arg(long, global = true, default_value = "chorus.toml")]
    config: PathBuf,
    /// Development profile: port 5251, data in ./data-dev.
    #[arg(long, global = true)]
    dev: bool,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run pending schema migrations (also done automatically by `serve`).
    Migrate,
    /// Integrity check and a short status report.
    Check,
}

fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::from_default_env()).init();
    let cli = Cli::parse();
    let cfg = if cli.dev { Config::dev() } else { Config::load(Some(&cli.config))? };
    match cli.cmd {
        Cmd::Migrate => {
            let conn = chorus_server::open_and_migrate(&cfg)?;
            println!("schema version {}", db::schema_version(&conn)?);
            conn.execute_batch("PRAGMA optimize")?;
        }
        Cmd::Check => {
            let conn = db::open(&cfg.db_path())?;
            let ok: String = conn.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
            let ops: i64 = conn.query_row("SELECT count(*) FROM op", [], |r| r.get(0)).unwrap_or(0);
            println!("integrity: {ok}\nschema version: {}\nops: {ops}", db::schema_version(&conn)?);
        }
    }
    Ok(())
}
