//! `chorus.toml` (docs/OPS.md §3). Every option has a default; the file may be missing or empty.

use std::path::{Path, PathBuf};

use serde::Deserialize;

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub server: Server,
    pub push: Push,
    pub backup: Backup,
    pub limits: Limits,
    pub security: Security,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Server {
    pub listen: String,
    pub public_url: String,
    pub data_dir: PathBuf,
    /// Serve the built web app from this directory (the PWA), if set.
    pub web_dir: Option<PathBuf>,
    /// Released APK + `chorus.json` for in-app updates (M12.3). Default: `android/` next to
    /// `web_dir`.
    pub android_dir: Option<PathBuf>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Push {
    pub ntfy_url: Option<String>,
    pub ntfy_token: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Backup {
    pub dir: Option<PathBuf>,
    pub time: String,
    pub keep_daily: u32,
    pub keep_weekly: u32,
    pub keep_monthly: u32,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Limits {
    pub max_blob_mb: u64,
    pub snapshot_threshold: u64,
    pub session_days: u64,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Security {
    pub tailscale_whois: bool,
    /// Legacy switch: `true` means `webhook_targets = "any"`.
    pub webhooks_allow_external: bool,
    /// Where webhooks may point (API.md §7). Default `internal` (a tailnet/LAN install); a public
    /// server (deploy/linux) uses `public`, so accounts can't reach the host's own services.
    pub webhook_targets: Option<WebhookTargets>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WebhookTargets {
    /// Tailnet and LAN addresses only (never loopback: the host's own admin ports).
    #[default]
    Internal,
    /// Globally routable addresses only: for a server on the public internet.
    Public,
    /// Anything, including loopback (tests, or an owner who knows what's listening).
    Any,
}

impl Security {
    pub fn webhook_targets(&self) -> WebhookTargets {
        self.webhook_targets.unwrap_or(if self.webhooks_allow_external {
            WebhookTargets::Any
        } else {
            WebhookTargets::Internal
        })
    }
}

impl Default for Server {
    fn default() -> Self {
        Server {
            listen: "127.0.0.1:5250".into(),
            public_url: "http://127.0.0.1:5250".into(),
            data_dir: PathBuf::from("data"),
            web_dir: None,
            android_dir: None,
        }
    }
}

impl Default for Backup {
    fn default() -> Self {
        Backup { dir: None, time: "04:00".into(), keep_daily: 14, keep_weekly: 8, keep_monthly: 12 }
    }
}

impl Default for Limits {
    fn default() -> Self {
        Limits { max_blob_mb: 100, snapshot_threshold: 20_000, session_days: 30 }
    }
}

impl Config {
    pub fn android_dir(&self) -> Option<PathBuf> {
        self.server
            .android_dir
            .clone()
            .or_else(|| self.server.web_dir.as_ref().and_then(|w| w.parent()).map(|p| p.join("android")))
    }

    /// Load from a file if it exists; otherwise defaults.
    pub fn load(path: Option<&Path>) -> anyhow::Result<Config> {
        let Some(path) = path else { return Ok(Config::default()) };
        if !path.exists() {
            return Ok(Config::default());
        }
        let text = std::fs::read_to_string(path)?;
        toml::from_str(&text).map_err(|e| anyhow::anyhow!("{}: {e}", path.display()))
    }

    /// Development profile (docs/OPS.md §8): port 5251, data in ./data-dev.
    pub fn dev() -> Config {
        let mut c = Config::default();
        c.server.listen = "127.0.0.1:5251".into();
        c.server.public_url = "http://127.0.0.1:5252".into();
        c.server.data_dir = PathBuf::from("data-dev");
        c
    }

    pub fn db_path(&self) -> PathBuf {
        self.server.data_dir.join("chorus.db")
    }

    pub fn blob_dir(&self) -> PathBuf {
        self.server.data_dir.join("blobs")
    }

    pub fn backup_dir(&self) -> PathBuf {
        self.backup.dir.clone().unwrap_or_else(|| self.server.data_dir.join("backups"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ops_example_and_defaults() {
        let c: Config = toml::from_str(
            r#"
            [server]
            listen = "127.0.0.1:5250"
            public_url = "https://chorus.vayne.garden"
            data_dir = "C:/Users/pcuser/selfhost/chorus/data"
            [push]
            ntfy_url = "https://ntfy.vayne.garden"
            [backup]
            keep_daily = 7
            "#,
        )
        .unwrap();
        assert_eq!(c.backup.keep_daily, 7);
        assert_eq!(c.backup.keep_weekly, 8);
        assert_eq!(c.limits.max_blob_mb, 100);
        assert!(toml::from_str::<Config>("[server]\nlisten_on = 1").is_err(), "typos are errors");
    }
}
