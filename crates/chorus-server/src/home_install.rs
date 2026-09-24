//! Chorus Home install, update and uninstall on Windows (D-071, docs/HOME.md §2). One program:
//! double-clicked it installs itself (asking Windows for permission once) and opens Chorus in the
//! browser; run again it updates in place. No installer toolchain.
//!
//! Layout: the program and the web app in `%ProgramFiles%\Chorus Home\`, everything else in
//! `%ProgramData%\Chorus Home\` (`chorus.toml`, `data\`, `backups\`, `chorus.log`), the
//! `ChorusHome` service (distinct from the technical install's `Chorus`), a firewall rule for
//! private networks on the home-wifi port, a Start-menu shortcut and an *Apps* entry.

use std::path::{Path, PathBuf};

pub const SERVICE: &str = "ChorusHome";
pub const DISPLAY: &str = "Chorus Home";

/// Where things go on this machine.
#[derive(Clone, Debug)]
pub struct Layout {
    pub program_dir: PathBuf,
    pub data_root: PathBuf,
}

impl Layout {
    pub fn windows() -> Layout {
        let pf = std::env::var_os("ProgramFiles").map(PathBuf::from).unwrap_or_else(|| r"C:\Program Files".into());
        let pd = std::env::var_os("ProgramData").map(PathBuf::from).unwrap_or_else(|| r"C:\ProgramData".into());
        Layout { program_dir: pf.join(DISPLAY), data_root: pd.join(DISPLAY) }
    }
    pub fn exe(&self) -> PathBuf {
        self.program_dir.join("chorus-server.exe")
    }
    pub fn web_dir(&self) -> PathBuf {
        self.program_dir.join("web")
    }
    pub fn config(&self) -> PathBuf {
        self.data_root.join("chorus.toml")
    }
    pub fn log(&self) -> PathBuf {
        self.data_root.join("chorus.log")
    }
}

fn fwd(p: &Path) -> String {
    p.to_string_lossy().replace('\\', "/")
}

/// The first install's `chorus.toml`: this PC's browser on `localhost:<port>`, phones on the home
/// wifi on `<port + 1>` over TLS (tls.rs), the web app from the program folder.
pub fn first_config(layout: &Layout, port: u16) -> String {
    format!(
        "# Chorus Home (docs/HOME.md). Change these in Chorus: More → This computer.\n\
         [server]\n\
         home = true\n\
         listen = \"127.0.0.1:{port}\"\n\
         lan_listen = \"0.0.0.0:{lan}\"\n\
         public_url = \"http://localhost:{port}\"\n\
         data_dir = \"{data}\"\n\
         web_dir = \"{web}\"\n\
         \n\
         [backup]\n\
         dir = \"{backups}\"\n\
         keep_daily = 14\n",
        lan = port + 1,
        data = fwd(&layout.data_root.join("data")),
        web = fwd(&layout.web_dir()),
        backups = fwd(&layout.data_root.join("backups")),
    )
}

fn free(port: u16) -> bool {
    std::net::TcpListener::bind(("0.0.0.0", port)).is_ok() && std::net::TcpListener::bind(("127.0.0.1", port)).is_ok()
}

/// The first pair `(p, p + 1)` from 5250 up with both ports free (the owner's technical install,
/// for one, already has 5250).
pub fn pick_port() -> u16 {
    (5250..6000).step_by(2).find(|p| free(*p) && free(p + 1)).unwrap_or(5250)
}

/// The port an installed config uses, for opening the browser.
pub fn installed_port(layout: &Layout) -> Option<u16> {
    let cfg = crate::config::Config::load(Some(&layout.config())).ok()?;
    cfg.server.listen.rsplit(':').next()?.parse().ok()
}

#[cfg(windows)]
pub use windows_impl::*;

#[cfg(windows)]
mod windows_impl {
    use super::*;
    use std::ffi::OsString;
    use std::process::Command;
    use std::time::Duration;
    use windows_service::service::{
        ServiceAccess, ServiceAction, ServiceActionType, ServiceErrorControl, ServiceFailureActions,
        ServiceFailureResetPeriod, ServiceInfo, ServiceStartType, ServiceState, ServiceType,
    };
    use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

    #[cfg(feature = "home-bundle")]
    static WEB: include_dir::Dir<'_> = include_dir::include_dir!("$CARGO_MANIFEST_DIR/../../web/dist");

    const UNINSTALL_KEY: &str = r"HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\ChorusHome";

    fn manager(access: ServiceManagerAccess) -> windows_service::Result<ServiceManager> {
        ServiceManager::local_computer(None::<&str>, access)
    }

    /// Can this process install services (is it running as administrator)?
    pub fn elevated() -> bool {
        manager(ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE).is_ok()
    }

    pub fn installed() -> bool {
        manager(ServiceManagerAccess::CONNECT)
            .and_then(|m| m.open_service(SERVICE, ServiceAccess::QUERY_STATUS))
            .is_ok()
    }

    /// Run this program again as administrator with `args` and wait (Windows asks the person).
    pub fn run_elevated(args: &[&str]) -> anyhow::Result<bool> {
        let exe = std::env::current_exe()?;
        let list = args.iter().map(|a| format!("'{a}'")).collect::<Vec<_>>().join(",");
        let script = format!(
            "$p = Start-Process -FilePath '{}' -ArgumentList {list} -Verb RunAs -Wait -PassThru; exit $p.ExitCode",
            exe.display()
        );
        let status =
            Command::new("powershell.exe").args(["-NoProfile", "-NonInteractive", "-Command", &script]).status()?;
        Ok(status.success())
    }

    pub fn open_browser(port: u16) {
        let _ = Command::new("explorer.exe").arg(format!("http://localhost:{port}/")).status();
    }

    fn stop_service(wait: Duration) -> anyhow::Result<()> {
        let m = manager(ServiceManagerAccess::CONNECT)?;
        let Ok(s) = m.open_service(SERVICE, ServiceAccess::QUERY_STATUS | ServiceAccess::STOP) else { return Ok(()) };
        if s.query_status()?.current_state != ServiceState::Stopped {
            let _ = s.stop();
        }
        let until = std::time::Instant::now() + wait;
        while s.query_status()?.current_state != ServiceState::Stopped {
            anyhow::ensure!(std::time::Instant::now() < until, "the Chorus Home service didn't stop");
            std::thread::sleep(Duration::from_millis(250));
        }
        Ok(())
    }

    fn copy_program(layout: &Layout) -> anyhow::Result<()> {
        std::fs::create_dir_all(&layout.program_dir)?;
        let me = std::env::current_exe()?;
        if me.canonicalize().ok() != layout.exe().canonicalize().ok() {
            std::fs::copy(&me, layout.exe())?;
        }
        #[cfg(feature = "home-bundle")]
        {
            let web = layout.web_dir();
            if web.exists() {
                std::fs::remove_dir_all(&web)?;
            }
            std::fs::create_dir_all(&web)?;
            WEB.extract(&web)?;
        }
        Ok(())
    }

    fn netsh(args: &[&str]) {
        let _ = Command::new("netsh.exe").args(args).status();
    }

    fn reg(args: &[&str]) {
        let _ = Command::new("reg.exe").args(args).status();
    }

    fn shortcut(layout: &Layout) -> PathBuf {
        let pd = layout.data_root.parent().map(Path::to_path_buf).unwrap_or_default();
        pd.join(r"Microsoft\Windows\Start Menu\Programs\Chorus.url")
    }

    /// Install (or update in place). Must run elevated. Returns the port Chorus is on.
    pub fn install(layout: &Layout) -> anyhow::Result<u16> {
        let updating = installed();
        if updating {
            stop_service(Duration::from_secs(30))?;
        }
        copy_program(layout)?;
        std::fs::create_dir_all(layout.data_root.join("data"))?;
        if !layout.config().exists() {
            std::fs::write(layout.config(), first_config(layout, pick_port()))?;
        }
        let port = installed_port(layout).unwrap_or(5250);
        let m = manager(ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE)?;
        let service = if updating {
            m.open_service(SERVICE, ServiceAccess::START | ServiceAccess::CHANGE_CONFIG)?
        } else {
            let info = ServiceInfo {
                name: OsString::from(SERVICE),
                display_name: OsString::from(DISPLAY),
                service_type: ServiceType::OWN_PROCESS,
                start_type: ServiceStartType::AutoStart,
                error_control: ServiceErrorControl::Normal,
                executable_path: layout.exe(),
                launch_arguments: vec!["--config".into(), layout.config().into_os_string(), "service".into()],
                dependencies: vec![],
                account_name: None, // LocalSystem
                account_password: None,
            };
            let s = m.create_service(&info, ServiceAccess::START | ServiceAccess::CHANGE_CONFIG)?;
            s.set_description("Chorus for this computer and the phones on its wifi (docs/HOME.md)")?;
            s
        };
        let restart = ServiceAction { action_type: ServiceActionType::Restart, delay: Duration::from_secs(5) };
        service.update_failure_actions(ServiceFailureActions {
            reset_period: ServiceFailureResetPeriod::After(Duration::from_secs(86_400)),
            reboot_msg: None,
            command: None,
            actions: Some(vec![restart.clone(), restart.clone(), restart]),
        })?;
        // phones on the home wifi reach the TLS port; private networks only, never public wifi
        let lan = port + 1;
        netsh(&["advfirewall", "firewall", "delete", "rule", "name=Chorus Home"]);
        netsh(&[
            "advfirewall",
            "firewall",
            "add",
            "rule",
            "name=Chorus Home",
            "dir=in",
            "action=allow",
            "protocol=TCP",
            &format!("localport={lan}"),
            "profile=private",
            &format!("program={}", layout.exe().display()),
        ]);
        let link = shortcut(layout);
        if let Some(dir) = link.parent() {
            std::fs::create_dir_all(dir)?;
        }
        std::fs::write(&link, format!("[InternetShortcut]\r\nURL=http://localhost:{port}/\r\n"))?;
        let exe = layout.exe().display().to_string();
        let version = env!("CARGO_PKG_VERSION");
        for (name, kind, value) in [
            ("DisplayName", "REG_SZ", DISPLAY.to_string()),
            ("DisplayVersion", "REG_SZ", version.to_string()),
            ("Publisher", "REG_SZ", "Chorus".to_string()),
            ("DisplayIcon", "REG_SZ", exe.clone()),
            ("InstallLocation", "REG_SZ", layout.program_dir.display().to_string()),
            ("UninstallString", "REG_SZ", format!("\"{exe}\" uninstall")),
            ("NoModify", "REG_DWORD", "1".to_string()),
            ("NoRepair", "REG_DWORD", "1".to_string()),
        ] {
            reg(&["add", UNINSTALL_KEY, "/v", name, "/t", kind, "/d", &value, "/f"]);
        }
        service.start::<&str>(&[])?;
        Ok(port)
    }

    /// Remove the service, firewall rule, shortcut and *Apps* entry, and the program folder once
    /// this process has exited. Data stays unless `delete_data`.
    pub fn uninstall(layout: &Layout, delete_data: bool) -> anyhow::Result<()> {
        stop_service(Duration::from_secs(30))?;
        if let Ok(m) = manager(ServiceManagerAccess::CONNECT)
            && let Ok(s) = m.open_service(SERVICE, ServiceAccess::DELETE)
        {
            s.delete()?;
        }
        netsh(&["advfirewall", "firewall", "delete", "rule", "name=Chorus Home"]);
        let _ = std::fs::remove_file(shortcut(layout));
        reg(&["delete", UNINSTALL_KEY, "/f"]);
        if delete_data {
            let _ = std::fs::remove_dir_all(&layout.data_root);
        }
        // the running program can't delete itself: a helper removes the folder once it's gone
        let script = format!("ping -n 3 127.0.0.1 >nul & rmdir /s /q \"{}\"", layout.program_dir.display());
        let _ = Command::new("cmd.exe").args(["/c", &script]).spawn();
        Ok(())
    }

    // ── the service itself ───────────────────────────────────────────────────

    static CONFIG: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

    windows_service::define_windows_service!(ffi_service_main, service_main);

    /// Hand this process to the Service Control Manager (the `service` subcommand).
    pub fn run_service(config: PathBuf) -> anyhow::Result<()> {
        let _ = CONFIG.set(config);
        windows_service::service_dispatcher::start(SERVICE, ffi_service_main)?;
        Ok(())
    }

    fn service_main(_args: Vec<OsString>) {
        if let Err(e) = service_body() {
            tracing::error!(error = %e, "Chorus Home service failed");
        }
    }

    fn service_body() -> anyhow::Result<()> {
        use windows_service::service::{ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceStatus};
        use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
        let handler = |control| match control {
            ServiceControl::Stop | ServiceControl::Shutdown => {
                crate::home::request_stop();
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        };
        let status = service_control_handler::register(SERVICE, handler)?;
        let report = |state, accept| {
            status.set_service_status(ServiceStatus {
                service_type: ServiceType::OWN_PROCESS,
                current_state: state,
                controls_accepted: accept,
                exit_code: ServiceExitCode::Win32(0),
                checkpoint: 0,
                wait_hint: Duration::from_secs(10),
                process_id: None,
            })
        };
        report(ServiceState::Running, ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN)?;
        let config = CONFIG.get().cloned().unwrap_or_default();
        let result = crate::serve_until_stopped(&config);
        report(ServiceState::Stopped, ServiceControlAccept::empty())?;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_config_is_a_home_install_on_two_ports() {
        let layout = Layout {
            program_dir: r"C:\Program Files\Chorus Home".into(),
            data_root: r"C:\ProgramData\Chorus Home".into(),
        };
        let cfg: crate::config::Config = toml::from_str(&first_config(&layout, 5252)).unwrap();
        assert!(cfg.server.home);
        assert_eq!(cfg.server.listen, "127.0.0.1:5252", "this PC's browser: localhost only");
        assert_eq!(cfg.server.lan_listen.as_deref(), Some("0.0.0.0:5253"), "phones: TLS on the next port");
        assert_eq!(cfg.server.public_url, "http://localhost:5252");
        assert_eq!(cfg.server.data_dir, std::path::PathBuf::from("C:/ProgramData/Chorus Home/data"));
        assert_eq!(cfg.server.web_dir, Some("C:/Program Files/Chorus Home/web".into()));
        assert_eq!(cfg.backup.keep_daily, 14);
    }

    #[test]
    fn a_port_pair_in_use_is_skipped() {
        let first = pick_port();
        let _held = std::net::TcpListener::bind(("0.0.0.0", first)).unwrap();
        let next = pick_port();
        assert_ne!(next, first);
        assert_eq!(next % 2, 0, "pairs start on even ports");
    }
}
