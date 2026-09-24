//! `chorus-server` — see docs/OPS.md §4 for the commands.

use std::path::PathBuf;

use clap::{Parser, Subcommand};

use chorus_server::{backup, config::Config, db, exports, seed};

#[derive(Parser)]
#[command(name = "chorus-server", version, about = "Chorus server")]
struct Cli {
    /// Path to chorus.toml (defaults apply when missing).
    #[arg(long, global = true, default_value = "chorus.toml")]
    config: PathBuf,
    /// Development profile: port 5251, data in ./data-dev.
    #[arg(long, global = true)]
    dev: bool,
    /// No command: Chorus Home's double-click (install if needed, then open Chorus).
    #[command(subcommand)]
    cmd: Option<Cmd>,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run the server (what the service runs).
    Serve,
    /// Run pending schema migrations (also done automatically by `serve`).
    Migrate,
    /// Integrity check and a short status report.
    Check,
    /// After a restore: is the restore window open, and which devices are back (SYNC.md §7.3)?
    ReconcileStatus,
    /// Close the restore window: from now on, restore pushes count as the pusher's own ops.
    ReconcileClose,
    /// Rebuild every projection from the op log (with the server stopped: into a fresh file,
    /// then swapped in).
    Rebuild {
        /// Rewrite the live file in one big transaction instead (the old way; slower).
        #[arg(long)]
        in_place: bool,
    },
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
    /// Create representative ops in a new data directory for manual performance checks.
    Seed {
        #[arg(long, default_value_t = 300)]
        members: usize,
        #[arg(long, default_value_t = 20_000)]
        switches: usize,
        #[arg(long, default_value_t = 100_000)]
        messages: usize,
        /// New data directory; defaults to the configured data_dir and refuses an existing one.
        #[arg(long)]
        to: Option<PathBuf>,
    },
    /// Erase a message or an op for good (D-053): the op ids stay, their contents don't.
    /// Asks for confirmation unless --yes; logged to <data_dir>/purge.log.
    Purge {
        /// A message: all of its ops, ops that point at it, and reactions to it.
        #[arg(long, conflicts_with_all = ["op", "account"])]
        message: Option<String>,
        /// A whole non-admin account (e.g. a test account) and everything it wrote.
        #[arg(long, conflicts_with = "op")]
        account: Option<String>,
        /// One op.
        #[arg(long)]
        op: Option<String>,
        #[arg(long)]
        yes: bool,
    },
    /// Chorus Home (D-071): install or update on this Windows PC (as administrator).
    Install {
        /// Don't open the browser afterwards.
        #[arg(long)]
        no_browser: bool,
    },
    /// Chorus Home: remove the service, shortcut and firewall rule. Data stays unless asked.
    Uninstall {
        #[arg(long)]
        delete_data: bool,
    },
    /// Chorus Home: run as the Windows service (the Service Control Manager starts this).
    Service,
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
    let cli = Cli::parse();
    if matches!(cli.cmd, Some(Cmd::Service)) {
        // no console under the service manager: log next to chorus.toml
        let log = cli.config.parent().map(|d| d.join("chorus.log")).unwrap_or_else(|| "chorus.log".into());
        let file = std::fs::OpenOptions::new().create(true).append(true).open(log)?;
        tracing_subscriber::fmt()
            .with_env_filter(
                tracing_subscriber::EnvFilter::try_from_default_env()
                    .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
            )
            .with_ansi(false)
            .with_writer(std::sync::Mutex::new(file))
            .init();
    } else {
        // colour only on a terminal: the journal (systemd) and NSSM's log files get plain text
        tracing_subscriber::fmt()
            .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
            .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stdout()))
            .init();
    }
    let Some(cmd) = cli.cmd else { return home_launcher() };
    let mut cfg = if cli.dev { Config::dev() } else { Config::load(Some(&cli.config))? };
    match cmd {
        // Chorus Home's settings page restarts the server with the new file (home.rs)
        Cmd::Serve => loop {
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
            let state = chorus_server::app::Shared::new(conn, cfg.clone())?;
            // a fresh runtime each time: dropping it ends the last run's background tasks
            tokio::runtime::Runtime::new()?.block_on(chorus_server::app::serve(state))?;
            if !chorus_server::home::take_restart() {
                break;
            }
            cfg = Config::load(Some(&cli.config))?;
        },
        Cmd::Install { no_browser } => home_install(no_browser)?,
        Cmd::Uninstall { delete_data } => home_uninstall(delete_data)?,
        Cmd::Service => home_service(cli.config)?,
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
            if chorus_server::reconcile::open(&conn, chorus_server::now_ms())? {
                println!("restore window: open (see chorus-server reconcile-status)");
            }
        }
        Cmd::ReconcileStatus => {
            use chorus_server::reconcile;
            let conn = chorus_server::open_and_migrate(&cfg)?;
            let now = chorus_server::now_ms();
            let ago = |t: i64| {
                let days = (now - t) as f64 / 86_400_000.0;
                if days < 1.0 { format!("{:.0} h ago", days * 24.0) } else { format!("{days:.0} d ago") }
            };
            match reconcile::closes_at(&conn, now)? {
                None => println!("restore window: closed"),
                Some(end) => println!(
                    "restore window: open; closes by itself in {:.1} days (or: chorus-server reconcile-close)",
                    (end - now) as f64 / 86_400_000.0
                ),
            }
            let devices = reconcile::devices(&conn)?;
            let waiting = devices.iter().filter(|d| d.back_at.is_none()).count();
            for d in &devices {
                let seen = d.last_seen_at.map_or("never seen".to_string(), |t| format!("seen {}", ago(t)));
                let back = d.back_at.map_or("not back yet".to_string(), |t| format!("back {}", ago(t)));
                println!("  {:<16} {:<20} {:<8} {seen:<16} {back}", d.account, d.name, d.platform);
            }
            println!("{} of {} devices back", devices.len() - waiting, devices.len());
        }
        Cmd::ReconcileClose => {
            let conn = chorus_server::open_and_migrate(&cfg)?;
            chorus_server::reconcile::close(&conn)?;
            println!("restore window closed");
        }
        Cmd::Rebuild { in_place } => {
            let mut conn = chorus_server::open_and_migrate(&cfg)?;
            let t = std::time::Instant::now();
            let n = if in_place {
                chorus_server::project::rebuild(&mut conn)?
            } else {
                drop(conn);
                chorus_server::project::rebuild_swap(&cfg.db_path())?
            };
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
        Cmd::Seed { members, switches, messages, to } => {
            let mut seed_cfg = cfg;
            if let Some(path) = to {
                seed_cfg.server.data_dir = path;
            }
            let result = seed::run(&seed_cfg, seed::Counts { members, switches, messages })?;
            println!(
                "seeded {} ops for account {} in {}",
                result.ops,
                result.account_id,
                seed_cfg.server.data_dir.display()
            );
        }
        Cmd::Purge { message, op, account, yes } => {
            use chorus_server::purge::{self, Target};
            let (target, asked) = match (message, op, account) {
                (Some(m), None, None) => (Target::Message(m.clone()), format!("message {m}")),
                (None, Some(o), None) => (Target::Op(o.clone()), format!("op {o}")),
                (None, None, Some(a)) => (Target::Account(a.clone()), format!("account {a}")),
                _ => anyhow::bail!("say what to purge: --message <id>, --op <id> or --account <id>"),
            };
            let mut conn = chorus_server::open_and_migrate(&cfg)?;
            purge::check(&conn, &target)?;
            let ids = purge::ops_for(&conn, &target)?;
            let whole_account = matches!(target, Target::Account(_));
            if whole_account {
                println!("This removes account {} with its devices, sessions, tokens and webhooks.", asked);
                println!("Shared spaces it created disappear for everyone in them.");
            }
            if ids.is_empty() && !whole_account {
                println!("nothing to purge for {asked}");
                return Ok(());
            }
            println!("This erases {} op(s) for {asked} from the server for good.", ids.len());
            println!("Devices that already synced them keep their copies. Projections are rebuilt.");
            if !yes {
                print!("Type purge to go on: ");
                std::io::Write::flush(&mut std::io::stdout())?;
                let mut answer = String::new();
                std::io::stdin().read_line(&mut answer)?;
                if answer.trim() != "purge" {
                    println!("nothing changed");
                    return Ok(());
                }
            }
            let done = purge::run(&cfg, &mut conn, &target, &asked)?;
            println!("purged {} op(s); logged to {}", done.len(), cfg.server.data_dir.join("purge.log").display());
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
            if let Some(lan) = chorus_server::tls::home_invite(&cfg, &code) {
                println!("on the home wifi (phones): {lan}");
            }
        }
    }
    Ok(())
}

// ─── Chorus Home (D-071, docs/HOME.md) ───────────────────────────────────────

#[cfg(windows)]
fn home_launcher() -> anyhow::Result<()> {
    use chorus_server::home_install as hi;
    let layout = hi::Layout::windows();
    if !hi::installed() {
        println!("Installing Chorus Home… Windows will ask for permission.");
        anyhow::ensure!(hi::run_elevated(&["install", "--no-browser"])?, "Chorus Home wasn't installed");
    }
    // opened from this (not elevated) process: the browser runs as the person, not as admin
    let port = hi::installed_port(&layout).unwrap_or(5250);
    std::thread::sleep(std::time::Duration::from_secs(2));
    hi::open_browser(port);
    Ok(())
}

#[cfg(not(windows))]
fn home_launcher() -> anyhow::Result<()> {
    anyhow::bail!("give a command (chorus-server --help); Chorus Home's installer is Windows-only for now")
}

#[cfg(windows)]
fn home_install(no_browser: bool) -> anyhow::Result<()> {
    use chorus_server::home_install as hi;
    if !hi::elevated() {
        let mut args = vec!["install"];
        if no_browser {
            args.push("--no-browser");
        }
        anyhow::ensure!(hi::run_elevated(&args)?, "Chorus Home wasn't installed");
        return Ok(());
    }
    let port = hi::install(&hi::Layout::windows())?;
    println!("Chorus Home is running: http://localhost:{port}/");
    if !no_browser {
        hi::open_browser(port);
    }
    Ok(())
}

#[cfg(windows)]
fn home_uninstall(delete_data: bool) -> anyhow::Result<()> {
    use chorus_server::home_install as hi;
    if !hi::elevated() {
        let mut args = vec!["uninstall"];
        if delete_data {
            args.push("--delete-data");
        }
        anyhow::ensure!(hi::run_elevated(&args)?, "Chorus Home wasn't removed");
        return Ok(());
    }
    let layout = hi::Layout::windows();
    hi::uninstall(&layout, delete_data)?;
    if delete_data {
        println!("Chorus Home and its data were removed.");
    } else {
        println!("Chorus Home was removed. Your data is still in {}.", layout.data_root.display());
    }
    Ok(())
}

#[cfg(windows)]
fn home_service(config: PathBuf) -> anyhow::Result<()> {
    chorus_server::home_install::run_service(config)
}

#[cfg(not(windows))]
fn home_install(_: bool) -> anyhow::Result<()> {
    anyhow::bail!("Chorus Home's installer is Windows-only for now (docs/HOME.md)")
}

#[cfg(not(windows))]
fn home_uninstall(_: bool) -> anyhow::Result<()> {
    anyhow::bail!("Chorus Home's installer is Windows-only for now (docs/HOME.md)")
}

#[cfg(not(windows))]
fn home_service(_: PathBuf) -> anyhow::Result<()> {
    anyhow::bail!("the service mode is Windows-only")
}
