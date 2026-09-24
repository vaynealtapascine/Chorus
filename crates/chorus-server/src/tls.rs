//! Chorus Home's home-wifi listener (D-071, docs/HOME.md §2): TLS with a certificate the server
//! makes for itself, which phones trust by pinning its fingerprint from the invite QR rather than
//! through a CA. The PC's own browser keeps using plain `http://localhost` (a secure context).

use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use base64::Engine as _;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};

/// How long a client gets to finish the TLS handshake.
const HANDSHAKE_WITHIN: Duration = Duration::from_secs(10);

/// The server's own certificate: DER, its private key, and the pin phones check.
pub struct Identity {
    pub cert: CertificateDer<'static>,
    key: PrivatePkcs8KeyDer<'static>,
    /// `sha256/<base64url of the certificate's SHA-256>`, as carried in `#pin=` invite links.
    pub pin: String,
}

fn files(data_dir: &Path) -> (PathBuf, PathBuf) {
    let dir = data_dir.join("tls");
    (dir.join("home.crt.der"), dir.join("home.key.der"))
}

/// The pin for a certificate: SHA-256 of its DER encoding, base64url without padding.
pub fn pin_of(cert: &[u8]) -> String {
    format!("sha256/{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(Sha256::digest(cert)))
}

/// Load the certificate from `<data_dir>/tls/`, or make one the first time. It is kept for good:
/// a new certificate would break every phone's pin, so it is never rotated silently.
pub fn load_or_create(data_dir: &Path) -> anyhow::Result<Identity> {
    let (cert_path, key_path) = files(data_dir);
    if cert_path.is_file() && key_path.is_file() {
        let cert = CertificateDer::from(std::fs::read(&cert_path)?);
        let key = PrivatePkcs8KeyDer::from(std::fs::read(&key_path)?);
        let pin = pin_of(&cert);
        return Ok(Identity { cert, key, pin });
    }
    let host = hostname();
    let mut names = vec!["localhost".to_string()];
    if let Some(h) = &host {
        names.push(h.clone());
        names.push(format!("{h}.local"));
    }
    let mut params = rcgen::CertificateParams::new(names)?;
    params.distinguished_name.push(rcgen::DnType::CommonName, host.unwrap_or_else(|| "Chorus Home".into()));
    // Pinned by fingerprint, so the dates only have to satisfy clients that check them
    params.not_before = rcgen::date_time_ymd(2026, 1, 1);
    params.not_after = rcgen::date_time_ymd(2046, 1, 1);
    let key = rcgen::KeyPair::generate()?;
    let cert = params.self_signed(&key)?;
    let dir = cert_path.parent().expect("tls dir");
    std::fs::create_dir_all(dir)?;
    // the key first: a certificate without its key would be unusable
    write_private(&key_path, &key.serialize_der())?;
    std::fs::write(&cert_path, cert.der())?;
    tracing::info!(pin = %pin_of(cert.der()), "made the home-wifi TLS certificate");
    let cert = cert.der().clone();
    let pin = pin_of(&cert);
    Ok(Identity { cert, key: PrivatePkcs8KeyDer::from(key.serialize_der()), pin })
}

fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path)
}

fn hostname() -> Option<String> {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .map(|h| h.trim().to_ascii_lowercase())
        .filter(|h| !h.is_empty() && h.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'))
}

/// This PC's address on the home network: the one the OS would use to reach the internet.
/// Connecting a UDP socket sends nothing; it only picks the route. `None` when offline.
pub fn lan_ip() -> Option<std::net::IpAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?; // TEST-NET-1: never answered, never sent to
    let ip = socket.local_addr().ok()?.ip();
    (!ip.is_unspecified() && !ip.is_loopback()).then_some(ip)
}

/// The base URL phones use on the home wifi, e.g. `https://192.168.1.20:5251`.
pub fn lan_base(listen: &str) -> Option<String> {
    let port = listen.rsplit(':').next()?.parse::<u16>().ok()?;
    Some(format!("https://{}:{port}", lan_ip()?))
}

/// An invite link a phone opens on the home wifi: the LAN address plus the certificate pin.
pub fn lan_invite(base: &str, code: &str, pin: &str) -> String {
    format!("{}/i/{code}#pin={pin}", base.trim_end_matches('/'))
}

/// With a home-wifi listener configured: the invite link a phone should get for `code`.
pub fn home_invite(cfg: &crate::config::Config, code: &str) -> Option<String> {
    let listen = cfg.server.lan_listen.as_deref()?;
    let id = load_or_create(&cfg.server.data_dir).ok()?;
    Some(lan_invite(&lan_base(listen)?, code, &id.pin))
}

/// A listener that hands axum TLS streams. Handshakes run on their own tasks (with a deadline),
/// so a slow or hostile client can't hold up the next connection.
pub struct TlsListener {
    rx: tokio::sync::mpsc::Receiver<(tokio_rustls::server::TlsStream<tokio::net::TcpStream>, SocketAddr)>,
    local: SocketAddr,
}

impl TlsListener {
    pub async fn bind(addr: &str, id: &Identity) -> anyhow::Result<TlsListener> {
        // name the provider: the build may carry more than one, and then there is no default
        let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
        let config = rustls::ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()?
            .with_no_client_auth()
            .with_single_cert(vec![id.cert.clone()], PrivateKeyDer::Pkcs8(id.key.clone_key()))?;
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(config));
        let tcp = tokio::net::TcpListener::bind(addr).await?;
        let local = tcp.local_addr()?;
        let (tx, rx) = tokio::sync::mpsc::channel(64);
        tokio::spawn(async move {
            loop {
                let (stream, peer) = match tcp.accept().await {
                    Ok(c) => c,
                    Err(e) => {
                        tracing::warn!(error = %e, "home-wifi accept failed");
                        tokio::time::sleep(Duration::from_millis(100)).await;
                        continue;
                    }
                };
                let (acceptor, ready) = (acceptor.clone(), tx.clone());
                tokio::spawn(async move {
                    match tokio::time::timeout(HANDSHAKE_WITHIN, acceptor.accept(stream)).await {
                        Ok(Ok(tls)) => {
                            let _ = ready.send((tls, peer)).await;
                        }
                        Ok(Err(e)) => tracing::debug!(%peer, error = %e, "TLS handshake failed"),
                        Err(_) => tracing::debug!(%peer, "TLS handshake timed out"),
                    }
                });
                if tx.is_closed() {
                    return;
                }
            }
        });
        Ok(TlsListener { rx, local })
    }
}

impl axum::serve::Listener for TlsListener {
    type Io = tokio_rustls::server::TlsStream<tokio::net::TcpStream>;
    type Addr = SocketAddr;

    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        match self.rx.recv().await {
            Some(c) => c,
            // the accept task only ends once this listener is gone
            None => std::future::pending().await,
        }
    }

    fn local_addr(&self) -> std::io::Result<Self::Addr> {
        Ok(self.local)
    }
}

/// The client address of a home-wifi connection. axum only fills `ConnectInfo<SocketAddr>` for its
/// own TCP listener, so [`with_peer`] copies this into it for the rate limits and sync sockets.
#[derive(Clone, Copy, Debug)]
pub struct Peer(pub SocketAddr);

impl axum::extract::connect_info::Connected<axum::serve::IncomingStream<'_, TlsListener>> for Peer {
    fn connect_info(stream: axum::serve::IncomingStream<'_, TlsListener>) -> Self {
        Peer(*stream.remote_addr())
    }
}

/// `router`, reading the client address the way the plain listener provides it.
pub fn with_peer(router: axum::Router) -> axum::Router {
    router.layer(axum::middleware::map_request(
        |axum::extract::ConnectInfo(Peer(addr)): axum::extract::ConnectInfo<Peer>, mut req: axum::extract::Request| async move {
            req.extensions_mut().insert(axum::extract::ConnectInfo(addr));
            req
        },
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_certificate_is_made_once_and_kept() {
        let dir = std::env::temp_dir().join(format!("chorus-tls-test-{:016x}", rand::random::<u64>()));
        let first = load_or_create(&dir).unwrap();
        let again = load_or_create(&dir).unwrap();
        assert_eq!(first.pin, again.pin, "a restart must not change the pin phones hold");
        assert!(first.pin.starts_with("sha256/"));
        assert_eq!(first.pin, pin_of(&again.cert));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn lan_invites_carry_the_pin() {
        assert_eq!(
            lan_invite("https://192.168.1.20:5251/", "ABCD", "sha256/xyz"),
            "https://192.168.1.20:5251/i/ABCD#pin=sha256/xyz"
        );
    }
}
