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
    /// Rebuild every projection from the op log.
    Rebuild,
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
        Cmd::Rebuild => {
            let mut conn = chorus_server::open_and_migrate(&cfg)?;
            let t = std::time::Instant::now();
            let n = chorus_server::project::rebuild(&mut conn)?;
            println!("re-projected {n} ops in {:.1?}", t.elapsed());
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
