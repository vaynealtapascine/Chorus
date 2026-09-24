//! `chorus-server` — see docs/OPS.md §4 for the commands.

use std::path::PathBuf;

use clap::{Parser, Subcommand};

use chorus_server::{backup, config::Config, db, exports};

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
    /// Run the server (what the service runs).
    Serve,
    /// Run pending schema migrations (also done automatically by `serve`).
    Migrate,
    /// Integrity check and a short status report.
    Check,
    /// Rebuild every projection from the op log.
    Rebuild,
    /// Take an online SQLite and blob snapshot.
    Backup {
        /// Snapshot parent directory (defaults to backup.dir).
        #[arg(long)]
        to: Option<PathBuf>,
    },
    /// Verify a snapshot and restore it into a directory that does not exist yet.
    Restore {
        #[arg(long)]
        from: PathBuf,
        #[arg(long)]
        into: PathBuf,
    },
    /// Export one account's op log, CSV tables or filtered SQLite copy.
    Export {
        #[arg(long)]
        account: String,
        #[arg(long, value_enum)]
        kind: ExportKind,
        /// Output directory; defaults to a new directory under data/exports.
        #[arg(long)]
        to: Option<PathBuf>,
    },
    /// Create an invite link for a new system, person or device.
    Invite {
        #[arg(long, value_enum, default_value = "system")]
        kind: InviteArg,
        /// For device invites: the account to add a device to.
        #[arg(long)]
        account: Option<String>,
        /// Days until it expires.
        #[arg(long, default_value_t = 7)]
        days: i64,
        #[arg(long, default_value_t = 1)]
        uses: u32,
    },
}

#[derive(Clone, Copy, clap::ValueEnum)]
enum InviteArg {
    System,
    Person,
    Device,
}

#[derive(Clone, Copy, clap::ValueEnum)]
enum ExportKind {
    Full,
    Csv,
    Sqlite,
}

fn main() -> anyhow::Result<()> {
    // colour only on a terminal: the journal (systemd) and NSSM's log files get plain text
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stdout()))
        .init();
    let cli = Cli::parse();
    let cfg = if cli.dev { Config::dev() } else { Config::load(Some(&cli.config))? };
    match cli.cmd {
        Cmd::Serve => {
            let conn = chorus_server::open_and_migrate(&cfg)?;
            if cli.dev && conn.query_row("SELECT count(*) = 0 FROM account", [], |r| r.get::<_, bool>(0))? {
                let code = chorus_server::auth::create_invite(
                    &conn,
                    chorus_server::auth::InviteKind::System,
                    None,
                    "dev",
                    7 * 86_400_000,
                    10,
                    chorus_server::now_ms(),
                )?;
                println!("dev invite: {}/i/{code}", cfg.server.public_url.trim_end_matches('/'));
            }
            let fixed = chorus_server::auth::backfill_self_members(&conn, chorus_server::now_ms())?;
            if fixed > 0 {
                tracing::info!(accounts = fixed, "gave person accounts their self member (D-003)");
            }
            let state = chorus_server::app::Shared::new(conn, cfg)?;
            tokio::runtime::Runtime::new()?.block_on(chorus_server::app::serve(state))?;
        }
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
        Cmd::Rebuild => {
            let mut conn = chorus_server::open_and_migrate(&cfg)?;
            let t = std::time::Instant::now();
            let n = chorus_server::project::rebuild(&mut conn)?;
            println!("re-projected {n} ops in {:.1?}", t.elapsed());
        }
        Cmd::Backup { to } => {
            let snapshot = backup::create(&cfg, to.as_deref())?;
            println!("{}", snapshot.display());
            if to.is_none() {
                backup::rotate(&cfg)?;
            }
        }
        Cmd::Restore { from, into } => {
            backup::restore(&from, &into)?;
            println!("restored to {}", into.display());
        }
        Cmd::Export { account, kind, to } => {
            let conn = db::open(&cfg.db_path())?;
            let exists: bool =
                conn.query_row("SELECT EXISTS(SELECT 1 FROM account WHERE id = ?1)", [&account], |r| r.get(0))?;
            anyhow::ensure!(exists, "account does not exist: {account}");
            let out = to.unwrap_or_else(|| {
                cfg.server.data_dir.join("exports").join(format!(
                    "{}-{}-{:08x}",
                    account,
                    chorus_server::now_ms(),
                    rand::random::<u32>()
                ))
            });
            std::fs::create_dir_all(&out)?;
            let write = |name: &str, bytes: Vec<u8>| -> anyhow::Result<()> {
                use std::io::Write as _;
                let mut file = std::fs::OpenOptions::new().write(true).create_new(true).open(out.join(name))?;
                file.write_all(&bytes)?;
                file.sync_all()?;
                Ok(())
            };
            match kind {
                ExportKind::Full => write("ops.jsonl", exports::ops_jsonl(&conn, &account)?)?,
                ExportKind::Csv => {
                    for name in exports::CSV_NAMES {
                        write(&format!("{name}.csv"), exports::csv(&conn, &account, name)?.expect("known CSV"))?;
                    }
                }
                ExportKind::Sqlite => {
                    write(
                        "account.sqlite",
                        exports::sqlite_copy(&conn, &account, &cfg.server.data_dir.join("export-work"))?,
                    )?;
                }
            }
            println!("{}", out.display());
        }
        Cmd::Invite { kind, account, days, uses } => {
            use chorus_server::auth::{self, InviteKind};
            let conn = chorus_server::open_and_migrate(&cfg)?;
            let kind = match kind {
                InviteArg::System => InviteKind::System,
                InviteArg::Person => InviteKind::Person,
                InviteArg::Device => InviteKind::Device,
            };
            if kind == InviteKind::Device && account.is_none() {
                anyhow::bail!("--account is required for device invites");
            }
            let code = auth::create_invite(
                &conn,
                kind,
                account.as_deref(),
                "cli",
                days * 24 * 3_600_000,
                uses,
                chorus_server::now_ms(),
            )?;
            println!("{}/i/{code}", cfg.server.public_url.trim_end_matches('/'));
        }
    }
    Ok(())
}
